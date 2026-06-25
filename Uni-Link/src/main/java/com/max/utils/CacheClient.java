package com.max.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    //创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //方法1:将任意Java对象序列化为json并存储在String类型Key中,并设置TTL过期时间
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    //方法2:将任意Java对象序列化为json并存储在String类型Key中,并设置逻辑过期时间,用于处理缓存击穿
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisData.setData(value);

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //方法3:根据指定的key查询,并且反序列化为指定类型,利用缓存空值的方式解决缓存穿透的问题
    //参数列表依次为:存储的key前缀,传的商品id,返回值类型,查询方法实现,设置的缓存有效期,设置的缓存有效期类型
    public <R,ID> R queryWithPassThough(String KeyPrefix, ID id, Class<R> type, Function<ID,R> dbFallBack, Long time, TimeUnit unit){
        //因为我们不知道调用者怎么查询数据库,所以我们采用函数式接口,有参有返回值类型,让调用者自己去实现数据库查询操作,这属于一种降级,只有当自己工具类执行失败了才用自己写的方法
        //从redis里面查询商品缓存,判断是否存在
        String json = stringRedisTemplate.opsForValue().get(KeyPrefix + id);

        //存在--直接返回,命中的不是空值
        if (StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        //--存在--命中的是空值
        if(json!=null){
            return null;
        }
        //不存在--根据id查数据库
        R r = dbFallBack.apply(id);
        //不存在--返回错误
        if(r==null){
            //尽大可能减少缓存穿透问题,所以查询失败的时候缓存空值到redis里面,并设置空值有效期
            stringRedisTemplate.opsForValue().set(KeyPrefix + id,"",RedisConstants.CACHE_NULL_TTL+ RandomUtil.randomLong(1L,10L),TimeUnit.MINUTES);
            return null;
        }
        //存在--写入redis,并返回,并且设置缓存时间
        this.set(KeyPrefix + id,r,time,unit);
        return r;
    }

    //方法4:根据指定的key查询,并且反序列化为指定类型,需要利用逻辑过期解决缓存击穿问题
    //缓存穿透+缓存击穿(逻辑过期)解决办法
    //参数列表依次为:key前缀,传入的id,返回值类型,查询方法.设置的逻辑过期时间(当前时间+time),逻辑过期时间类型
    public <R,ID> R queryWithLogicalExpire(String KeyPrefix, ID id, Class<R> type, Function<ID,R> dbFallBack, Long time, TimeUnit unit){
        //从redis里面查询商品缓存,判断是否存在
        String json = stringRedisTemplate.opsForValue().get(KeyPrefix + id);
        //不存在--返回null
        if(StrUtil.isBlank(json)){
            return null;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //存在,判断是否为过期

        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期-直接返回
            return r;
        }
        //过期--尝试获取锁,获取成功,开启新线程,返回旧数据,释放锁
        boolean isLock = tryGetLock(RedisConstants.LOCK_SHOP_KEY + id);
        try {
            if(isLock){//得到锁以后也要立即检查一次缓存有没有过期,双重验证
                String json2 = stringRedisTemplate.opsForValue().get(KeyPrefix + id);
                RedisData redisData2 = JSONUtil.toBean(json2, RedisData.class);
                R r2 = JSONUtil.toBean((JSONObject) redisData2.getData(), type);
                LocalDateTime expireTime2 = redisData2.getExpireTime();
                if(expireTime2.isAfter(LocalDateTime.now())){
                    //未过期-直接返回
                    delectLock(RedisConstants.LOCK_SHOP_KEY + id);
                    return r2;
                }
                //开启独立线程,修改数据
                CACHE_REBUILD_EXECUTOR.submit(()->{
                    try {
                        //查询数据库
                        R r1 = dbFallBack.apply(id);
                        //写入Redis
                        this.setWithLogicalExpire(KeyPrefix+id,r1,time,unit);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                        //子线程释放锁
                    } finally {delectLock(RedisConstants.LOCK_SHOP_KEY + id);
                    }
                });
            }
        } catch (Exception e){
            throw new RuntimeException(e);
        }
        //过期--尝试获取锁,获取失败,返回旧数据

        return r;
    }

    //为了应对缓存击穿问题,而加的锁
    private boolean tryGetLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }
    private void delectLock(String key){
        stringRedisTemplate.delete(key);
    }
}
