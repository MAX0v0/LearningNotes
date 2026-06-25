package com.max.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.max.dto.Result;
import com.max.entity.Shop;
import com.max.mapper.ShopMapper;
import com.max.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.max.utils.CacheClient;
import com.max.utils.RedisConstants;
import com.max.utils.RedisData;
import com.max.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 *
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;


    @Override
    //查询店铺,目前采用的是缓存穿透+缓存击穿(逻辑过期)
    public Result queryById(Long id) {
        //Shop shop = queryWithPassThough(id);//缓存穿透封装的解决方案
        //Shop shop = queryWithMutex(id);//缓存穿透+缓存击穿解决方案
        //Shop shop = queryWithLogicalExpire(id);//缓存穿透+逻辑过期解决方案
        //解决缓存穿透问题(存null值)
        //Shop shop = cacheClient.queryWithPassThough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //解决缓存击穿问题(热点key)
        Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if(shop==null){
            return Result.fail("查询失败");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional
    //更新店铺.更新数据库,删除缓存
    public Result update(Shop shop) {
        if(shop.getId()==null){
            return Result.fail("无效修改");
        }
        //先更新数据库,再删除缓存
        updateById(shop);
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //是否需要坐标
        if(x==null||y==null){
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //计算分页参数
        int from =(current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end =current*SystemConstants.DEFAULT_PAGE_SIZE;

        //查询redis,按照距离排序,结果:shopId,distance
        String key=RedisConstants.SHOP_GEO_KEY+typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));

        //解析出id
        if(results==null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size()<=from){
            return Result.ok(Collections.emptyList());
        }
        //截取
        List<Long> ids=new ArrayList<>(list.size());
        Map<String,Distance> distanceMap=new HashMap<>(list.size());
        list.stream().skip(from).forEach(result->{
            String shopIdStr = result.getContent().getName();//店铺id
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();//获取距离
            distanceMap.put(shopIdStr,distance);

        });

        //根据id查shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }


    //缓存穿透封装方法
    public Shop queryWithPassThough(Long id){
        //从redis里面查询商品缓存,判断是否存在
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);

        //存在--直接返回,命中的不是空值
        if (StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //--存在--命中的是空值
        if(shopJson!=null){
            return null;
        }
        //不存在--根据id查数据库
        Shop shop = getById(id);
        //不存在--返回错误
        if(shop==null){
            //尽大可能减少缓存穿透问题,所以查询失败的时候缓存空值到redis里面,并设置有效期
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,"",RedisConstants.CACHE_NULL_TTL+ RandomUtil.randomLong(1L,10L),TimeUnit.MINUTES);
            return null;
        }
        //存在--写入redis,并返回,并且设置缓存时间
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }

    //查询数据库,缓存穿透+缓存击穿(加锁)解决办法
    /*public Shop queryWithMutex(Long id){
        //从redis里面查询商品缓存,判断是否存在
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);

        //存在--直接返回,命中的不是空值
        if (StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //--存在--命中的是空值
        if(shopJson!=null){
            return null;
        }
        //不存在--根据id查数据库,这里加锁
        //实现缓存重建,得到互斥锁,去操作,没有得到锁的,休眠50毫秒并重试
        Shop shop;
        while(!tryGetLock(RedisConstants.LOCK_SHOP_KEY+id)){
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            //醒来后第一件事马上看缓存有没有,有就直接返回数据了
            String shopJson2=stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
            //存在--直接返回,命中的不是空值
            if (StrUtil.isNotBlank(shopJson2)){
                return JSONUtil.toBean(shopJson2, Shop.class);
            }
            //--存在--命中的是空值
            if(shopJson2!=null){
                return null;
            }
        }
        try {
//            boolean isGetLock = tryGetLock(RedisConstants.LOCK_SHOP_KEY + id);
//            if(!isGetLock){
//                Thread.sleep(50);
//                return queryWithMutex(id);
//            }

            //拿到锁之后,也要先看看缓存里有没有数据,双重验证,有了也就返回,并且释放锁
            String shopJson3= stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
            //存在--直接返回,命中的不是空值
            if (StrUtil.isNotBlank(shopJson3)){
                return JSONUtil.toBean(shopJson3, Shop.class);
            }
            //--存在--命中的是空值
            if(shopJson3!=null){
                return null;
            }

            shop = getById(id);
            //不存在--返回错误
            if(shop==null){
                //尽大可能减少缓存穿透问题,所以查询失败的时候缓存空值到redis里面,并设置有效期
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,"",RedisConstants.CACHE_NULL_TTL+ RandomUtil.randomLong(1L,10L),TimeUnit.MINUTES);
                return null;
            }
            //存在--写入redis,并返回,并且设置缓存时间
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } finally {
            //释放互斥锁
            delectLock(RedisConstants.LOCK_SHOP_KEY + id);
        }
        return shop;
    }*/

    //缓存穿透+缓存击穿(逻辑过期)解决办法
    /*public Shop queryWithLogicalExpire(Long id){
        //从redis里面查询商品缓存,判断是否存在
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //不存在--返回null
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //存在,判断是否为过期

           if(expireTime.isAfter(LocalDateTime.now())){
               //未过期-直接返回
               return shop;
           }
           //过期--尝试获取锁,获取成功,开启新线程,返回旧数据,释放锁
           boolean isLock = tryGetLock(RedisConstants.LOCK_SHOP_KEY + id);
        try {
            if(isLock){//得到锁以后也要立即检查一次缓存有没有过期,双重验证
                String shopJson2 = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
                RedisData redisData2 = JSONUtil.toBean(shopJson2, RedisData.class);
                Shop shop2 = JSONUtil.toBean((JSONObject) redisData2.getData(), Shop.class);
                LocalDateTime expireTime2 = redisData2.getExpireTime();
                if(expireTime2.isAfter(LocalDateTime.now())){
                    //未过期-直接返回
                    delectLock(RedisConstants.LOCK_SHOP_KEY + id);
                    return shop2;
                }
                //开启独立线程,修改数据
                CACHE_REBUILD_EXECUTOR.submit(()->{
                    try {
                        this.saveShop2Redis(id,30L);
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

           return shop;
    }*/

    //封装查询的新的逻辑数据
    public void saveShop2Redis(Long id,Long expireSeconds){
        //查询店铺数据
        Shop shop = getById(id);
        //封装逻辑过期时间
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));

    }
}
