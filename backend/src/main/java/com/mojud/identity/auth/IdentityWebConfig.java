package com.mojud.identity.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class IdentityWebConfig implements WebMvcConfigurer {

    private final PasswordGate gate;

    IdentityWebConfig(PasswordGate gate) {
        this.gate = gate;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // First, before a business's role is even looked at.
        registry.addInterceptor(gate).order(-100);
    }
}
