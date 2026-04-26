package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.RabbitMQConfig;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.DispatchService;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.strategy.PriceStrategyFactory;
import com.hmdp.strategy.PriceStrategyType;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.hmdp.websocket.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
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
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final int ORDER_STATUS_UNPAID = 1;
    private static final int ORDER_STATUS_CANCELED = 4;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private PriceStrategyFactory priceStrategyFactory;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private DispatchService dispatchService;

    private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
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
            List<SeckillVoucher> vouchers = seckillVoucherService.list();
            for (SeckillVoucher voucher : vouchers) {
                String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucher.getVoucherId();
                if (stringRedisTemplate.opsForValue().get(stockKey) == null) {
                    stringRedisTemplate.opsForValue().set(stockKey, voucher.getStock().toString());
                    log.info("init stock to redis, voucherId={}, stock={}", voucher.getVoucherId(), voucher.getStock());
                }
            }
        } catch (Exception e) {
            log.error("init stock to redis failed", e);
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
                    Map<Object, Object> valuesValue = values.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(valuesValue, new VoucherOrder(), true);

                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", values.getId());
                } catch (Exception e) {
                    log.error("handle stream order failed", e);
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
                Map<Object, Object> valuesValue = values.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(valuesValue, new VoucherOrder(), true);

                handleVoucherOrder(voucherOrder);
                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", values.getId());
            } catch (Exception e) {
                log.error("handle pending-list order failed", e);
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = redisLock.tryLock();
        if (!isLock) {
            log.error("duplicate order rejected, userId={}", userId);
            return;
        }

        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            redisLock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );

        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "无法下单");
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);

        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    @Override
    public Result queryAdminOrderPage(Long current, Long size) {
        long validCurrent = current == null || current < 1 ? 1L : current;
        long validSize = size == null || size < 1 ? 10L : size;
        long offset = (validCurrent - 1) * validSize;

        List<VoucherOrder> records = baseMapper.selectAdminPageByDeferredJoin(offset, validSize);
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
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("user already ordered, userId={}, voucherId={}", userId, voucherOrder.getVoucherId());
            return;
        }

        Voucher voucher = voucherService.getById(voucherOrder.getVoucherId());
        if (voucher == null) {
            log.error("voucher not found, voucherId={}", voucherOrder.getVoucherId());
            return;
        }

        Long finalPayAmount = calculateFinalPayAmount(voucher);
        voucherOrder.setFinalPayAmount(finalPayAmount);
        voucherOrder.setStatus(ORDER_STATUS_UNPAID);

        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("stock not enough, voucherId={}", voucherOrder.getVoucherId());
            return;
        }

        save(voucherOrder);
        registerAfterCommitActions(voucherOrder.getId(), voucher.getShopId());
    }

    @Override
    @Transactional
    public void cancelTimeoutOrder(Long orderId) {
        VoucherOrder voucherOrder = getById(orderId);
        if (voucherOrder == null) {
            log.warn("timeout order not found, orderId={}", orderId);
            return;
        }
        if (!Integer.valueOf(ORDER_STATUS_UNPAID).equals(voucherOrder.getStatus())) {
            log.info("timeout order ignored, orderId={}, status={}", orderId, voucherOrder.getStatus());
            return;
        }

        boolean canceled = update()
                .set("status", ORDER_STATUS_CANCELED)
                .eq("id", orderId)
                .eq("status", ORDER_STATUS_UNPAID)
                .update();
        if (!canceled) {
            log.info("timeout order cancel skipped by concurrent update, orderId={}", orderId);
            return;
        }

        boolean restored = seckillVoucherService.update()
                .setSql("stock=stock+1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .update();
        if (!restored) {
            log.warn("db stock restore failed, orderId={}, voucherId={}", orderId, voucherOrder.getVoucherId());
        }

        stringRedisTemplate.opsForValue().increment(RedisConstants.SECKILL_STOCK_KEY + voucherOrder.getVoucherId());

        Voucher voucher = voucherService.getById(voucherOrder.getVoucherId());
        Long shopId = voucher == null ? null : voucher.getShopId();
        registerAfterCommit(new Runnable() {
            @Override
            public void run() {
                pushOrderStatusChange(shopId, orderId, "已取消");
            }
        });
    }

    private Long calculateFinalPayAmount(Voucher voucher) {
        Integer strategyType = resolvePriceStrategyType(voucher);
        return priceStrategyFactory.getStrategy(strategyType).calculatePrice(voucher);
    }

    private void registerAfterCommitActions(final Long orderId, final Long shopId) {
        registerAfterCommit(new Runnable() {
            @Override
            public void run() {
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.ORDER_EVENT_EXCHANGE,
                        RabbitMQConfig.ORDER_DELAY_ROUTING_KEY,
                        String.valueOf(orderId)
                );
                pushOrderStatusChange(shopId, orderId, "待支付");
                dispatchService.dispatchOrder(orderId, shopId);
            }
        });
    }

    private void pushOrderStatusChange(Long shopId, Long orderId, String statusText) {
        if (shopId == null) {
            return;
        }
        WebSocketServer.sendToShop(shopId, "订单状态已变更：" + statusText + "，订单ID=" + orderId);
    }

    // 所有外部副作用都在事务提交后执行，避免订单回滚后仍然通知前端或触发派单。
    private void registerAfterCommit(final Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
                @Override
                public void afterCommit() {
                    try {
                        action.run();
                    } catch (Exception e) {
                        log.error("after-commit action failed", e);
                    }
                }
            });
            return;
        }

        action.run();
    }

    private Integer resolvePriceStrategyType(Voucher voucher) {
        Integer voucherType = voucher.getType();
        if (voucherType == null) {
            return PriceStrategyType.NORMAL;
        }
        if (voucherType == 0) {
            return PriceStrategyType.NORMAL;
        }
        if (voucherType == 1) {
            return PriceStrategyType.DISCOUNT;
        }
        if (voucherType == 2) {
            return PriceStrategyType.FULL_REDUCTION;
        }
        return PriceStrategyType.NORMAL;
    }
}
