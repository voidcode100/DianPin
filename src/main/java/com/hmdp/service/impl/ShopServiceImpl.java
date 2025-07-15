package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisObjectData;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    @Autowired
    private CacheClient cacheClient;
    /**
     * 根据商铺ID查询商铺信息
     * @param id 商铺ID
     * @return 商铺详情数据
     */
    @Override
    public Result queryById(Long id) {
        //解决缓存穿透问题
//        Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,this::getById,
//            RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //互斥锁解决缓存击穿问题
        Shop shop = cacheClient.queryByIdWithMutex(RedisConstants.CACHE_SHOP_KEY,RedisConstants.LOCK_SHOP_KEY,id,Shop.class,this::getById,
                RedisConstants.CACHE_SHOP_TTL,TimeUnit.SECONDS);

        //逻辑过期时间解决缓存穿透问题
//        Shop shop = cacheClient.queryByIdWithLogicExpire(RedisConstants.CACHE_SHOP_KEY,RedisConstants.LOCK_SHOP_KEY,id,Shop.class,this::getById,
//                30L,TimeUnit.MINUTES);
        if(shop == null){
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
    }


    //获取锁
    private boolean tryLock(String key){
        //利用redis setnx命令尝试获取锁
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        //删除redis数据达到解锁目的
        stringRedisTemplate.delete(key);
    }

    //互斥锁解决缓存击穿问题
    private Shop queryByIdWithMutex(Long id){

        try {
            //缓存不存在，尝试获取分布式锁
            String lockKey = RedisConstants.LOCK_SHOP_KEY+id;
            RedisObjectData box = new RedisObjectData();
            do{
                if(nullShopIdCheck(id,box)){
                    return dealData(box);
                };
                Thread.sleep(50);
            }while(!tryLock(lockKey));

            //Double-Check
            if(nullShopIdCheck(id,box)){
                return dealData(box);
            }
            //如果缓存不存在，则查询数据库
            Shop shop = getById(id);

            //Thread.sleep(200); //模拟查询数据库耗时

            //判断数据库是否存在
            if(shop == null){
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,"",
                        RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //如果数据库存在，则将数据写入缓存,并设置有效期
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONObject.toJSONString(shop),
                    RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return shop;

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放锁
            unlock(RedisConstants.LOCK_SHOP_KEY+id);
        }



    }

    private boolean nullShopIdCheck(Long id,RedisObjectData box){
        //从redis中查询缓存
        String jsonString = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+id);
        //判断缓存是否存在
        if(!StrUtil.isBlank(jsonString)){
            Shop shop = JSONObject.parseObject(jsonString, Shop.class);
            box.setData(shop);
            return true;
        }
        //判断命中jsonString为""
        if(jsonString!=null){
            return true;
        }
        return false;
    }

    private boolean nullShopIdCheckWithExpire(Long id,RedisObjectData box){
        //从redis中查询缓存
        String jsonString = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+id);

        //判断缓存是否存在
        if(StrUtil.isBlank(jsonString)){
            return true;
        }

        //fastjson转化
        RedisObjectData redisObjectData = JSONObject.parseObject(jsonString, RedisObjectData.class);
        LocalDateTime expireTime = redisObjectData.getExpireTime();
        BeanUtils.copyProperties(redisObjectData,box);
        //判断缓存释放过期
        if(expireTime.isAfter(LocalDateTime.now())){
            return true; //缓存未过期，直接返回
        }

        //已过期，需缓存重建
        return false;
    }

    //开辟线程池
    private ExecutorService CACHE_REBUILD_EXCUTOR = Executors.newFixedThreadPool(10);
    //逻辑过期时间解决缓存穿透问题
    private Shop queryByIdWithLogicExpire(Long id){
        RedisObjectData box=new RedisObjectData();
        //从redis中查询缓存
        if(nullShopIdCheckWithExpire(id,box)){
            return dealData(box);
        }
        //获取锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if(isLock){

            //获取锁成功,开辟新线程处理数据重建
            CACHE_REBUILD_EXCUTOR.submit(() -> {
                try {
                    //判断缓存是否过期
                    //Double-Check
                    if(nullShopIdCheckWithExpire(id,box)){
                        return;
                    }
                    this.saveShop2Redis(id,30*60L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //返回过期商铺信息
        Shop shop = dealData(box);
        return shop;

    }

    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //获取店铺信息
        Shop shop = getById(id);
        //Thread.sleep(100);//模拟数据重建时间
        //封装
        RedisObjectData redisObjectData = RedisObjectData.builder()
                .expireTime(LocalDateTime.now().plusSeconds(expireSeconds))
                .data(shop)
                .build();
        //写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONObject.toJSONString(redisObjectData));
    }

    private Shop dealData(RedisObjectData box){
        String jsonString = JSONObject.toJSONString(box.getData());
        if(StrUtil.isBlank(jsonString)){
            return null;
        }
        return JSONObject.parseObject(jsonString,Shop.class);
    }




    @Transactional
    @Override
    public Result updateShop(Shop shop) {
        //检验商铺ID是否存在
        if(shop.getId() == null){
            return Result.fail("商铺ID不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+shop.getId());
        //返回
        return Result.ok();
    }
}
