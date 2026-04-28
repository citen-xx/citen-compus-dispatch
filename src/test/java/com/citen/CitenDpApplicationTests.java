package com.citen;

import com.citen.entity.Shop;
import com.citen.service.impl.ShopServiceImpl;
import com.citen.utils.CacheClient;
import com.citen.utils.RedisConstants;
import com.citen.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.citen.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class CitenDpApplicationTests {

    @Resource
    private CacheClient cacheClient;

    @Resource
    private  ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    private ExecutorService es= Executors.newFixedThreadPool(500);
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testIdWorkers() throws InterruptedException {
        CountDownLatch latch=new CountDownLatch(300);
        Runnable task=()->{
            for (int i = 0; i < 100; i++) {
                long id=redisIdWorker.nextId("order");
                System.out.println("id="+id);
            }
            latch.countDown();
        };
        long begin=System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end=System.currentTimeMillis();
        System.out.println("time="+(end-begin));
    }


    @Test
    void testSaveShop(){
        Shop shop=shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY+1L,shop,10L, TimeUnit.SECONDS);
    }

    @Test
    void loadShopData(){
        //查询店铺信息
        List<Shop> list = shopService.list();
        //把店铺分组
        Map<Long,List<Shop>> map=list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long ,List<Shop>>entry:map.entrySet()){
            //获取类型id
            Long typeId=entry.getKey();
            String key = RedisConstants.SHOP_GEO_KEY + typeId;
            //获取同类型的店铺的集合
            List<Shop>value=entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>>locations=new ArrayList<>();

            //写入redis
            for (Shop shop:value){
            //    stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(),shop.getY()),shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(),shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key,locations);

        }
        //分批完成写入redis
    }
    @Test
    void testHyperLogLog(){
        String[]values=new String[1000];
        int j=0;
        for (int i = 0; i < 1000; i++) {
            j=i%1000;
            values[j]="user_"+i;
            if(j==999){
                //发送到redis
                Long count = stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
                System.out.println("count="+count);
            }

        }
        stringRedisTemplate.opsForHyperLogLog().add("hl2",values);
    }

}
