package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        //从redis中查询缓存
        String jsonString = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+id);
        //判断缓存是否存在
        if(!StrUtil.isBlank(jsonString)){
            Shop shop = JSONObject.parseObject(jsonString, Shop.class);
            return Result.ok(shop);
        }
        //如果缓存不存在，则查询数据库
        Shop shop = getById(id);
        //判断数据库是否存在
        if(shop == null){
            return Result.fail("店铺不存在");
        }
        //如果数据库存在，则将数据写入缓存,并设置有效期
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONObject.toJSONString(shop),
                RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
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
