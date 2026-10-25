package com.mojud.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Test and local-run helpers: captured SMS and email ({@link CapturedMessages}) readable at
 * {@code /dev/otp}, and a clock tests can move ({@link TestClock}).
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(DevOtpController.class)
public class DevSupportConfig {

    /** Preferred over the logging senders, which stay configured. */
    @Bean
    @Primary
    CapturedMessages capturedMessages() {
        return new CapturedMessages();
    }

    /** Preferred over the system clock, so code that asks for {@code Clock} gets this one. */
    @Bean
    @Primary
    TestClock testClock() {
        return new TestClock();
    }

    /** {@code /dev/**} is open and needs no CSRF token; it does not exist outside test sources. */
    @Bean
    @Order(0)
    SecurityFilterChain devSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/dev/**")
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }
}
