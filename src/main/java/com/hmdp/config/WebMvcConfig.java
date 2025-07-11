package com.hmdp.config;

import com.hmdp.interceptor.LoginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/code", // 排除发送验证码的接口
                        "/user/login", // 排除登录接口
                        "/shop/**", // 排除商铺相关接口
                        "/voucher/**", // 排除代金券相关接口
                        "/upload/**" ,// 排除文件上传接口
                        "/blog/hot",// 排除热搜榜接口
                        "shop-type/**" // 排除商铺类型相关接口
                );
    }
}
