package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 查询商铺类型列表
     * @return 商铺类型列表
     */
    @Override
    public Result queryTypeList() {
        //查询商品类型缓存
        String jsonString = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_TYPE_KEY);
        //如果缓存存在，直接返回
        if(!StrUtil.isBlank(jsonString)){
            List<ShopType> shopTypes = JSONObject.parseArray(jsonString,ShopType.class);
            return Result.ok(shopTypes);
        }
        //如果缓存不存在，则查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //判断集合是否存在
        if(typeList == null || typeList.isEmpty()){
            return Result.fail("商铺类型不存在");
        }
        //如果数据库存在，则将数据写入缓存
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_TYPE_KEY,JSONObject.toJSONString(typeList));
        return Result.ok(typeList);
    }
}
