package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

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
        //4. 保存到验证码到session
        session.setAttribute("code",code);
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
        //2. 校验验证码
        Object code = session.getAttribute("code");
        if(code==null || !code.toString().equals(loginForm.getCode())){
            //不一致
            return Result.fail("验证码错误");
        }
        //3. 根据手机号查询用户
        User user = query().eq("phone", loginForm.getPhone()).one();
        //4. 如果不存在，则创建新用户
        if(user==null){
            user = new User();
            user.setPhone(loginForm.getPhone());
            user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX +RandomUtil.randomString(10));
            save(user);
        }
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user,userDTO);
        session.setAttribute("user",userDTO);
        return Result.ok();
    }
}
