package com.mojud.shared.db;

import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.shared.db.system.SystemDatabaseProperties;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two connection pools (backend-plan.md §4): the application pool as {@code mojud_app}, under
 * row-level security and tagged with the current business, and a small pool as
 * {@code mojud_system}, reachable only through {@link SystemDatabase}. Flyway connects separately
 * as {@code mojud_owner} ({@code spring.flyway.user}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SystemDatabaseProperties.class)
class DatabaseConfig {

    /** Wraps Spring Boot's auto-configured pool; JPA, jobs and events all use the wrapped one. */
    @Bean
    static BeanPostProcessor tenantAwareDataSource() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof DataSource dataSource && "dataSource".equals(beanName)
                        ? new TenantAwareDataSource(dataSource)
                        : bean;
            }
        };
    }

    /** Not a default candidate, so it never replaces the application pool by accident. */
    @Bean(defaultCandidate = false, destroyMethod = "close")
    HikariDataSource systemDataSource(SystemDatabaseProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("system");
        dataSource.setJdbcUrl(properties.url());
        dataSource.setUsername(properties.username());
        dataSource.setPassword(properties.password());
        dataSource.setMaximumPoolSize(properties.maxPoolSize());
        return dataSource;
    }

    @Bean
    SystemDatabase systemDatabase(@Qualifier("systemDataSource") DataSource systemDataSource) {
        return new SystemDatabase(systemDataSource);
    }
}
