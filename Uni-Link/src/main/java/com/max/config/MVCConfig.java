package com.max.config;

import com.max.Interceptor.LoginInterceptor;
import com.max.Interceptor.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MVCConfig implements WebMvcConfigurer {//添加拦截器,order越小越先执行
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //登录拦截器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                  "/user/code",
                  "/user/login",
                  "/blog/hot",
                  "/shop/**",
                  "/shop-type/**",
                  "/upload//**",
                  "/voucher/**"
                 )
                .order(1);
        //全局刷新拦截器
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(0);
    }
}
