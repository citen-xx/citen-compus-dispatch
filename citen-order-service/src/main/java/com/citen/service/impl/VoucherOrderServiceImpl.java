package com.citen.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citen.config.RabbitMQConfig;
import com.citen.dto.Result;
import com.citen.entity.Reservation;
import com.citen.entity.Resource;
import com.citen.entity.ResourceQuota;
import com.citen.mapper.VoucherOrderMapper;
import com.citen.service.DispatchService;
import com.citen.service.ISeckillVoucherService;
import com.citen.service.IVoucherOrderService;
import com.citen.service.IVoucherService;
import com.citen.strategy.PriceStrategyFactory;
import com.citen.strategy.PriceStrategyType;
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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, Reservation> implements IVoucherOrderService {

    private static final Logger LOG = LoggerFactory.getLogger(VoucherOrderServiceImpl.class);

    private static final int ORDER_STATUS_UNPAID = 1;
    private static final int ORDER_STATUS_CANCELED = 4;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @javax.annotation.Resource
    private ISeckillVoucherService seckillVoucherService;

    @javax.annotation.Resource
    private IVoucherService voucherService;

    @javax.annotation.Resource
    private RedisIdWorker redisIdWorker;

    @javax.annotation.Resource
    private RedissonClient redissonClient;

    @javax.annotation.Resource
    private StringRedisTemplate stringRedisTemplate;

    @javax.annotation.Resource
    private PriceStrategyFactory priceStrategyFactory;

    @javax.annotation.Resource
    private RabbitTemplate rabbitTemplate;

    @javax.annotation.Resource
    private DispatchService dispatchService;

    private final BlockingQueue<Reservation> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private final String queueName = "stream.orders";

    private IVoucherOrderService proxy;

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandle());
        initStockToRedis();
    }

    private void initStockToRedis() {
        try {
            List<ResourceQuota> quotas = seckillVoucherService.list();
            for (ResourceQuota quota : quotas) {
                String quotaKey = RedisConstants.SECKILL_STOCK_KEY + quota.getResourceId();
                if (stringRedisTemplate.opsForValue().get(quotaKey) == null) {
                    stringRedisTemplate.opsForValue().set(quotaKey, quota.getQuota().toString());
                    LOG.info("init quota to redis, resourceId={}, quota={}", quota.getResourceId(), quota.getQuota());
                }
            }
        } catch (Exception e) {
            LOG.error("init quota to redis failed", e);
        }
    }

    private class VoucherOrderHandle implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    if (list == null || list.isEmpty()) {
                        continue;
                    }

                    MapRecord<String, Object, Object> values = list.get(0);
                    Map<Object, Object> valueMap = values.getValue();
                    Reservation reservation = BeanUtil.fillBeanWithMap(valueMap, new Reservation(), true);

                    handleVoucherOrder(reservation);
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", values.getId());
                } catch (Exception e) {
                    LOG.error("handle stream order failed", e);
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
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );
                if (list == null || list.isEmpty()) {
                    break;
                }

                MapRecord<String, Object, Object> values = list.get(0);
                Map<Object, Object> valueMap = values.getValue();
                Reservation reservation = BeanUtil.fillBeanWithMap(valueMap, new Reservation(), true);

                handleVoucherOrder(reservation);
                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", values.getId());
            } catch (Exception e) {
                LOG.error("handle pending-list order failed", e);
            }
        }
    }

    private void handleVoucherOrder(Reservation reservation) {
        Long userId = reservation.getUserId();
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = redisLock.tryLock();
        if (!isLock) {
            LOG.error("duplicate reservation rejected, userId={}", userId);
            return;
        }

        try {
            proxy.createVoucherOrder(reservation);
        } finally {
            redisLock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long resourceId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("reservation");

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                resourceId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );

        int r = result == null ? -1 : result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "资源额度已满" : "资源超额分配");
        }

        Reservation reservation = new Reservation();
        reservation.setId(orderId);
        reservation.setUserId(userId);
        reservation.setResourceId(resourceId);
        orderTasks.add(reservation);

        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    @Override
    public Result queryAdminOrderPage(Long current, Long size) {
        long validCurrent = current == null || current < 1 ? 1L : current;
        long validSize = size == null || size < 1 ? 10L : size;
        long offset = (validCurrent - 1) * validSize;

        List<Reservation> records = baseMapper.selectAdminPageByDeferredJoin(offset, validSize);
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
    public void createVoucherOrder(Reservation reservation) {
        Long userId = reservation.getUserId();

        int count = query()
                .eq("user_id", userId)
                .eq("resource_id", reservation.getResourceId())
                .count();
        if (count > 0) {
            LOG.error("user already reserved, userId={}, resourceId={}", userId, reservation.getResourceId());
            return;
        }

        Resource resource = voucherService.getById(reservation.getResourceId());
        if (resource == null) {
            LOG.error("resource not found, resourceId={}", reservation.getResourceId());
            return;
        }

        Long allocatedQuota = calculateAllocatedQuota(resource);
        reservation.setAllocatedQuota(allocatedQuota);
        reservation.setStatus(ORDER_STATUS_UNPAID);

        boolean success = seckillVoucherService.update()
                .setSql("quota=quota-1")
                .eq("resource_id", reservation.getResourceId())
                .gt("quota", 0)
                .update();
        if (!success) {
            LOG.error("resource quota exhausted, resourceId={}", reservation.getResourceId());
            return;
        }

        save(reservation);
        registerAfterCommitActions(reservation.getId(), resource.getLabId());
    }

    @Override
    @Transactional
    public void cancelTimeoutOrder(Long orderId) {
        Reservation reservation = getById(orderId);
        if (reservation == null) {
            LOG.warn("timeout reservation not found, orderId={}", orderId);
            return;
        }
        if (!Integer.valueOf(ORDER_STATUS_UNPAID).equals(reservation.getStatus())) {
            LOG.info("timeout reservation ignored, orderId={}, status={}", orderId, reservation.getStatus());
            return;
        }

        boolean canceled = update()
                .set("status", ORDER_STATUS_CANCELED)
                .eq("id", orderId)
                .eq("status", ORDER_STATUS_UNPAID)
                .update();
        if (!canceled) {
            LOG.info("timeout reservation cancel skipped by concurrent update, orderId={}", orderId);
            return;
        }

        boolean restored = seckillVoucherService.update()
                .setSql("quota=quota+1")
                .eq("resource_id", reservation.getResourceId())
                .update();
        if (!restored) {
            LOG.warn("db quota restore failed, orderId={}, resourceId={}", orderId, reservation.getResourceId());
        }

        stringRedisTemplate.opsForValue().increment(RedisConstants.SECKILL_STOCK_KEY + reservation.getResourceId());

        Resource resource = voucherService.getById(reservation.getResourceId());
        Long labId = resource == null ? null : resource.getLabId();
        registerAfterCommit(() -> pushReservationStatusChange(labId, orderId, "已取消"));
    }

    private Long calculateAllocatedQuota(Resource resource) {
        Integer strategyType = resolvePriceStrategyType(resource);
        return priceStrategyFactory.getStrategy(strategyType).calculatePrice(resource);
    }

    private void registerAfterCommitActions(final Long orderId, final Long labId) {
        registerAfterCommit(() -> {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.ORDER_EVENT_EXCHANGE,
                    RabbitMQConfig.ORDER_DELAY_ROUTING_KEY,
                    String.valueOf(orderId)
            );
            pushReservationStatusChange(labId, orderId, "待确认");
            dispatchService.dispatchOrder(orderId, labId);
        });
    }

    private void pushReservationStatusChange(Long labId, Long orderId, String statusText) {
        if (labId == null) {
            return;
        }
        WebSocketServer.sendToShop(labId, "预约状态已变更：" + statusText + "，预约ID=" + orderId);
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

    private Integer resolvePriceStrategyType(Resource resource) {
        Integer resourceMode = resource.getResourceMode();
        if (resourceMode == null) {
            return PriceStrategyType.NORMAL;
        }
        if (resourceMode == 0) {
            return PriceStrategyType.NORMAL;
        }
        if (resourceMode == 1) {
            return PriceStrategyType.DISCOUNT;
        }
        if (resourceMode == 2) {
            return PriceStrategyType.FULL_REDUCTION;
        }
        return PriceStrategyType.NORMAL;
    }
}
