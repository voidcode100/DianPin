package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    /**
     * 秒杀优惠券
     * @param voucherId 优惠券ID
     * @return 结果
     */
    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisWorker redisWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //private BlockingQueue<VoucherOrder> orderTask = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private IVoucherOrderService proxy;
    private static final String queenName = "stream.orders";
    @Autowired
    private VoucherOrderMapper voucherOrderMapper;

    @PostConstruct
    private void init(){
        //启动订单处理线程
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //基于redis stream实现异步下单的秒杀
    @Override
    public Result setKillVoucher(Long voucherId) {
        //获取用户ID
        Long userId = UserHolder.getUser().getId();
        //生成订单ID
        Long orderId = redisWorker.nextId("order");

        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                orderId.toString()

        );
        //判断是否为0
        if(result != 0L){
            return Result.ok(result==1?"库存不足":"用户已下单");
        }

        return Result.ok(orderId);

    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while(true){
                try {
                    //从redis stream中获取订单
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2L)),
                            StreamOffset.create(queenName, ReadOffset.lastConsumed())
                    );
                    //如果没有消息
                    if(list==null || list.isEmpty()){
                        //继续循环
                        continue;
                    }

                    //获取消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> voucherOrderMap = record.getValue();
                    //将消息转换为VoucherOrder对象
                    VoucherOrder voucherOrder = new VoucherOrder();
                    BeanUtil.fillBeanWithMap(voucherOrderMap,voucherOrder, true);

                    handleVoucherOrder(voucherOrder);
                    //ack消息
                    stringRedisTemplate.opsForStream().acknowledge(
                            queenName,
                            "g1",
                            record.getId()
                    );
                } catch (Exception e) {
                    log.error("订单线程异常",e);
                    //异常查看pendinglist中的消息
                    hadlePendingList();
                }
            }

        }
    }

    public void hadlePendingList(){
        while(true){
            try{
                //从redis stream中获取订单
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queenName, ReadOffset.from("0"))
                );
                //如果没有消息
                if(list==null || list.isEmpty()){
                    //pendinglist中无异常消息，结束循环
                    break;
                }

                //获取消息
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> voucherOrderMap = record.getValue();
                //将消息转换为VoucherOrder对象
                VoucherOrder voucherOrder = new VoucherOrder();
                BeanUtil.fillBeanWithMap(voucherOrderMap,voucherOrder, true);

                handleVoucherOrder(voucherOrder);
                //ack消息
                stringRedisTemplate.opsForStream().acknowledge(
                        queenName,
                        "g1",
                        record.getId()
                );
            }catch (Exception e){
                log.error("订单处理异常",e);
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }

        }
    }


//    private class VoucherOrderHandler implements Runnable {
//        @Override
//        public void run() {
//            while(true){
//                try {
//                    VoucherOrder voucherOrder = orderTask.take();
//                    handleVoucherOrder(voucherOrder);
//                } catch (InterruptedException e) {
//                    log.error("订单线程异常",e);
//                }
//            }
//
//        }
//    }

    private void handleVoucherOrder(VoucherOrder voucherOrder){
        //获取锁对象
        //SimpleRedisLock simpleRedisLock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        Long userId = voucherOrder.getUserId();
        RLock rlock = redissonClient.getLock("order:" + userId);
        //获取锁
        //boolean isLocked = simpleRedisLock.tryLock(5L);
        boolean isLocked = rlock.tryLock();
        //如果获取锁失败
        if(!isLocked){
            log.error("一人只能下一单，用户ID: {}", userId);
            return;
        }
        //获得代理对象（事务）
        try{
            proxy.createVoucherOrder(voucherOrder);
        }finally{
            //释放锁
            //simpleRedisLock.unlock();
            rlock.unlock();
        }
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        //判断用户是否已经购买过
        //用户信息
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count > 0){
            log.error("该用户已购买过该优惠券，用户ID: {}, 优惠券ID: {}", userId, voucherId);
            return;
        }
        //更新库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if(!success){
            log.error("库存不足，用户ID: {}, 优惠券ID: {}", userId, voucherId);
            return;
        }
        save(voucherOrder);
    }

    //使用java阻塞队列实现异步下单的秒杀
//    @Override
//    public Result setKillVoucher(Long voucherId) {
//        //获取用户ID
//        Long userId = UserHolder.getUser().getId();
//        //执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(),
//                userId.toString()
//        );
//        //判断是否为0
//        if(result != 0L){
//            return Result.ok(result==1?"库存不足":"用户已下单");
//        }
//
//        //如果为0，说明秒杀成功
//        Long orderId = redisWorker.nextId("order");
//        //创建订单对象
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setVoucherId(voucherId);
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//
//        orderTask.add(voucherOrder);
//
//        return Result.ok(orderId);
//
//    }

    //未实现异步下单的redisson分布式锁秒杀
//    @Override
//    public Result setKillVoucher(Long voucherId) {
//        //查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //判断秒杀是否开启
//        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("优惠券秒杀活动尚未开启");
//        }
//        //判断秒杀是否结束
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("优惠券秒杀活动已结束");
//        }
//        //判断库存是否充足
//        if(voucher.getStock() < 1){
//            return Result.fail("优惠券库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//
//        //获取锁对象
//        //SimpleRedisLock simpleRedisLock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
//        RLock rlock = redissonClient.getLock("order:" + userId);
//        //获取锁
//        //boolean isLocked = simpleRedisLock.tryLock(5L);
//        boolean isLocked = rlock.tryLock();
//        //如果获取锁失败
//        if(!isLocked){
//            return Result.fail("一人只能下一单");
//        }
//        //获得代理对象（事务）
//        try{
//            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }finally{
//            //释放锁
//            //simpleRedisLock.unlock();
//            rlock.unlock();
//        }
//
//
//
//    }

//    @Transactional
//    public Result createVoucherOrder(Long voucherId){
//        //判断用户是否已经购买过
//        //用户信息
//        Long userId = UserHolder.getUser().getId();
//        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        if(count > 0){
//            return Result.fail("用户已购买过该优惠券");
//        }
//        //更新库存
//        boolean success = seckillVoucherService.update()
//                .setSql("stock = stock - 1")
//                .eq("voucher_id", voucherId).gt("stock", 0)
//                .update();
//        if(!success){
//            return Result.fail("优惠券库存不足");
//        }
//        //创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //生成订单号
//        long voucherOrderId = redisWorker.nextId("order");
//
//
//        voucherOrder.setVoucherId(voucherId);
//        voucherOrder.setId(voucherOrderId);
//        voucherOrder.setUserId(userId);
//
//        save(voucherOrder);
//        return Result.ok(voucherOrderId);
//    }
}
