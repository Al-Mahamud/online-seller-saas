package com.mojud.billing;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** The subscription guard runs after tenancy's role check (order 0), so the business is known. */
@Configuration
class BillingWebConfig implements WebMvcConfigurer {

    private final SubscriptionGuard guard;

    BillingWebConfig(SubscriptionGuard guard) {
        this.guard = guard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard).order(10);
    }
}
