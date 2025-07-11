package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import jakarta.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    /**
     * 发送手机验证码
     * @param phone 手机号
     * @param session HttpSession对象
     * @return 发送结果
     */
    Result sendCode(String phone, HttpSession session);

    /**
     * 登录功能
     * @param loginForm 登录表单数据
     * @param session HttpSession对象
     * @return 登录结果
     */
    Result login(LoginFormDTO loginForm, HttpSession session);
}
