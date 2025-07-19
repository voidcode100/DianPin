package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IUserService userService;
    @Override
    public Result follow(Long followUserId, boolean isFollow) {
        //获取登陆用户id
        Long userId = UserHolder.getUser().getId();
        String key = "follow:"+userId;
        //如果未关注，则添加关注
        if(isFollow){
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if(isSuccess){
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else{
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("follow_user_id", followUserId).eq("user_id", userId));
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }

        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //获取登陆用户id
        Long userId = UserHolder.getUser().getId();
        //统计关注关系
        Long count = query().eq("follow_user_id", followUserId)
                .eq("user_id", userId).count();

        return Result.ok(count > 0);
    }

    @Override
    public Result followCommon(Long id) {
        //获得用户id
        Long userId = UserHolder.getUser().getId();
        //获得key
        String key1 = "follow:" + userId;
        String key2 = "follow:" + id;
        //redis 查询用户共同关注
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        //如果没有共同关注，则返回空
        if(intersect==null||intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        //根据ids查询用户消息
        List<Long> ids = intersect.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());

        //转换成DTO
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(users);
    }
}
