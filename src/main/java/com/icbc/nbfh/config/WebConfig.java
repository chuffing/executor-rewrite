package com.icbc.nbfh.config;

import com.icbc.nbfh.config.interceptor.PlatformTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册执行器的 Web 拦截器
 * 所有由平台发起的入站接口都必须携带平台下发的执行器 token，包括运维任务、配置/版本下发以及手动采集入口
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 平台调用执行器的入站路径 */
    private static final String[] PLATFORM_INBOUND_PATHS = {
            "/api/agent/task",
            "/api/agent/config/deploy",
            "/api/agent/version/deploy",
            "/executor/**"
    };

    private final PlatformTokenInterceptor platformTokenInterceptor;

    public WebConfig(PlatformTokenInterceptor platformTokenInterceptor) {
        this.platformTokenInterceptor = platformTokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(platformTokenInterceptor)
                .addPathPatterns(PLATFORM_INBOUND_PATHS);
    }
}
