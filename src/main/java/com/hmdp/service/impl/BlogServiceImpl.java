package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Autowired
    private IUserService userService;
    @Autowired
    private IFollowService followService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlogById(Long id) {
        // 根据id查询博文
        Blog blog = getById(id);
        if(blog==null){
            return Result.fail("博文不存在");
        }

        // 查询用户
        querryBlogUser(blog);
        isBlogLiked(blog);

        return Result.ok(blog);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            querryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    public void isBlogLiked(Blog blog){
        //获取用户id
        UserDTO user =UserHolder.getUser();
        if(user==null){
            return;
        }
        Long userId = user.getId();
        //redis中查询用户是否点赞
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);
    }

    @Override
    public Result likeBlog(Long id) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //redis中查询用户是否点赞
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score==null){
            //未点赞,点赞数+1
            //更新数据库
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //更新数据库成功更新redis，加入点赞集合
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else{
            //已经点赞,取消点赞,点赞数-1
            //更新数据库
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //更新数据库成功更新redis，移除点赞集合
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        //redis查询前五名用户
        Set<String> userSet = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(userSet==null || userSet.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //获得用户id
        List<Long> ids = userSet.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //根据id查询用户
        List<UserDTO> userDTOS = userService.query().in("id",ids)
                .last("ORDER BY FIELD(id,"+idStr +")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        blog.setUserId(userId);
        // 保存探店博文
        boolean isSuccess = save(blog);

        if(!isSuccess){
            return Result.fail("新增博文失败");
        }
        //查询关注的用户id
        List<Follow> follows = followService.query().eq("follow_user_id", userId).list();

        Long blogId = blog.getId();
        //feed流给每一个用户推送zset
        for (Follow follow : follows) {
            String key = RedisConstants.FEED_KEY + follow.getUserId();

            stringRedisTemplate.opsForZSet().add(key,blogId.toString(),System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blogId);
    }

    @Override
    public Result queryBlogofFollow(Long max, Integer offset) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //获取key
        String key = RedisConstants.FEED_KEY + userId;

        //获取feed流数据
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if(typedTuples==null || typedTuples.isEmpty()){
            return Result.ok();
        }

        //解析blogId
        ArrayList<Long> ids = new ArrayList<>();
        Long minTime = 0L;
        int of = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            Long blogId = Long.valueOf(typedTuple.getValue());
            ids.add(blogId);
            Long time = typedTuple.getScore().longValue();
            if(time == minTime){
                of++;
            }else{
                minTime = time;
                of = 1;
            }
        }

        //查询博文
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list();
        //补充博文相关用户，和是否点赞表示
        for (Blog blog : blogs) {
            querryBlogUser(blog);
            isBlogLiked(blog);
        }

        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(of);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }

    void querryBlogUser(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
