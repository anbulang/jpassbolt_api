package com.jpassbolt.api.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC configuration. Registers the {@link ActionLogInterceptor} so every controller
 * request is recorded in the operation audit log ({@code action_logs}).
 *
 * <p>The interceptor is applied to all paths; per-action filtering (blacklist, enable flag)
 * is handled inside {@link com.jpassbolt.api.service.ActionLogService} so it can be reasoned
 * about and tested in one place.</p>
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final ActionLogInterceptor actionLogInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(actionLogInterceptor).addPathPatterns("/**");
    }
}
