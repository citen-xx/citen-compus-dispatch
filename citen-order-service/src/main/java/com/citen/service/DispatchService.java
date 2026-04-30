package com.citen.service;

import com.citen.entity.Lab;
import com.citen.mapper.LabMapper;
import com.citen.utils.RedisConstants;
import com.citen.websocket.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.ArrayList;
import java.util.List;

@Service
public class DispatchService {

    private static final Logger LOG = LoggerFactory.getLogger(DispatchService.class);

    @javax.annotation.Resource
    private StringRedisTemplate stringRedisTemplate;

    @javax.annotation.Resource
    private LabMapper labMapper;

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

    public void dispatchOrder(Long orderId, Long labId) {
        if (labId == null) {
            LOG.warn("dispatch skipped, labId is null, orderId={}", orderId);
            return;
        }

        Lab lab = labMapper.selectById(labId);
        if (lab == null || lab.getX() == null || lab.getY() == null) {
            LOG.warn("dispatch skipped, lab location missing, orderId={}, labId={}", orderId, labId);
            return;
        }

        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                RedisConstants.DELIVERY_RIDER_GEO_KEY,
                GeoReference.fromCoordinate(lab.getX(), lab.getY()),
                new Distance(3, Metrics.KILOMETERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().sortAscending().limit(1)
        );

        if (results == null || results.getContent().isEmpty()) {
            LOG.warn("no rider found within 3km, orderId={}, labId={}", orderId, labId);
            return;
        }

        GeoResult<RedisGeoCommands.GeoLocation<String>> riderResult = results.getContent().get(0);
        String riderName = riderResult.getContent().getName();
        LOG.info("dispatch success, orderId={}, labId={}, rider={}, distance={}km",
                orderId, labId, riderName, riderResult.getDistance() == null ? null : riderResult.getDistance().getValue());

        WebSocketServer.sendToShop(labId, "有新的预约任务已分配");
    }
}
