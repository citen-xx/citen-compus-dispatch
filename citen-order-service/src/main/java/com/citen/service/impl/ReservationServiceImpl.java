package com.citen.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.citen.common.ReservationStatus;
import com.citen.common.ReservationStatusEvent;
import com.citen.config.RabbitMQConfig;
import com.citen.dto.Result;
import com.citen.dto.ReservationRequest;
import com.citen.entity.Reservation;
import com.citen.entity.ReservationCompensation;
import com.citen.entity.Resource;
import com.citen.entity.ResourceQuota;
import com.citen.mapper.ReservationMapper;
import com.citen.mapper.ReservationCompensationMapper;
import com.citen.service.IReservationService;
import com.citen.service.IResourceQuotaService;
import com.citen.service.IResourceService;
import com.citen.service.ReservationStateTransitionService;
import com.citen.utils.RedisConstants;
import com.citen.utils.RedisIdWorker;
import com.citen.utils.UserHolder;
import com.citen.websocket.WebSocketServer;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

@Service
public class ReservationServiceImpl extends ServiceImpl<ReservationMapper, Reservation> implements IReservationService {

    private static final Logger LOG = LoggerFactory.getLogger(ReservationServiceImpl.class);
    private static final String STREAM_CONSUMER_GROUP = "g1";
    private static final String STREAM_CONSUMER_NAME = "c-" + UUID.randomUUID();
    private static final long PENDING_LIST_RETRY_DELAY_MILLIS = 500L;
    private static final int PENDING_LIST_MAX_RETRY_COUNT = 3;
    private static final String REDIS_RELEASE_COMPENSATION = "REDIS_SLOT_RELEASE";

    private static final DefaultRedisScript<Long> RESERVATION_SCRIPT;
    private static final DefaultRedisScript<Long> RELEASE_RESERVATION_SCRIPT;

    static {
        RESERVATION_SCRIPT = new DefaultRedisScript<>();
        RESERVATION_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        RESERVATION_SCRIPT.setResultType(Long.class);
        RELEASE_RESERVATION_SCRIPT = new DefaultRedisScript<>();
        RELEASE_RESERVATION_SCRIPT.setLocation(new ClassPathResource("release-reservation.lua"));
        RELEASE_RESERVATION_SCRIPT.setResultType(Long.class);
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
    private RabbitTemplate rabbitTemplate;

    @javax.annotation.Resource
    private ReservationStateTransitionService reservationStateTransitionService;

    @javax.annotation.Resource
    private ReservationCompensationMapper reservationCompensationMapper;

    @Value("${reservation.confirm-timeout-seconds:900}")
    private long confirmTimeoutSeconds;

    @Autowired
    @Lazy
    private IReservationService reservationServiceProxy;

    private final ExecutorService reservationExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "reservation-stream-consumer");
        thread.setDaemon(true);
        return thread;
    });
    private final String streamQueueName = RedisConstants.RESERVATION_STREAM_KEY;

    @PostConstruct
    private void init() {
        initQuotaToRedis();
        ensureStreamConsumerGroup();
        reservationExecutor.submit(new ReservationTaskHandler());
    }

    @PreDestroy
    private void destroy() {
        reservationExecutor.shutdownNow();
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

    private void ensureStreamConsumerGroup() {
        try {
            stringRedisTemplate.execute((RedisCallback<Object>) connection -> connection.execute(
                    "XGROUP",
                    "CREATE".getBytes(StandardCharsets.UTF_8),
                    streamQueueName.getBytes(StandardCharsets.UTF_8),
                    STREAM_CONSUMER_GROUP.getBytes(StandardCharsets.UTF_8),
                    "0".getBytes(StandardCharsets.UTF_8),
                    "MKSTREAM".getBytes(StandardCharsets.UTF_8)
            ));
            LOG.info("created reservation stream consumer group, stream={}, group={}",
                    streamQueueName, STREAM_CONSUMER_GROUP);
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                return;
            }
            throw e;
        }
    }

    private class ReservationTaskHandler implements Runnable {
        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    handlePendingList();
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
                    sleepQuietly(PENDING_LIST_RETRY_DELAY_MILLIS);
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(STREAM_CONSUMER_GROUP, STREAM_CONSUMER_NAME),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(streamQueueName, ReadOffset.from("0"))
                    );
                    if (list == null || list.isEmpty()) {
                        break;
                    }
                    processReservationRecord(list.get(0));
                } catch (RuntimeException e) {
                    LOG.error("handle reservation pending-list failed", e);
                    throw e;
                }
            }
        }
    }

    void processReservationRecord(MapRecord<String, Object, Object> record) {
        Reservation reservation;
        try {
            reservation = mapReservation(record.getValue());
        } catch (RuntimeException e) {
            long retryCount = incrementRetryCount(record);
            if (retryCount < PENDING_LIST_MAX_RETRY_COUNT) {
                throw e;
            }
            moveToFailedStream(record, e, retryCount);
            acknowledgeRecord(record);
            stringRedisTemplate.opsForHash().delete(
                    RedisConstants.RESERVATION_RETRY_KEY, record.getId().getValue());
            LOG.error("invalid reservation stream message moved to failed stream, recordId={}, retryCount={}",
                    record.getId().getValue(), retryCount, e);
            return;
        }
        try {
            handleReservation(reservation);
        } catch (ReservationLockException e) {
            LOG.info("reservation lock busy, keep message pending, reservationId={}", reservation.getId());
            throw e;
        } catch (ReservationAlreadyCompensatedException e) {
            moveToFailedStream(record, e, PENDING_LIST_MAX_RETRY_COUNT);
            acknowledgeRecord(record);
            stringRedisTemplate.opsForHash().delete(
                    RedisConstants.RESERVATION_RETRY_KEY, record.getId().getValue());
            return;
        } catch (RuntimeException e) {
            long retryCount = incrementRetryCount(record);
            if (retryCount < PENDING_LIST_MAX_RETRY_COUNT) {
                throw e;
            }
            if (!releaseRedisReservation(reservation, "stream-create-failed")) {
                LOG.error("reservation compensation failed, keep message pending, reservationId={}",
                        reservation.getId(), e);
                throw e;
            }
            moveToFailedStream(record, e, retryCount);
            acknowledgeRecord(record);
            stringRedisTemplate.opsForHash().delete(
                    RedisConstants.RESERVATION_RETRY_KEY, record.getId().getValue());
            LOG.error("reservation create failed after retries and was compensated, reservationId={}, retryCount={}",
                    reservation.getId(), retryCount, e);
            return;
        }

        // Persistence has succeeded (or the same reservation ID already exists). Failures below
        // must leave the record pending; compensating here would release a valid DB reservation.
        markReservationPersisted(reservation.getId());
        acknowledgeRecord(record);
        stringRedisTemplate.opsForHash().delete(
                RedisConstants.RESERVATION_RETRY_KEY, record.getId().getValue());
    }

    void handleReservation(Reservation reservation) {
        Long userId = reservation.getUserId();
        Object redisState = stringRedisTemplate.opsForHash().get(
                RedisConstants.RESERVATION_META_KEY + reservation.getId(), "state");
        if ("COMPENSATED".equals(String.valueOf(redisState))) {
            throw new ReservationAlreadyCompensatedException();
        }
        RLock redisLock = redissonClient.getLock("lock:reservation:resource:" + reservation.getResourceId());
        boolean locked = redisLock.tryLock();
        if (!locked) {
            LOG.warn("lock failed, reservationId={}, userId={}, resourceId={}, message remains pending",
                    reservation.getId(), userId, reservation.getResourceId());
            throw new ReservationLockException();
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

    private Reservation mapReservation(Map<Object, Object> valueMap) {
        Reservation reservation = new Reservation();
        reservation.setId(parseLong(valueMap.get("id")));
        reservation.setUserId(parseLong(valueMap.get("userId")));
        reservation.setResourceId(parseLong(valueMap.get("resourceId")));
        reservation.setReservationDate(LocalDate.parse(String.valueOf(valueMap.get("reservationDate"))));
        reservation.setStartTime(LocalTime.parse(String.valueOf(valueMap.get("startTime"))));
        reservation.setEndTime(LocalTime.parse(String.valueOf(valueMap.get("endTime"))));
        return reservation;
    }

    private Long parseLong(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("reservation stream field is missing");
        }
        return Long.valueOf(String.valueOf(value));
    }

    private void acknowledgeRecord(MapRecord<String, Object, Object> record) {
        stringRedisTemplate.opsForStream().acknowledge(
                streamQueueName, STREAM_CONSUMER_GROUP, record.getId());
    }

    private long incrementRetryCount(MapRecord<String, Object, Object> record) {
        Long count = stringRedisTemplate.opsForHash().increment(
                RedisConstants.RESERVATION_RETRY_KEY, record.getId().getValue(), 1L);
        stringRedisTemplate.expire(RedisConstants.RESERVATION_RETRY_KEY, 7, TimeUnit.DAYS);
        return count == null ? 1L : count;
    }

    private void moveToFailedStream(MapRecord<String, Object, Object> record,
                                    RuntimeException error, long retryCount) {
        Map<Object, Object> failedRecord = new HashMap<>(record.getValue());
        failedRecord.put("sourceRecordId", record.getId().getValue());
        failedRecord.put("retryCount", String.valueOf(retryCount));
        failedRecord.put("error", error.getClass().getSimpleName() + ":" + error.getMessage());
        failedRecord.put("failedAt", LocalDateTime.now().toString());
        stringRedisTemplate.opsForStream().add(
                RedisConstants.RESERVATION_FAILED_STREAM_KEY, failedRecord);
    }

    private void markReservationPersisted(Long reservationId) {
        String metaKey = RedisConstants.RESERVATION_META_KEY + reservationId;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(metaKey))) {
            stringRedisTemplate.opsForHash().put(metaKey, "state", "PERSISTED");
        }
    }

    boolean releaseRedisReservation(Reservation reservation, String reason) {
        try {
            String slotsKey = RedisConstants.RESOURCE_RESERVATION_SLOT_KEY
                    + reservation.getResourceId() + ":" + reservation.getReservationDate();
            String userSlotsKey = RedisConstants.USER_RESERVATION_SLOT_KEY
                    + reservation.getUserId() + ":" + reservation.getResourceId()
                    + ":" + reservation.getReservationDate();
            Long releaseResult = stringRedisTemplate.execute(
                    RELEASE_RESERVATION_SCRIPT,
                    java.util.Arrays.asList(
                            slotsKey,
                            userSlotsKey,
                            RedisConstants.RESERVATION_META_KEY + reservation.getId()
                    ),
                    reservation.getId().toString()
            );
            if (releaseResult != null && releaseResult == -2) {
                LOG.warn("{} redis metadata already missing, treat as idempotent release, reservationId={}",
                        reason, reservation.getId());
                return true;
            }
            if (releaseResult == null || releaseResult < 0) {
                LOG.error("{} redis release rejected, reservationId={}, result={}",
                        reason, reservation.getId(), releaseResult);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            LOG.error("{} redis release failed, reservationId={}", reason, reservation.getId(), e);
            return false;
        }
    }

    @Override
    public Result reserveResource(Long resourceId, ReservationRequest request) {
        Long userId = UserHolder.getUser().getId();
        String validationError = validateReservationRequest(resourceId, request);
        if (validationError != null) {
            return Result.fail(validationError);
        }

        long reservationId = redisIdWorker.nextId("reservation");
        LocalDate reservationDate = request.getReservationDate();
        int startMinute = request.getStartTime().getHour() * 60 + request.getStartTime().getMinute();
        int endMinute = request.getEndTime().getHour() * 60 + request.getEndTime().getMinute();
        long ttlSeconds = Math.max(86400L, ChronoUnit.SECONDS.between(
                LocalDateTime.now(), reservationDate.plusDays(2).atStartOfDay()));

        String quotaKey = RedisConstants.RESOURCE_QUOTA_KEY + resourceId;
        String slotsKey = RedisConstants.RESOURCE_RESERVATION_SLOT_KEY + resourceId + ":" + reservationDate;
        String userSlotsKey = RedisConstants.USER_RESERVATION_SLOT_KEY
                + userId + ":" + resourceId + ":" + reservationDate;
        String metaKey = RedisConstants.RESERVATION_META_KEY + reservationId;

        Long result = stringRedisTemplate.execute(
                RESERVATION_SCRIPT,
                java.util.Arrays.asList(quotaKey, slotsKey, userSlotsKey, metaKey, streamQueueName),
                userId.toString(),
                resourceId.toString(),
                String.valueOf(reservationId),
                reservationDate.toString(),
                String.valueOf(startMinute),
                String.valueOf(endMinute),
                String.valueOf(ttlSeconds),
                request.getStartTime().toString(),
                request.getEndTime().toString()
        );

        int code = result == null ? -1 : result.intValue();
        if (code == 1) {
            return Result.fail("当前时段资源额度已被抢空");
        }
        if (code == 2) {
            return Result.fail("该资源在所选时间段已被预约");
        }
        if (code == 3) {
            return Result.fail("请勿重复提交预约");
        }
        if (code == 4) {
            return Result.fail("预约时间段不合法");
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

        registerAfterCommit(() -> pushReservationStatusChange(
                reservation.getUserId(),
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

        createRedisReleaseCompensation(reservation);
        registerRedisReleaseAfterCommit(reservation, "cancel");

        registerAfterCommit(() -> pushReservationStatusChange(
                reservation.getUserId(),
                reservationId,
                ReservationStatus.CANCELLED.getDesc()
        ));
        return Result.ok();
    }

    @Override
    @Transactional
    public Result completeReservation(Long reservationId) {
        Long currentUserId = UserHolder.getUser().getId();
        Reservation reservation = getById(reservationId);
        if (reservation == null) {
            return Result.fail("预约不存在");
        }
        if (!currentUserId.equals(reservation.getUserId())) {
            return Result.fail("无权完成该预约");
        }
        if (!reservationStateTransitionService.canTransit(reservation.getStatus(), ReservationStatusEvent.COMPLETE)) {
            return Result.fail("当前预约状态不允许完成");
        }
        LocalDateTime reservationEnd = LocalDateTime.of(
                reservation.getReservationDate(), reservation.getEndTime());
        if (LocalDateTime.now().isBefore(reservationEnd)) {
            return Result.fail("预约结束后才能标记完成");
        }
        if (!reservationStateTransitionService.transitionReservationStatus(
                reservationId, currentUserId, ReservationStatusEvent.COMPLETE)) {
            return Result.fail("预约状态已变更，请刷新后重试");
        }
        createRedisReleaseCompensation(reservation);
        registerRedisReleaseAfterCommit(reservation, "complete");
        registerAfterCommit(() -> pushReservationStatusChange(
                reservation.getUserId(), reservationId, ReservationStatus.COMPLETED.getDesc()));
        return Result.ok();
    }

    @Override
    @Transactional
    public void createReservation(Reservation reservation) {
        Long reservationId = reservation.getId();
        Long userId = reservation.getUserId();
        Long resourceId = reservation.getResourceId();

        try {
            Reservation existing = getById(reservationId);
            if (existing != null) {
                LOG.info("reservation already persisted, reservationId={}, userId={}, resourceId={}, ack current stream message",
                        reservationId, userId, resourceId);
                return;
            }

            Long lockedResourceId = baseMapper.lockResourceForReservation(resourceId);
            if (lockedResourceId == null) {
                LOG.error("reservation create failed, resource not found, reservationId={}, userId={}, resourceId={}",
                        reservationId, userId, resourceId);
                throw new IllegalStateException("resource not found");
            }
            Resource resource = resourceService.getById(resourceId);
            if (resource == null || reservation.getReservationDate() == null
                    || reservation.getStartTime() == null || reservation.getEndTime() == null
                    || !reservation.getStartTime().isBefore(reservation.getEndTime())) {
                throw new IllegalStateException("invalid reservation stream payload");
            }

            ResourceQuota quota = resourceQuotaService.getById(resourceId);
            LocalDateTime startDateTime = LocalDateTime.of(reservation.getReservationDate(), reservation.getStartTime());
            LocalDateTime endDateTime = LocalDateTime.of(reservation.getReservationDate(), reservation.getEndTime());
            if (!startDateTime.isAfter(LocalDateTime.now())) {
                throw new IllegalStateException("reservation start time has passed before persistence");
            }
            if (quota == null || (quota.getBeginTime() != null && startDateTime.isBefore(quota.getBeginTime()))
                    || (quota.getEndTime() != null && endDateTime.isAfter(quota.getEndTime()))) {
                throw new IllegalStateException("reservation outside resource availability window");
            }

            List<Reservation> overlappingReservations = baseMapper.selectActiveOverlappingReservations(
                    resourceId, reservation.getReservationDate(), reservation.getStartTime(), reservation.getEndTime());
            boolean duplicatedByUser = hasDuplicateUserReservation(overlappingReservations, userId);
            if (duplicatedByUser) {
                throw new IllegalStateException("user already has an overlapping reservation for this resource");
            }
            if (hasCapacityConflict(overlappingReservations, quota.getQuota(),
                    reservation.getStartTime(), reservation.getEndTime())) {
                throw new IllegalStateException("reservation time capacity reached");
            }

            reservation.setStatus(ReservationStatus.PENDING.getCode());
            LocalDateTime confirmationDeadline = LocalDateTime.now().plusSeconds(confirmTimeoutSeconds);
            reservation.setExpireAt(confirmationDeadline.isBefore(startDateTime)
                    ? confirmationDeadline : startDateTime);
            reservation.setTimeoutMessageSent(false);

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
    public void expireReservation(Long reservationId) {
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

        if (reservation.getExpireAt() != null && reservation.getExpireAt().isAfter(LocalDateTime.now())) {
            return;
        }
        if (!reservationStateTransitionService.canTransit(reservation.getStatus(), ReservationStatusEvent.EXPIRE)) {
            LOG.info("timeout reservation ignored, reservationId={}, resourceId={}, userId={}, status={}",
                    reservationId, resourceId, userId, reservation.getStatus());
            return;
        }

        boolean statusUpdated = reservationStateTransitionService.transitionReservationStatus(
                reservationId,
                null,
                ReservationStatusEvent.EXPIRE
        );
        if (!statusUpdated) {
            LOG.info("timeout reservation status update skipped by concurrent update, reservationId={}, resourceId={}, userId={}",
                    reservationId, resourceId, userId);
            return;
        }

        createRedisReleaseCompensation(reservation);
        registerRedisReleaseAfterCommit(reservation, "expire");

        registerAfterCommit(() -> pushReservationStatusChange(
                reservation.getUserId(),
                reservationId,
                ReservationStatus.EXPIRED.getDesc()
        ));
    }

    private void registerAfterCommitActions(final Reservation reservation, final Long labId) {
        registerAfterCommit(() -> {
            sendTimeoutMessage(reservation);
            pushReservationStatusChange(reservation.getUserId(), reservation.getId(), ReservationStatus.PENDING.getDesc());
        });
    }

    private boolean sendTimeoutMessage(Reservation reservation) {
        long delayMillis = reservation.getExpireAt() == null
                ? TimeUnit.SECONDS.toMillis(confirmTimeoutSeconds)
                : Math.max(1000L, Duration.between(LocalDateTime.now(), reservation.getExpireAt()).toMillis());
        CorrelationData correlationData = new CorrelationData(reservation.getId().toString());
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.RESERVATION_EVENT_EXCHANGE,
                    RabbitMQConfig.RESERVATION_DELAY_ROUTING_KEY,
                    reservation.getId().toString(),
                    message -> {
                        message.getMessageProperties().setExpiration(String.valueOf(delayMillis));
                        return message;
                    },
                    correlationData
            );
            CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                LOG.error("timeout message was nacked, reservationId={}, reason={}",
                        reservation.getId(), confirm.getReason());
                return false;
            }
            if (correlationData.getReturned() != null) {
                LOG.error("timeout message was returned, reservationId={}, replyText={}",
                        reservation.getId(), correlationData.getReturned().getReplyText());
                return false;
            }
            boolean marked = update(new LambdaUpdateWrapper<Reservation>()
                    .eq(Reservation::getId, reservation.getId())
                    .eq(Reservation::getStatus, ReservationStatus.PENDING.getCode())
                    .set(Reservation::getTimeoutMessageSent, true));
            if (!marked) {
                LOG.info("timeout message confirmed after reservation status changed, reservationId={}",
                        reservation.getId());
            }
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.error("timeout message publish failed, reservationId={}", reservation.getId(), e);
            return false;
        }
    }

    @Scheduled(fixedDelayString = "${reservation.recovery-interval-ms:60000}")
    public void recoverTimeoutMessagesAndExpiredReservations() {
        LocalDateTime now = LocalDateTime.now();
        List<Reservation> expired = list(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getStatus, ReservationStatus.PENDING.getCode())
                .le(Reservation::getExpireAt, now)
                .last("LIMIT 100"));
        for (Reservation reservation : expired) {
            try {
                reservationServiceProxy.expireReservation(reservation.getId());
            } catch (RuntimeException e) {
                LOG.error("scheduled reservation expiration failed, reservationId={}", reservation.getId(), e);
            }
        }

        List<Reservation> unpublished = list(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getStatus, ReservationStatus.PENDING.getCode())
                .eq(Reservation::getTimeoutMessageSent, false)
                .gt(Reservation::getExpireAt, now)
                .last("LIMIT 100"));
        for (Reservation reservation : unpublished) {
            sendTimeoutMessage(reservation);
        }
    }

    private void pushReservationStatusChange(Long userId, Long reservationId, String statusText) {
        if (userId == null) {
            return;
        }
        WebSocketServer.sendToUser(userId, "预约状态已变更：" + statusText + "，预约ID=" + reservationId);
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

    private void createRedisReleaseCompensation(Reservation reservation) {
        ReservationCompensation task = new ReservationCompensation();
        task.setReservationId(reservation.getId());
        task.setUserId(reservation.getUserId());
        task.setResourceId(reservation.getResourceId());
        task.setReservationDate(reservation.getReservationDate());
        task.setStartTime(reservation.getStartTime());
        task.setEndTime(reservation.getEndTime());
        task.setCompensationType(REDIS_RELEASE_COMPENSATION);
        task.setStatus(0);
        task.setRetryCount(0);
        try {
            reservationCompensationMapper.insert(task);
        } catch (DuplicateKeyException e) {
            LOG.info("redis release compensation task already exists, reservationId={}", reservation.getId());
        }
    }

    private void registerRedisReleaseAfterCommit(Reservation reservation, String reason) {
        registerAfterCommit(() -> processRedisReleaseCompensation(reservation, reason));
    }

    private void processRedisReleaseCompensation(Reservation reservation, String reason) {
        if (!releaseRedisReservation(reservation, reason)) {
            updateCompensationFailure(reservation.getId(), "Redis release failed");
            return;
        }
        reservationCompensationMapper.update(null,
                new LambdaUpdateWrapper<ReservationCompensation>()
                        .eq(ReservationCompensation::getReservationId, reservation.getId())
                        .eq(ReservationCompensation::getCompensationType, REDIS_RELEASE_COMPENSATION)
                        .eq(ReservationCompensation::getStatus, 0)
                        .set(ReservationCompensation::getStatus, 1)
                        .set(ReservationCompensation::getLastError, null));
    }

    private void updateCompensationFailure(Long reservationId, String error) {
        reservationCompensationMapper.update(null,
                new LambdaUpdateWrapper<ReservationCompensation>()
                        .eq(ReservationCompensation::getReservationId, reservationId)
                        .eq(ReservationCompensation::getCompensationType, REDIS_RELEASE_COMPENSATION)
                        .eq(ReservationCompensation::getStatus, 0)
                        .setSql("retry_count=retry_count+1")
                        .set(ReservationCompensation::getLastError, error));
    }

    @Scheduled(fixedDelayString = "${reservation.compensation-interval-ms:30000}")
    public void retryRedisReleaseCompensations() {
        List<ReservationCompensation> tasks = reservationCompensationMapper.selectList(
                new LambdaQueryWrapper<ReservationCompensation>()
                        .eq(ReservationCompensation::getStatus, 0)
                        .last("LIMIT 100"));
        for (ReservationCompensation task : tasks) {
            Reservation reservation = new Reservation();
            reservation.setId(task.getReservationId());
            reservation.setUserId(task.getUserId());
            reservation.setResourceId(task.getResourceId());
            reservation.setReservationDate(task.getReservationDate());
            reservation.setStartTime(task.getStartTime());
            reservation.setEndTime(task.getEndTime());
            try {
                processRedisReleaseCompensation(reservation, "scheduled-retry");
            } catch (RuntimeException e) {
                updateCompensationFailure(task.getReservationId(), e.getMessage());
                LOG.error("redis release compensation retry failed, reservationId={}",
                        task.getReservationId(), e);
            }
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String validateReservationRequest(Long resourceId, ReservationRequest request) {
        if (resourceId == null || resourceId <= 0) {
            return "资源ID不合法";
        }
        if (request == null || request.getReservationDate() == null
                || request.getStartTime() == null || request.getEndTime() == null) {
            return "预约日期、开始时间和结束时间不能为空";
        }
        LocalDate today = LocalDate.now();
        if (request.getReservationDate().isBefore(today)) {
            return "不能预约过去的日期";
        }
        if (!request.getStartTime().isBefore(request.getEndTime())) {
            return "结束时间必须晚于开始时间";
        }
        if (request.getStartTime().getSecond() != 0 || request.getStartTime().getNano() != 0
                || request.getEndTime().getSecond() != 0 || request.getEndTime().getNano() != 0) {
            return "预约时间必须精确到分钟";
        }
        if (request.getReservationDate().equals(today)
                && !request.getStartTime().isAfter(LocalTime.now())) {
            return "不能预约已经开始的时间段";
        }
        if (request.getReservationDate().isAfter(today.plusDays(30))) {
            return "最多只能提前30天预约";
        }
        Resource resource = resourceService.getById(resourceId);
        if (resource == null || !Integer.valueOf(1).equals(resource.getStatus())) {
            return "资源不存在或已停用";
        }
        ResourceQuota quota = resourceQuotaService.getById(resourceId);
        if (quota == null || quota.getQuota() == null || quota.getQuota() <= 0) {
            return "当前资源没有可预约额度";
        }
        LocalDateTime startDateTime = LocalDateTime.of(request.getReservationDate(), request.getStartTime());
        LocalDateTime endDateTime = LocalDateTime.of(request.getReservationDate(), request.getEndTime());
        if (quota.getBeginTime() != null && startDateTime.isBefore(quota.getBeginTime())) {
            return "预约开始时间不在资源开放范围内";
        }
        if (quota.getEndTime() != null && endDateTime.isAfter(quota.getEndTime())) {
            return "预约结束时间不在资源开放范围内";
        }
        return null;
    }

    static boolean hasCapacityConflict(List<Reservation> existingReservations, Integer capacity,
                                       LocalTime requestedStart, LocalTime requestedEnd) {
        if (capacity == null || capacity <= 0) {
            return true;
        }
        int startMinute = requestedStart.getHour() * 60 + requestedStart.getMinute();
        int endMinute = requestedEnd.getHour() * 60 + requestedEnd.getMinute();
        for (int minute = startMinute; minute < endMinute; minute++) {
            int occupied = 0;
            for (Reservation existing : existingReservations) {
                int existingStart = existing.getStartTime().getHour() * 60 + existing.getStartTime().getMinute();
                int existingEnd = existing.getEndTime().getHour() * 60 + existing.getEndTime().getMinute();
                if (existingStart <= minute && existingEnd > minute) {
                    occupied++;
                }
            }
            if (occupied >= capacity) {
                return true;
            }
        }
        return false;
    }

    static boolean hasDuplicateUserReservation(List<Reservation> existingReservations, Long userId) {
        return userId != null && existingReservations.stream()
                .anyMatch(existingReservation -> userId.equals(existingReservation.getUserId()));
    }

    static final class ReservationLockException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    static final class ReservationAlreadyCompensatedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ReservationAlreadyCompensatedException() {
            super("reservation Redis pre-deduction was already compensated");
        }
    }

}
