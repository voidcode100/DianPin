package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key, JSONObject.toJSONString(value), time, timeUnit);

    }

    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit timeUnit){
        //设置逻辑过期
        RedisObjectData redisData = new RedisObjectData().builder()
                .data(value)
                .expireTime(LocalDateTime.now().plusSeconds(time))
                .build();

        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONObject.toJSONString(redisData));
    }

    //查询缓存数据，解决缓存穿透问题
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit timeUnit)
    {
        String key = keyPrefix + id;
        //从redis中查询缓存
        String jsonString = stringRedisTemplate.opsForValue().get(key);
        //判断缓存是否存在
        if(!StrUtil.isBlank(jsonString)){
            return JSONObject.parseObject(jsonString, type);
        }
        //判断命中jsonString为""
        if(jsonString!=null){
            return null;
        }
        //如果缓存不存在，则查询数据库
        R r = dbFallback.apply(id);
        //判断数据库是否存在
        if(r == null){
            stringRedisTemplate.opsForValue().set(key,"",
                    RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //如果数据库存在，则将数据写入缓存,并设置有效期
        this.set(key, r, time, timeUnit);
        return r;
    }

    //锁
    private boolean tryLock(String key){
        //利用redis setnx命令尝试获取锁
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        //删除redis数据达到解锁目的
        stringRedisTemplate.delete(key);
    }

    //开辟线程池
    private ExecutorService CACHE_REBUILD_EXCUTOR = Executors.newFixedThreadPool(10);
    //逻辑过期时间解决缓存穿透问题
    public <R,ID> R queryByIdWithLogicExpire(String keyPrefix,String lockKeyPrefix, ID id, Class<R> type,Function<ID,R> dbFallback,Long time
            , TimeUnit timeUnit) {
        String key = keyPrefix + id;
        RedisObjectData box = new RedisObjectData();
        //从redis中查询缓存
        if(nullShopIdCheckWithExpire(key,box)){
            return dealData(box,type);
        }
        //获取锁
        String lockKey = lockKeyPrefix + id;
        boolean isLock = tryLock(lockKey);
        if(isLock){

            //获取锁成功,开辟新线程处理数据重建
            CACHE_REBUILD_EXCUTOR.submit(() -> {
                try {
                    //判断缓存是否过期
                    //Double-Check
                    if(nullShopIdCheckWithExpire(key,box)){
                        return;
                    }
                    //获取店铺信息
                    R apply = dbFallback.apply(id);
                    //Thread.sleep(100);//模拟数据重建时间
                    //封装,并写入redis
                    this.setWithLogicExpire(key,apply,time,timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //返回过期商铺信息
        R r = dealData(box,type);
        return r;

    }


    private<R> boolean nullShopIdCheckWithExpire(String key,RedisObjectData r){
        //从redis中查询缓存
        String jsonString = stringRedisTemplate.opsForValue().get(key);

        //判断缓存是否存在
        if(StrUtil.isBlank(jsonString)){
            r.setData(null);
            return true;
        }

        //fastjson转化
        RedisObjectData redisObjectData = JSONObject.parseObject(jsonString, RedisObjectData.class);

        LocalDateTime expireTime = redisObjectData.getExpireTime();
        BeanUtils.copyProperties(redisObjectData,r);
        //判断缓存释放过期
        if(expireTime.isAfter(LocalDateTime.now())){
            return true; //缓存未过期，直接返回
        }

        //已过期，需缓存重建
        return false;
    }

    private<R> R dealData(RedisObjectData box,Class<R> type){
        String jsonString = JSONObject.toJSONString(box.getData());
        if(StrUtil.isBlank(jsonString)){
            return null;
        }
        return JSONObject.parseObject(jsonString,type);
    }


    //互斥锁解决缓存击穿问题
    public<R,ID> R queryByIdWithMutex(String keyPrefix,String lockKeyPrefix, ID id, Class<R> type,Function<ID,R> dbFallback,Long time
            , TimeUnit timeUnit){

        String key = keyPrefix + id;
        try {
            //缓存不存在，尝试获取分布式锁
            String lockKey = lockKeyPrefix + id;
            RedisObjectData box = new RedisObjectData();
            do{
                if(nullShopIdCheck(key,box)){
                    return dealData(box,type);
                };
                Thread.sleep(50);
            }while(!tryLock(lockKey));

            //Double-Check
            if(nullShopIdCheck(key,box)){
                return dealData(box,type);
            }
            //如果缓存不存在，则查询数据库
            R r = dbFallback.apply(id);

            Thread.sleep(200); //模拟查询数据库耗时

            //判断数据库是否存在
            if(r == null){
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,"",
                        RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //如果数据库存在，则将数据写入缓存,并设置有效期
            set(key, r, time, timeUnit);
            return r;

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放锁
            unlock(RedisConstants.LOCK_SHOP_KEY+id);
        }



    }

    private<R> boolean nullShopIdCheck(String key,RedisObjectData box){
        //从redis中查询缓存
        String jsonString = stringRedisTemplate.opsForValue().get(key);
        //判断缓存是否存在
        if(!StrUtil.isBlank(jsonString)){
            Shop shop = JSONObject.parseObject(jsonString, Shop.class);
            box.setData(shop);
            return true;
        }
        //判断命中jsonString为""
        if(jsonString!=null){
            box.setData(null);
            return true;
        }
        return false;
    }

}
