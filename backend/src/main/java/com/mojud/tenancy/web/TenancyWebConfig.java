package com.mojud.tenancy.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class TenancyWebConfig implements WebMvcConfigurer {

    private final RoleInterceptor roles;

    TenancyWebConfig(RoleInterceptor roles) {
        this.roles = roles;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(roles);
    }
}
