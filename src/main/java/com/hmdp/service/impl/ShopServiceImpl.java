package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisShopData;
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
    /**
     * 根据商铺ID查询商铺信息
     * @param id 商铺ID
     * @return 商铺详情数据
     */
    @Override
    public Result queryById(Long id) {
        //互斥锁解决缓存击穿问题
        Shop shop = queryByIdWithMutex(id);

        //逻辑过期时间解决缓存穿透问题
        //Shop shop = queryByIdWithLogicExpire(id);
        if(shop == null){
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
    }

    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //获取店铺信息
        Shop shop = getById(id);
        //Thread.sleep(100);//模拟数据重建时间
        //封装
        RedisShopData redisShopData = RedisShopData.builder()
                .expireTime(LocalDateTime.now().plusSeconds(expireSeconds))
                .data(shop)
                .build();
        //写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONObject.toJSONString(redisShopData));
    }

    //开辟线程池
    private ExecutorService CACHE_REBUILD_EXCUTOR = Executors.newFixedThreadPool(10);
    //逻辑过期时间解决缓存穿透问题
    private Shop queryByIdWithLogicExpire(Long id){
        Shop shop = new Shop();
        //从redis中查询缓存
        if(nullShopIdCheckWithExpire(id,shop)){
            return shop;
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
                    if(nullShopIdCheckWithExpire(id,shop)){
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
        return shop;

    }

    //互斥锁解决缓存击穿问题
    private Shop queryByIdWithMutex(Long id){

        Shop shop = new Shop();
        try {
            //缓存不存在，尝试获取分布式锁
            String lockKey = RedisConstants.LOCK_SHOP_KEY+id;
            do{
                if(nullShopIdCheck(id,shop)){
                    return shop;
                };
                Thread.sleep(50);
            }while(!tryLock(lockKey));

            //Double-Check
            if(nullShopIdCheck(id,shop)){
                return shop;
            }
            //如果缓存不存在，则查询数据库
            shop = getById(id);

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

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放锁
            unlock(RedisConstants.LOCK_SHOP_KEY+id);
        }
        return shop;


    }

    private boolean nullShopIdCheckWithExpire(Long id,Shop shop){
        //从redis中查询缓存
        String jsonString = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+id);

        //判断缓存是否存在
        if(StrUtil.isBlank(jsonString)){
            shop = null;
            return true;
        }

        //fastjson转化
        RedisShopData redisShopData = JSONObject.parseObject(jsonString, RedisShopData.class);
        Shop data = JSONObject.parseObject(JSONObject.toJSONString(redisShopData.getData()), Shop.class);
        LocalDateTime expireTime = redisShopData.getExpireTime();
        BeanUtils.copyProperties(data,shop);
        //判断缓存释放过期
        if(expireTime.isAfter(LocalDateTime.now())){
            return true; //缓存未过期，直接返回
        }

        //已过期，需缓存重建
        return false;
    }

    private boolean nullShopIdCheck(Long id,Shop shop){
        //从redis中查询缓存
        String jsonString = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+id);
        //判断缓存是否存在
        if(!StrUtil.isBlank(jsonString)){
            Shop tmpShop = JSONObject.parseObject(jsonString, Shop.class);
            BeanUtils.copyProperties(tmpShop,shop);
            return true;
        }
        //判断命中jsonString为""
        if(jsonString!=null){
            return true;
        }
        return false;
    }

    private boolean tryLock(String key){
        //利用redis setnx命令尝试获取锁
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        //删除redis数据达到解锁目的
        stringRedisTemplate.delete(key);
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
