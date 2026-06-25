package com.max;

import com.max.entity.Shop;
import com.max.service.impl.ShopServiceImpl;
import com.max.utils.CacheClient;
import com.max.utils.RedisConstants;
import com.max.utils.RedisIDWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@SpringBootTest
class ApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisIDWorker redisIDWorker;

    private ExecutorService executorService= Executors.newFixedThreadPool(500);

    @Test
    void testSaveShop() throws InterruptedException {
        CountDownLatch latch=new CountDownLatch(300);
       Runnable task=()->{
           for (int i = 0; i < 100; i++) {
               long id = redisIDWorker.nextId("order");
               System.out.println("id= "+id);
           }
           latch.countDown();
       };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            executorService.submit(task);
        }
        latch.await();
        long end =System.currentTimeMillis();
        System.out.println(end-begin);
    }

    @Test
    void loadShopData(){
        List<Shop> list = shopService.list();

        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            String key=RedisConstants.SHOP_GEO_KEY+typeId;
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations=new ArrayList<>(value.size());

            for (Shop shop : value) {
//                stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(), shop.getY()),shop.getId().toString())
            locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(), shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key,locations);
        }


    }

    @Test
    void testBasicOps(){
        
    }


}
