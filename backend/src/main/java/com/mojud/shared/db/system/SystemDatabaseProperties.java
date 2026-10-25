package com.mojud.shared.db.system;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Connection settings for the {@code mojud_system} pool ({@code mojud.db.system.*}). */
@ConfigurationProperties("mojud.db.system")
public record SystemDatabaseProperties(
        String url,
        String username,
        String password,
        @DefaultValue("3") int maxPoolSize) {
}
