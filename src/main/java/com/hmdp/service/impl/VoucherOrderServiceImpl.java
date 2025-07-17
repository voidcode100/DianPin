package com.hmdp.service.impl;

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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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

    @Override
    public Result setKillVoucher(Long voucherId) {
        //查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //判断秒杀是否开启
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("优惠券秒杀活动尚未开启");
        }
        //判断秒杀是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("优惠券秒杀活动已结束");
        }
        //判断库存是否充足
        if(voucher.getStock() < 1){
            return Result.fail("优惠券库存不足");
        }
        Long userId = UserHolder.getUser().getId();

        //获取锁对象
        //SimpleRedisLock simpleRedisLock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        RLock rlock = redissonClient.getLock("order:" + userId);
        //获取锁
        //boolean isLocked = simpleRedisLock.tryLock(5L);
        boolean isLocked = rlock.tryLock();
        //如果获取锁失败
        if(!isLocked){
            return Result.fail("一人只能下一单");
        }
        //获得代理对象（事务）
        try{
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally{
            //释放锁
            //simpleRedisLock.unlock();
            rlock.unlock();
        }



    }

    @Transactional
    public Result createVoucherOrder(Long voucherId){
        //判断用户是否已经购买过
        //用户信息
        Long userId = UserHolder.getUser().getId();
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count > 0){
            return Result.fail("用户已购买过该优惠券");
        }
        //更新库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if(!success){
            return Result.fail("优惠券库存不足");
        }
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //生成订单号
        long voucherOrderId = redisWorker.nextId("order");


        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(voucherOrderId);
        voucherOrder.setUserId(userId);

        save(voucherOrder);
        return Result.ok(voucherOrderId);
    }
}
