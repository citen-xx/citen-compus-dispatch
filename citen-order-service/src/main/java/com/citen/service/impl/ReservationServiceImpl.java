package com.citen.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citen.config.RabbitMQConfig;
import com.citen.dto.Result;
import com.citen.entity.Reservation;
import com.citen.entity.Resource;
import com.citen.entity.ResourceQuota;
import com.citen.mapper.ReservationMapper;
import com.citen.service.DispatchService;
import com.citen.service.IReservationService;
import com.citen.service.IResourceQuotaService;
import com.citen.service.IResourceService;
import com.citen.strategy.ResourceAllocationStrategyFactory;
import com.citen.strategy.ResourceAllocationStrategyType;
import com.citen.utils.RedisConstants;
import com.citen.utils.RedisIdWorker;
import com.citen.utils.UserHolder;
import com.citen.websocket.WebSocketServer;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ReservationServiceImpl extends ServiceImpl<ReservationMapper, Reservation> implements IReservationService {

    private static final Logger LOG = LoggerFactory.getLogger(ReservationServiceImpl.class);

    private static final int RESERVATION_STATUS_PENDING_CONFIRM = 1;
    private static final int RESERVATION_STATUS_CANCELED = 4;

    private static final DefaultRedisScript<Long> RESERVATION_SCRIPT;

    static {
        RESERVATION_SCRIPT = new DefaultRedisScript<>();
        RESERVATION_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        RESERVATION_SCRIPT.setResultType(Long.class);
    }

    @javax.annotation.Resource
    private IResourceQuotaService resourceQuotaService;

    @javax.annotation.Resource
    private IResourceService resourceService;

    @javax.annotation.Resource
    private RedisIdWorker redisIdWorker;

    @javax.annotation.Resource
    private RedissonClient redissonClient;

    @javax.annotation.Resource
    private StringRedisTemplate stringRedisTemplate;

    @javax.annotation.Resource
    private ResourceAllocationStrategyFactory resourceAllocationStrategyFactory;

    @javax.annotation.Resource
    private RabbitTemplate rabbitTemplate;

    @javax.annotation.Resource
    private DispatchService dispatchService;

    private final BlockingQueue<Reservation> reservationTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService RESERVATION_EXECUTOR = Executors.newSingleThreadExecutor();
    private final String streamQueueName = "stream.reservations";

    private IReservationService proxy;

    @PostConstruct
    private void init() {
        RESERVATION_EXECUTOR.submit(new ReservationTaskHandler());
        initQuotaToRedis();
    }

    private void initQuotaToRedis() {
        try {
            List<ResourceQuota> quotas = resourceQuotaService.list();
            for (ResourceQuota quota : quotas) {
                String quotaKey = RedisConstants.RESOURCE_QUOTA_KEY + quota.getResourceId();
                if (stringRedisTemplate.opsForValue().get(quotaKey) == null) {
                    stringRedisTemplate.opsForValue().set(quotaKey, quota.getQuota().toString());
                    LOG.info("init quota to redis, resourceId={}, quota={}", quota.getResourceId(), quota.getQuota());
                }
            }
        } catch (Exception e) {
            LOG.error("init quota to redis failed", e);
        }
    }

    private class ReservationTaskHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(streamQueueName, ReadOffset.lastConsumed())
                    );
                    if (list == null || list.isEmpty()) {
                        continue;
                    }

                    MapRecord<String, Object, Object> values = list.get(0);
                    Map<Object, Object> valueMap = values.getValue();
                    Reservation reservation = BeanUtil.fillBeanWithMap(valueMap, new Reservation(), true);

                    handleReservation(reservation);
                    stringRedisTemplate.opsForStream().acknowledge(streamQueueName, "g1", values.getId());
                } catch (Exception e) {
                    LOG.error("handle reservation stream failed", e);
                    handlePendingList();
                }
            }
        }
    }

    private void handlePendingList() {
        while (true) {
            try {
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(streamQueueName, ReadOffset.from("0"))
                );
                if (list == null || list.isEmpty()) {
                    break;
                }

                MapRecord<String, Object, Object> values = list.get(0);
                Map<Object, Object> valueMap = values.getValue();
                Reservation reservation = BeanUtil.fillBeanWithMap(valueMap, new Reservation(), true);

                handleReservation(reservation);
                stringRedisTemplate.opsForStream().acknowledge(streamQueueName, "g1", values.getId());
            } catch (Exception e) {
                LOG.error("handle reservation pending-list failed", e);
            }
        }
    }

    private void handleReservation(Reservation reservation) {
        Long userId = reservation.getUserId();
        RLock redisLock = redissonClient.getLock("lock:reservation:" + userId);
        boolean locked = redisLock.tryLock();
        if (!locked) {
            LOG.error("same user concurrent reservation rejected, userId={}", userId);
            return;
        }

        try {
            proxy.createReservation(reservation);
        } finally {
            redisLock.unlock();
        }
    }

    @Override
    public Result reserveResource(Long resourceId) {
        Long userId = UserHolder.getUser().getId();
        long reservationId = redisIdWorker.nextId("reservation");

        Long result = stringRedisTemplate.execute(
                RESERVATION_SCRIPT,
                Collections.emptyList(),
                resourceId.toString(),
                userId.toString(),
                String.valueOf(reservationId)
        );

        int r = result == null ? -1 : result.intValue();
        if (r == 1) {
            return Result.fail("当前时段算力额度已被抢空");
        }
        if (r == 2) {
            return Result.fail("同一用户在同时段内不允许重复预约");
        }
        if (r != 0) {
            return Result.fail("资源抢占失败，请稍后重试");
        }

        Reservation reservation = new Reservation();
        reservation.setId(reservationId);
        reservation.setUserId(userId);
        reservation.setResourceId(resourceId);
        reservationTasks.add(reservation);

        proxy = (IReservationService) AopContext.currentProxy();
        return Result.ok(reservationId);
    }

    @Override
    public Result queryAdminReservationPage(Long current, Long size) {
        long validCurrent = current == null || current < 1 ? 1L : current;
        long validSize = size == null || size < 1 ? 10L : size;
        long offset = (validCurrent - 1) * validSize;

        List<Reservation> records = baseMapper.selectAdminReservationPage(offset, validSize);
        long total = count();

        Map<String, Object> pageResult = new HashMap<>(4);
        pageResult.put("current", validCurrent);
        pageResult.put("size", validSize);
        pageResult.put("total", total);
        pageResult.put("records", records);
        return Result.ok(pageResult);
    }

    @Override
    @Transactional
    public void createReservation(Reservation reservation) {
        Long userId = reservation.getUserId();

        int count = query()
                .eq("user_id", userId)
                .eq("resource_id", reservation.getResourceId())
                .count();
        if (count > 0) {
            LOG.warn("same user duplicate reservation blocked, userId={}, resourceId={}", userId, reservation.getResourceId());
            return;
        }

        Resource resource = resourceService.getById(reservation.getResourceId());
        if (resource == null) {
            LOG.error("resource not found, resourceId={}", reservation.getResourceId());
            return;
        }

        Long allocatedQuota = calculateAllocatedQuota(resource);
        reservation.setAllocatedQuota(allocatedQuota);
        reservation.setStatus(RESERVATION_STATUS_PENDING_CONFIRM);

        boolean success = resourceQuotaService.update()
                .setSql("quota=quota-1")
                .eq("resource_id", reservation.getResourceId())
                .gt("quota", 0)
                .update();
        if (!success) {
            LOG.error("当前时段算力额度已被抢空, resourceId={}", reservation.getResourceId());
            return;
        }

        save(reservation);
        registerAfterCommitActions(reservation, resource.getLabId());
    }

    @Override
    @Transactional
    public void markTimeoutBreach(Long reservationId) {
        Reservation reservation = getById(reservationId);
        if (reservation == null) {
            LOG.warn("timeout reservation not found, reservationId={}", reservationId);
            return;
        }
        if (!Integer.valueOf(RESERVATION_STATUS_PENDING_CONFIRM).equals(reservation.getStatus())) {
            LOG.info("timeout reservation ignored, reservationId={}, status={}", reservationId, reservation.getStatus());
            return;
        }

        boolean canceled = update()
                .set("status", RESERVATION_STATUS_CANCELED)
                .eq("id", reservationId)
                .eq("status", RESERVATION_STATUS_PENDING_CONFIRM)
                .update();
        if (!canceled) {
            LOG.info("timeout reservation cancel skipped by concurrent update, reservationId={}", reservationId);
            return;
        }

        boolean restored = resourceQuotaService.update()
                .setSql("quota=quota+1")
                .eq("resource_id", reservation.getResourceId())
                .update();
        if (!restored) {
            LOG.warn("db quota restore failed, reservationId={}, resourceId={}", reservationId, reservation.getResourceId());
        }

        stringRedisTemplate.opsForValue().increment(RedisConstants.RESOURCE_QUOTA_KEY + reservation.getResourceId());

        Resource resource = resourceService.getById(reservation.getResourceId());
        Long labId = resource == null ? null : resource.getLabId();
        registerAfterCommit(() -> pushReservationStatusChange(labId, reservationId, "已取消"));
    }

    private Long calculateAllocatedQuota(Resource resource) {
        Integer strategyType = resolveAllocationStrategyType(resource);
        return resourceAllocationStrategyFactory.getStrategy(strategyType).calculateRequiredQuota(resource);
    }

    private void registerAfterCommitActions(final Reservation reservation, final Long labId) {
        registerAfterCommit(() -> {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.RESERVATION_EVENT_EXCHANGE,
                    RabbitMQConfig.RESERVATION_DELAY_ROUTING_KEY,
                    reservation
            );
            pushReservationStatusChange(labId, reservation.getId(), "待确认");
            dispatchService.dispatchOrder(reservation.getId(), labId);
        });
    }

    private void pushReservationStatusChange(Long labId, Long reservationId, String statusText) {
        if (labId == null) {
            return;
        }
        WebSocketServer.sendToShop(labId, "预约状态已变更：" + statusText + "，预约ID=" + reservationId);
    }

    private void registerAfterCommit(final Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
                @Override
                public void afterCommit() {
                    try {
                        action.run();
                    } catch (Exception e) {
                        LOG.error("after-commit action failed", e);
                    }
                }
            });
            return;
        }
        action.run();
    }

    private Integer resolveAllocationStrategyType(Resource resource) {
        Integer resourceMode = resource.getResourceMode();
        if (resourceMode == null) {
            return ResourceAllocationStrategyType.COMPUTE_POINT;
        }
        if (resourceMode == 0) {
            return ResourceAllocationStrategyType.COMPUTE_POINT;
        }
        return ResourceAllocationStrategyType.COMPUTE_POINT;
    }
}
