package com.mojud.shared.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Business code reads the time from this {@link Clock} instead of calling {@code Instant.now()},
 * so trial, grace-period and reservation-expiry rules can be tested with a fixed clock.
 */
@Configuration(proxyBeanMethods = false)
class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
