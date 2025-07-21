package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1. 校验手机号格式
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //2. 生成验证码
        String code = RandomUtil.randomNumbers(6);
        //3.发送验证码
        log.debug("发送验证码成功，验证码为：{}", code);
        //4. 保存到验证码到redis中
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY+phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5. 返回结果
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm 登录表单数据
     * @param session HttpSession对象
     * @return 登录结果
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1. 校验手机号格式
        if(RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            return Result.fail("手机号格式错误");
        }
        //2. 从redis中获取验证码,校验验证码
        String code = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + loginForm.getPhone());
        if(code==null || !code.toString().equals(loginForm.getCode())){
            //不一致
            return Result.fail("验证码错误");
        }
        //3. 根据手机号查询用户
        User user = query().eq("phone", loginForm.getPhone()).one();
        //4. 如果不存在，则创建新用户(存储在redis中)
        if(user==null){
            user = new User();
            user.setPhone(loginForm.getPhone());
            user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX +RandomUtil.randomString(10));
            save(user);

        }

        //封装DTO对象
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user,userDTO);
        //生成token作为key
        String token = UUID.randomUUID().toString();
        //将User对象转换为Map
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((filedName,fieldValue)->fieldValue.toString()));
        //将token-User对象存储到redis中
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY+token,userMap);

        //设置token的过期时间
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+token,RedisConstants.LOGIN_USER_TTL,
                TimeUnit.MINUTES);
        //放回token给前端
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //获取用户id
        Long userId = UserHolder.getUser().getId();

        //拼接key
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;

        //redis bitmap签到
        //获取今天是这个月的第几天
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth - 1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //获取用户id
        Long userId = UserHolder.getUser().getId();

        //拼接key
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;

        //获取今天是这个月的第几天
        int dayOfMonth = now.getDayOfMonth();

        //redis统计BitField
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );
        if(result==null || result.isEmpty()){
            return Result.ok(0);
        }

        //遍历bitfield结果
        int count = 0;
        Long num = result.get(0);
        while(true){
            if((num & 1) == 0){
                break;//如果当前位为0，说明没有签到，结束循环
            }else{
                ++count;//如果当前位为1，说明签到，计数加1
            }
            num = num >> 1; //右移一位，检查下一位
        }
        return Result.ok(count);
    }
}
