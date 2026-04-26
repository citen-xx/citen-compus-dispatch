package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.strategy.PriceStrategyFactory;
import com.hmdp.strategy.PriceStrategyType;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
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

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

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

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

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

        // OCP: service only orchestrates the order flow.
        // Add a new voucher type by extending strategy/factory, not by rewriting this method.
        Long finalPayAmount = calculateFinalPayAmount(voucher);
        voucherOrder.setFinalPayAmount(finalPayAmount);

        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();

        if (!success) {
            log.error("stock not enough, voucherId={}", voucherOrder.getVoucherId());
            return;
        }

        log.info("price calculated, orderId={}, voucherId={}, voucherType={}, finalPayAmount={}",
                voucherOrder.getId(), voucher.getId(), voucher.getType(), finalPayAmount);
        save(voucherOrder);
    }

    private Long calculateFinalPayAmount(Voucher voucher) {
        Integer strategyType = resolvePriceStrategyType(voucher);
        return priceStrategyFactory.getStrategy(strategyType).calculatePrice(voucher);
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
