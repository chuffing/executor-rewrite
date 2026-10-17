package com.icbc.nbfh.config;

import com.icbc.nbfh.config.interceptor.PlatformTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册执行器的 Web 拦截器。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final PlatformTokenInterceptor platformTokenInterceptor;

    public WebConfig(PlatformTokenInterceptor platformTokenInterceptor) {
        this.platformTokenInterceptor = platformTokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(platformTokenInterceptor)
                .addPathPatterns("/api/agent/task");
    }
}