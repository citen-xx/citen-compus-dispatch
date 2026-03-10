package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

   @Resource
   private ISeckillVoucherService seckillVoucherService;

   @Resource
   private RedisIdWorker redisIdWorker;

   @Resource
   private RedissonClient redissonClient;

   @Resource
   private StringRedisTemplate stringRedisTemplate;




    @Override
    public Result seckillVoucher(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 1. Check if voucher exists and is within valid time
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }
        // 2. Check stock
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        // 3. Create lock object
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//
//        // 4. Try to get lock (non-blocking)
//        boolean isLock = lock.tryLock();
//
//        if (!isLock) {
//            // Failed to acquire lock
//            return Result.fail("不允许重复下单");
//        }
        //创建锁对象
      //  SimpleRedisLock redislock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
        RLock redislock = redissonClient.getLock("locak:order" + userId);
        //获取锁
        boolean isLock= redislock.tryLock();

        if(!isLock){
            return Result.fail("不允许重复下单");
        }

        try {
            // 5. Get proxy to ensure transaction works
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 6. Release lock
            redislock.unlock();
        }
    }

    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();


        // 1. One user one order check
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("用户已经购买过一次！");
        }

        // 2. CAS update stock
        boolean success = seckillVoucherService.update(
                new UpdateWrapper<SeckillVoucher>()
                        .setSql("stock=stock-1")
                        .eq("voucher_id", voucherId)
                        .gt("stock", 0)
        );
        if (!success) {
            return Result.fail("库存不足");
        }


        // 3. Create order
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(orderId);
    }}

