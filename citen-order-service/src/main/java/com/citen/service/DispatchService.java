package com.citen.service;

import com.citen.entity.Shop;
import com.citen.mapper.ShopMapper;
import com.citen.utils.RedisConstants;
import com.citen.websocket.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class DispatchService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ShopMapper shopMapper;

    @PostConstruct
    public void initRiderGeoData() {
        Boolean exists = stringRedisTemplate.hasKey(RedisConstants.DELIVERY_RIDER_GEO_KEY);
        if (Boolean.TRUE.equals(exists)) {
            return;
        }

        List<RedisGeoCommands.GeoLocation<String>> riders = new ArrayList<>(4);
        riders.add(new RedisGeoCommands.GeoLocation<>("骑手张三", new Point(120.149500, 30.315900)));
        riders.add(new RedisGeoCommands.GeoLocation<>("骑手李四", new Point(120.151200, 30.318100)));
        riders.add(new RedisGeoCommands.GeoLocation<>("骑手王五", new Point(120.146800, 30.314500)));
        riders.add(new RedisGeoCommands.GeoLocation<>("骑手赵六", new Point(120.154000, 30.320200)));
        stringRedisTemplate.opsForGeo().add(RedisConstants.DELIVERY_RIDER_GEO_KEY, riders);
    }

    public void dispatchOrder(Long orderId, Long shopId) {
        if (shopId == null) {
            log.warn("dispatch skipped, shopId is null, orderId={}", orderId);
            return;
        }

        Shop shop = shopMapper.selectById(shopId);
        if (shop == null || shop.getX() == null || shop.getY() == null) {
            log.warn("dispatch skipped, shop location missing, orderId={}, shopId={}", orderId, shopId);
            return;
        }

        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                RedisConstants.DELIVERY_RIDER_GEO_KEY,
                GeoReference.fromCoordinate(shop.getX(), shop.getY()),
                new Distance(3, Metrics.KILOMETERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().sortAscending().limit(1)
        );

        if (results == null || results.getContent().isEmpty()) {
            log.warn("no rider found within 3km, orderId={}, shopId={}", orderId, shopId);
            return;
        }

        GeoResult<RedisGeoCommands.GeoLocation<String>> riderResult = results.getContent().get(0);
        String riderName = riderResult.getContent().getName();
        log.info("dispatch success, orderId={}, shopId={}, rider={}, distance={}km",
                orderId, shopId, riderName, riderResult.getDistance() == null ? null : riderResult.getDistance().getValue());

        WebSocketServer.sendToShop(shopId, "有新订单已接单");
    }
}
