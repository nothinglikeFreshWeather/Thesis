package com.example.Thesis.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Interceptor'ı register ediyoruz ki HTTP isteklerinde devreye girsin.
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**"); // Sadece /api/ altındaki rotaları limitle
    }
}
