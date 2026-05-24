package com.citen.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citen.common.ReservationStatus;
import com.citen.common.ReservationStatusEvent;
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
import com.citen.service.ReservationStateTransitionService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ReservationServiceImpl extends ServiceImpl<ReservationMapper, Reservation> implements IReservationService {

    private static final Logger LOG = LoggerFactory.getLogger(ReservationServiceImpl.class);
    private static final String STREAM_CONSUMER_GROUP = "g1";
    private static final String STREAM_CONSUMER_NAME = "c1";
    private static final long PENDING_LIST_RETRY_DELAY_MILLIS = 200L;

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

    @javax.annotation.Resource
    private ReservationStateTransitionService reservationStateTransitionService;

    @Autowired
    @Lazy
    private IReservationService reservationServiceProxy;

    private static final ExecutorService RESERVATION_EXECUTOR = Executors.newSingleThreadExecutor();
    private final String streamQueueName = RedisConstants.RESERVATION_STREAM_KEY;

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
                            Consumer.from(STREAM_CONSUMER_GROUP, STREAM_CONSUMER_NAME),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(streamQueueName, ReadOffset.lastConsumed())
                    );
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    processReservationRecord(list.get(0));
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
                        Consumer.from(STREAM_CONSUMER_GROUP, STREAM_CONSUMER_NAME),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(streamQueueName, ReadOffset.from("0"))
                );
                if (list == null || list.isEmpty()) {
                    break;
                }
                processReservationRecord(list.get(0));
            } catch (Exception e) {
                LOG.error("handle reservation pending-list failed", e);
                sleepQuietly(PENDING_LIST_RETRY_DELAY_MILLIS);
            }
        }
    }

    private void processReservationRecord(MapRecord<String, Object, Object> record) {
        Map<Object, Object> valueMap = record.getValue();
        Reservation reservation = BeanUtil.fillBeanWithMap(valueMap, new Reservation(), true);
        handleReservation(reservation);
        stringRedisTemplate.opsForStream().acknowledge(streamQueueName, STREAM_CONSUMER_GROUP, record.getId());
    }

    private void handleReservation(Reservation reservation) {
        Long userId = reservation.getUserId();
        RLock redisLock = redissonClient.getLock("lock:reservation:" + userId);
        boolean locked = redisLock.tryLock();
        if (!locked) {
            LOG.warn("lock failed, reservationId={}, userId={}, resourceId={}, message not acked, will retry from pending list",
                    reservation.getId(), userId, reservation.getResourceId());
            throw new IllegalStateException("reservation lock failed");
        }

        try {
            reservationServiceProxy.createReservation(reservation);
        } catch (RuntimeException e) {
            LOG.error("reservation stream handling failed, reservationId={}, userId={}, resourceId={}",
                    reservation.getId(), userId, reservation.getResourceId(), e);
            throw e;
        } finally {
            if (redisLock.isHeldByCurrentThread()) {
                redisLock.unlock();
            }
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

        int code = result == null ? -1 : result.intValue();
        if (code == 1) {
            return Result.fail("当前时段资源额度已被抢空");
        }
        if (code == 2) {
            return Result.fail("同一用户不允许重复预约");
        }
        if (code != 0) {
            return Result.fail("资源预约失败，请稍后重试");
        }

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
    public Result confirmReservation(Long reservationId) {
        Long currentUserId = UserHolder.getUser().getId();
        Reservation reservation = getById(reservationId);
        if (reservation == null) {
            return Result.fail("预约不存在");
        }
        if (!currentUserId.equals(reservation.getUserId())) {
            return Result.fail("无权确认该预约");
        }
        if (!reservationStateTransitionService.canTransit(reservation.getStatus(), ReservationStatusEvent.CONFIRM)) {
            return Result.fail("当前预约状态不允许确认");
        }

        boolean statusUpdated = reservationStateTransitionService.transitionReservationStatus(
                reservationId,
                currentUserId,
                ReservationStatusEvent.CONFIRM
        );
        if (!statusUpdated) {
            LOG.info("reservation confirm skipped by concurrent update, reservationId={}, userId={}",
                    reservationId, currentUserId);
            return Result.fail("预约状态已变更，请刷新后重试");
        }

        Resource resource = resourceService.getById(reservation.getResourceId());
        Long labId = resource == null ? null : resource.getLabId();
        registerAfterCommit(() -> pushReservationStatusChange(
                labId,
                reservationId,
                ReservationStatus.CONFIRMED.getDesc()
        ));
        return Result.ok();
    }

    @Override
    @Transactional
    public Result cancelReservation(Long reservationId) {
        Long currentUserId = UserHolder.getUser().getId();
        Reservation reservation = getById(reservationId);
        if (reservation == null) {
            return Result.fail("预约不存在");
        }
        if (!currentUserId.equals(reservation.getUserId())) {
            return Result.fail("无权取消该预约");
        }
        if (!reservationStateTransitionService.canTransit(reservation.getStatus(), ReservationStatusEvent.CANCEL)) {
            return Result.fail("当前预约状态不允许取消");
        }

        boolean statusUpdated = reservationStateTransitionService.transitionReservationStatus(
                reservationId,
                currentUserId,
                ReservationStatusEvent.CANCEL
        );
        if (!statusUpdated) {
            LOG.info("reservation cancel skipped by concurrent update, reservationId={}, userId={}",
                    reservationId, currentUserId);
            return Result.fail("预约状态已变更，请刷新后重试");
        }

        ReservationRollbackResult rollbackResult = rollbackReservationResource(reservation, "cancel");
        LOG.info("cancel reservation compensated, reservationId={}, resourceId={}, userId={}, statusUpdated={}, dbQuotaRestored={}, redisQuotaRestored={}, redisSetRemoved={}, removedCount={}",
                reservation.getId(), reservation.getResourceId(), currentUserId, statusUpdated,
                rollbackResult.dbQuotaRestored, rollbackResult.redisQuotaRestored,
                rollbackResult.redisSetRemoved, rollbackResult.removedCount);
        if (!rollbackResult.dbQuotaRestored || !rollbackResult.redisQuotaRestored || !rollbackResult.redisSetRemoved) {
            LOG.warn("cancel compensation completed with partial rollback, reservationId={}, resourceId={}, userId={}, dbQuotaRestored={}, redisQuotaRestored={}, redisSetRemoved={}",
                    reservation.getId(), reservation.getResourceId(), currentUserId,
                    rollbackResult.dbQuotaRestored, rollbackResult.redisQuotaRestored, rollbackResult.redisSetRemoved);
        }

        Resource resource = resourceService.getById(reservation.getResourceId());
        Long labId = resource == null ? null : resource.getLabId();
        registerAfterCommit(() -> pushReservationStatusChange(
                labId,
                reservationId,
                ReservationStatus.CANCELED.getDesc()
        ));
        return Result.ok();
    }

    @Override
    @Transactional
    public void createReservation(Reservation reservation) {
        Long reservationId = reservation.getId();
        Long userId = reservation.getUserId();
        Long resourceId = reservation.getResourceId();

        try {
            int count = query()
                    .eq("user_id", userId)
                    .eq("resource_id", resourceId)
                    .count();
            if (count > 0) {
                LOG.info("reservation already persisted, reservationId={}, userId={}, resourceId={}, ack current stream message",
                        reservationId, userId, resourceId);
                return;
            }

            Resource resource = resourceService.getById(resourceId);
            if (resource == null) {
                LOG.error("reservation create failed, resource not found, reservationId={}, userId={}, resourceId={}",
                        reservationId, userId, resourceId);
                throw new IllegalStateException("resource not found");
            }

            Long allocatedQuota = calculateAllocatedQuota(resource);
            reservation.setAllocatedQuota(allocatedQuota);
            reservation.setStatus(ReservationStatus.PENDING_CONFIRM.getCode());

            boolean quotaDeducted = resourceQuotaService.update()
                    .setSql("quota=quota-1")
                    .eq("resource_id", resourceId)
                    .gt("quota", 0)
                    .update();
            if (!quotaDeducted) {
                LOG.error("reservation create failed, db quota deduct rejected, reservationId={}, userId={}, resourceId={}",
                        reservationId, userId, resourceId);
                throw new IllegalStateException("db quota deduct failed");
            }

            boolean saved = save(reservation);
            if (!saved) {
                LOG.error("reservation create failed, save returned false, reservationId={}, userId={}, resourceId={}",
                        reservationId, userId, resourceId);
                throw new IllegalStateException("reservation save failed");
            }

            registerAfterCommitActions(reservation, resource.getLabId());
        } catch (RuntimeException e) {
            LOG.error("reservation create exception, reservationId={}, userId={}, resourceId={}",
                    reservationId, userId, resourceId, e);
            throw e;
        }
    }

    @Override
    @Transactional
    public void markTimeoutBreach(Long reservationId) {
        Reservation reservation = getById(reservationId);
        if (reservation == null) {
            LOG.warn("timeout reservation not found, reservationId={}", reservationId);
            return;
        }

        Long resourceId = reservation.getResourceId();
        Long userId = reservation.getUserId();
        if (resourceId == null || userId == null) {
            LOG.error("timeout reservation missing key fields, reservationId={}, resourceId={}, userId={}",
                    reservationId, resourceId, userId);
            return;
        }

        if (!reservationStateTransitionService.canTransit(reservation.getStatus(), ReservationStatusEvent.TIMEOUT)) {
            LOG.info("timeout reservation ignored, reservationId={}, resourceId={}, userId={}, status={}",
                    reservationId, resourceId, userId, reservation.getStatus());
            return;
        }

        boolean statusUpdated = reservationStateTransitionService.transitionReservationStatus(
                reservationId,
                null,
                ReservationStatusEvent.TIMEOUT
        );
        if (!statusUpdated) {
            LOG.info("timeout reservation status update skipped by concurrent update, reservationId={}, resourceId={}, userId={}",
                    reservationId, resourceId, userId);
            return;
        }

        ReservationRollbackResult rollbackResult = rollbackReservationResource(reservation, "timeout");
        LOG.info("timeout reservation compensated, reservationId={}, resourceId={}, userId={}, statusUpdated={}, dbQuotaRestored={}, redisQuotaRestored={}, redisSetRemoved={}, removedCount={}",
                reservationId, resourceId, userId, statusUpdated,
                rollbackResult.dbQuotaRestored, rollbackResult.redisQuotaRestored,
                rollbackResult.redisSetRemoved, rollbackResult.removedCount);
        if (!rollbackResult.dbQuotaRestored || !rollbackResult.redisQuotaRestored || !rollbackResult.redisSetRemoved) {
            LOG.warn("timeout compensation completed with partial rollback, reservationId={}, resourceId={}, userId={}, dbQuotaRestored={}, redisQuotaRestored={}, redisSetRemoved={}",
                    reservationId, resourceId, userId,
                    rollbackResult.dbQuotaRestored, rollbackResult.redisQuotaRestored, rollbackResult.redisSetRemoved);
        }

        Resource resource = resourceService.getById(resourceId);
        Long labId = resource == null ? null : resource.getLabId();
        registerAfterCommit(() -> pushReservationStatusChange(
                labId,
                reservationId,
                ReservationStatus.TIMEOUT_BREACH.getDesc()
        ));
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
            pushReservationStatusChange(labId, reservation.getId(), ReservationStatus.PENDING_CONFIRM.getDesc());
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

    private ReservationRollbackResult rollbackReservationResource(Reservation reservation, String reason) {
        Long reservationId = reservation.getId();
        Long resourceId = reservation.getResourceId();
        Long userId = reservation.getUserId();

        boolean dbQuotaRestored = resourceQuotaService.update()
                .setSql("quota=quota+1")
                .eq("resource_id", resourceId)
                .update();
        if (!dbQuotaRestored) {
            LOG.error("{} db quota rollback failed, reservationId={}, resourceId={}, userId={}",
                    reason, reservationId, resourceId, userId);
        }

        boolean redisQuotaRestored = false;
        try {
            Long redisQuotaValue = stringRedisTemplate.opsForValue().increment(RedisConstants.RESOURCE_QUOTA_KEY + resourceId);
            redisQuotaRestored = redisQuotaValue != null;
        } catch (RuntimeException e) {
            LOG.error("{} redis quota rollback failed, reservationId={}, resourceId={}, userId={}",
                    reason, reservationId, resourceId, userId, e);
        }

        long removedCount = 0L;
        boolean redisSetRemoved = false;
        try {
            Long removed = stringRedisTemplate.opsForSet().remove(
                    RedisConstants.RESOURCE_RESERVATION_KEY + resourceId,
                    userId.toString()
            );
            removedCount = removed == null ? 0L : removed;
            redisSetRemoved = removedCount > 0;
        } catch (RuntimeException e) {
            LOG.error("{} redis reservation set rollback failed, reservationId={}, resourceId={}, userId={}",
                    reason, reservationId, resourceId, userId, e);
        }

        return new ReservationRollbackResult(dbQuotaRestored, redisQuotaRestored, redisSetRemoved, removedCount);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    private static final class ReservationRollbackResult {
        private final boolean dbQuotaRestored;
        private final boolean redisQuotaRestored;
        private final boolean redisSetRemoved;
        private final long removedCount;

        private ReservationRollbackResult(boolean dbQuotaRestored, boolean redisQuotaRestored,
                                          boolean redisSetRemoved, long removedCount) {
            this.dbQuotaRestored = dbQuotaRestored;
            this.redisQuotaRestored = redisQuotaRestored;
            this.redisSetRemoved = redisSetRemoved;
            this.removedCount = removedCount;
        }
    }
}
