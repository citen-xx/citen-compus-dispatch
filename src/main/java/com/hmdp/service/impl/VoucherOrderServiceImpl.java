package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
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
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
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

   private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
   static {
       SECKILL_SCRIPT =new DefaultRedisScript<>();
       SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
       SECKILL_SCRIPT.setResultType(Long.class);
   }

    private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandle());
        // 初始化库存到Redis
        initStockToRedis();
    }
    
    private void initStockToRedis() {
        try {
            // 查询所有秒杀券
            List<SeckillVoucher> vouchers = seckillVoucherService.list();
            for (SeckillVoucher voucher : vouchers) {
                String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucher.getVoucherId();
                // 如果Redis中没有库存数据，则初始化
                if (stringRedisTemplate.opsForValue().get(stockKey) == null) {
                    stringRedisTemplate.opsForValue().set(stockKey, voucher.getStock().toString());
                    log.info("初始化库存到Redis: voucherId={}, stock={}", voucher.getVoucherId(), voucher.getStock());
                }
            }
        } catch (Exception e) {
            log.error("初始化库存失败", e);
        }
    }
    String queueName="stream.orders";
    private class VoucherOrderHandle implements Runnable{

        @Override
        public void run() {
            while (true){
                //获取队列中的订单信息
                try {
                   //获取消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断消息获取是否成功
                    if(list==null|| list.isEmpty()){
                        //如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    //解析消息中的订单消息
                    MapRecord<String, Object, Object> values = list.get(0);
                    Map<Object, Object> valuesValue = values.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(valuesValue, new VoucherOrder(), true);

                    //如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",values.getId());




                } catch (Exception e) {

                    log.error("处理订单异常",e);
                    handlePendingList();
                }
                //创建订单

            }
        }
    }

    private void handlePendingList() {
        while (true) {
            //获取队列中的订单信息
            try {
                //获取pendingList队列中的订单信息
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );
                //判断消息获取是否成功
                if (list == null || list.isEmpty()) {
                    //如果获取失败，说明没有异常消息，跳出循环
                    break;
                }
                //解析消息中的订单消息
                MapRecord<String, Object, Object> values = list.get(0);
                Map<Object, Object> valuesValue = values.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(valuesValue, new VoucherOrder(), true);

                //如果获取成功，可以下单
                handleVoucherOrder(voucherOrder);
                //ACK确认
                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", values.getId());


            } catch (Exception e) {

                log.error("处理订单异常", e);
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
    //    创建锁对象
  //   SimpleRedisLock redislock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
        //   voucherOrder

        Long userId = voucherOrder.getUserId();
        RLock redislock = redissonClient.getLock("locak:order" + userId);
    //获取锁
    boolean isLock= redislock.tryLock();

    if(!isLock){
       log.error("不允许重复下单");
       return;
    }

    try {
        // 5. Get proxy to ensure transaction works

         proxy.createVoucherOrder(voucherOrder);
    } finally {
        // 6. Release lock
        redislock.unlock();
    }
    }
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId=UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdWorker.nextId("order");
        //执行lua脚本

        Long result=stringRedisTemplate.execute(
                SECKILL_SCRIPT, Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );

        //判断结果是否为0
        int r = result.intValue();
        if(r!=0){
            /// /非0 没有购买资格
            return Result.fail(r==1?"库存不足":"无法下单");
        }


        //是0 有购买资格，把下单消息保存到阻塞队列里面34

        VoucherOrder voucherOrder=new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);
        //返回订单id

        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //获取用户
//        Long userId=UserHolder.getUser().getId();
//
//        //执行lua脚本
//
//        Long result=stringRedisTemplate.execute(
//                SECKILL_SCRIPT, Collections.emptyList(),
//                voucherId.toString(),
//                userId.toString()
//        );
//
//        //判断结果是否为0
//        int r = result.intValue();
//        if(r!=0){
//            /// /非0 没有购买资格
//            return Result.fail(r==1?"库存不足":"无法下单");
//        }
//        long orderId = redisIdWorker.nextId("order");
//
//        //是0 有购买资格，把下单消息保存到阻塞队列里面34
//
//        VoucherOrder voucherOrder=new VoucherOrder();
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        orderTasks.add(voucherOrder);
//        //返回订单id
//
//        //获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        return Result.ok(orderId);
//    }
    private IVoucherOrderService proxy;


    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();


        // 1. One user one order check
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("用户已经购买一次");
            return ;
        }

        // 2. CAS update stock
        boolean success = seckillVoucherService.update()
                        .setSql("stock=stock-1")
                        .eq("voucher_id", voucherOrder.getVoucherId())
                        .gt("stock", 0).update();

        if (!success) {
           log.error("库存不足");
           return;
        }
        // 3. Create order

        save(voucherOrder);

    }}

//
//@Override
//public Result seckillVoucher(Long voucherId) {
//    SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//    // 1. Check if voucher exists and is within valid time
//    if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//        return Result.fail("秒杀尚未开始！");
//    }
//    if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//        return Result.fail("秒杀已经结束！");
//    }
//    // 2. Check stock
//    if (voucher.getStock() < 1) {
//        return Result.fail("库存不足");
//    }
//
//    Long userId = UserHolder.getUser().getId();
//    // 3. Create lock object
////        RLock lock = redissonClient.getLock("lock:order:" + userId);
////
////        // 4. Try to get lock (non-blocking)
////        boolean isLock = lock.tryLock();
////
////        if (!isLock) {
////            // Failed to acquire lock
////            return Result.fail("不允许重复下单");
////        }
//    //创建锁对象
//    //  SimpleRedisLock redislock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
//    RLock redislock = redissonClient.getLock("locak:order" + userId);
//    //获取锁
//    boolean isLock= redislock.tryLock();
//
//    if(!isLock){
//        return Result.fail("不允许重复下单");
//    }
//
//    try {
//        // 5. Get proxy to ensure transaction works
//        IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//        return proxy.createVoucherOrder(voucherId);
//    } finally {
//        // 6. Release lock
//        redislock.unlock();
//    }
//}


