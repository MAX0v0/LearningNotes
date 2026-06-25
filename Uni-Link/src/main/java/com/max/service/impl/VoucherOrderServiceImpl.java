package com.max.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.max.dto.Result;
import com.max.entity.VoucherOrder;
import com.max.mapper.VoucherOrderMapper;
import com.max.service.ISeckillVoucherService;
import com.max.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.max.utils.RedisIDWorker;

import com.max.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 *
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService iSeckillVoucherService;
    @Resource
    private RedisIDWorker redisIDWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    @Resource
    @Lazy
    private IVoucherOrderService proxy;

    //private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{
        private String queueName="stream.orders";
        @Override
        public void run() {
            while(true){
                try {
                    //1获取消息队列中的信息//XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );

                    //2判断消息是否获取成功
                    if(list==null||list.isEmpty()){
                        //2.1如果获取失败说明没有消息,continue
                        continue;
                    }
                    //2.2如果有消息,创建订单
                    //3解析订单//泛型依次为:消息id,键值对
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);//true:出错了忽略

                    //3.1 创建订单
                    handleVoucherOrder(voucherOrder);
                    //3.2创建完订单后,做ACK确认SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());


                } catch (Exception e) {
                    //如果有异常,就从pendingList中取
                    log.error("处理订单异常",e);
                    handlePendingList();
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }

        }

        private void handlePendingList() {
            while(true){
                try {
                    //1获取pendingList中的信息//XREADGROUP GROUP g1 c1 COUNT 1  STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );

                    //2判断消息是否获取成功
                    if(list==null||list.isEmpty()){
                        //2.1如果获取失败说明没有消息,break
                        break;
                    }
                    //2.2如果有消息,创建订单
                    //3解析订单//泛型依次为:消息id,键值对
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);//true:出错了忽略

                    //3.1 创建订单
                    handleVoucherOrder(voucherOrder);
                    //3.2创建完订单后,做ACK确认SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());


                } catch (Exception e) {
                    //如果有异常,就从pendingList中取
                    log.error("处理pendingList订单异常",e);
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ex) {
                        //throw new RuntimeException(ex);
                    }
                }
            }

        }
        /*@Override
        public void run() {
        while(true){
            //获取队列中得到信息
            try {
                VoucherOrder voucherOrder = orderTasks.take();
                //创建订单
                handleVoucherOrder(voucherOrder);
            } catch (Exception e) {
                log.error("处理订单异常",e);
            }
         }
        }*/
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //创建锁对象,获取锁,使用的是Redisson的锁
        Long userId = voucherOrder.getUserId();
        //SimpleRedisLock lock=new SimpleRedisLock(stringRedisTemplate,"order:"+userID);
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        boolean isSuccess = lock.tryLock();//无参:默认失败立即返回,最大锁存在时间30s
        if(!isSuccess){
            log.error("不允许重复下单(理论上这句话不应该出现)");
            return ;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }

    }
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIDWorker.nextId("order");
        //1.执行lua脚本,判断结果
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT
                , Collections.emptyList()
                , voucherId.toString(), userId.toString(),String.valueOf(orderId));
        int r=result.intValue();
        if(r!=0){
            return Result.fail(r==1?"已经抢购完了":"一个用户只能下一单");
        }


        //获取动态代理对象
         //proxy = (IVoucherOrderService) AopContext.currentProxy();

        //返回订单id
        return Result.ok(orderId);
    }
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //查询优惠券信息
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
          //时间未开始,返回异常结果
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("失败,活动未开始");
        }
          //时间已结束,返回异常结果
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("失败,活动已结束");
        }
        //时间开始了,判断余额是否充足
        //不充足,返回异常结果
        if (voucher.getStock()<1) {
            return Result.fail("失败,库存不足");
        }
        Long userID=UserHolder.getUser().getId();
        //创建锁对象,获取锁,使用的是Redisson的锁
        //SimpleRedisLock lock=new SimpleRedisLock(stringRedisTemplate,"order:"+userID);
        RLock lock = redissonClient.getLock("lock:order:" + userID);

        boolean isSuccess = lock.tryLock();//无参:默认失败立即返回,最大锁存在时间30s
        if(!isSuccess){
            return Result.fail("不允许重复下单,请求失败");
        }

        try {
            //获取动态代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }

    }*/
    @Transactional
    public  void createVoucherOrder(VoucherOrder voucherOrder){
        Long userID=voucherOrder.getId();
        //判断是不是用户已经购买过一次了,这里的锁只锁用户,不用用户之间不会锁
        int count = query().eq("user_id",userID).eq("voucher_id",voucherOrder.getVoucherId()).count();
            if(count>0){
                log.error("你已经购买过一次了(此消息不应该出现)");
            }
            //扣减库存,创建订单,返回订单id,目前扣减库存方式:引入乐观锁CAS方法,引入每次扣库存时查询是不是已经买过了,如果是,不执行扣库存
            boolean isSuccess=iSeckillVoucherService.update()
                    .setSql("stock=stock-1")
                    .eq("voucher_id",voucherOrder.getVoucherId())
                    .gt("stock",0)
                    .update();
            if(!isSuccess){
                log.error("失败,库存不足,(此消息不应该出现)");
            }
            save(voucherOrder);
    }
}
