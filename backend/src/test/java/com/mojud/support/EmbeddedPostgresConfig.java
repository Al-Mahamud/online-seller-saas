package com.mojud.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * A real PostgreSQL started from bundled binaries, so tests and local runs need neither Docker
 * nor an installed server. It is set up like a production server: the three roles from
 * {@code db/bootstrap/roles.sql}, a {@code mojud} database owned by {@code mojud_owner}, and the
 * application connecting as {@code mojud_app}. Flyway migrates it as usual.
 */
@TestConfiguration(proxyBeanMethods = false)
public class EmbeddedPostgresConfig {

    static final Map<String, String> PASSWORDS = Map.of(
            "owner_password", "owner-secret",
            "app_password", "app-secret",
            "system_password", "system-secret");

    /** One server per JVM, started on first use and shared by every cached Spring context. */
    private static final class Server {

        static final EmbeddedPostgres INSTANCE = start();

        private static EmbeddedPostgres start() {
            try {
                // The library's own JVM shutdown hook is turned off for the same reason as below.
                EmbeddedPostgres postgres = EmbeddedPostgres.builder().setRegisterShutdownHook(false).start();
                bootstrap(postgres);
                // Not a plain JVM shutdown hook: those run in parallel with Spring's, so the database
                // could stop while a context is still closing (Spring Modulith queries it on close).
                // Spring Boot runs its shutdown handlers after every context has closed.
                SpringApplication.getShutdownHandlers().add(() -> {
                    try {
                        postgres.close();
                    } catch (IOException ignored) {
                        // The JVM is exiting; nothing left to clean up.
                    }
                });
                return postgres;
            } catch (IOException e) {
                throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
            }
        }

        private static void bootstrap(EmbeddedPostgres postgres) throws IOException {
            String script = readRolesScript();
            for (Map.Entry<String, String> password : PASSWORDS.entrySet()) {
                script = script.replace("${" + password.getKey() + "}", password.getValue());
            }
            try (Connection connection = postgres.getPostgresDatabase().getConnection();
                    Statement statement = connection.createStatement()) {
                for (String sql : script.split(";\\s*\\n")) {
                    String withoutComments = sql.replaceAll("(?m)^--.*$", "").strip();
                    if (!withoutComments.isEmpty()) {
                        statement.execute(withoutComments);
                    }
                }
                statement.execute("CREATE DATABASE mojud OWNER mojud_owner");
            } catch (SQLException e) {
                throw new IllegalStateException("Could not set up the embedded database", e);
            }
        }

        private static String readRolesScript() throws IOException {
            try (InputStream in = EmbeddedPostgresConfig.class.getResourceAsStream("/db/bootstrap/roles.sql")) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    /** Starts the server if needed and returns the settings that point the application at it. */
    public static Map<String, String> connectionProperties() {
        return Map.of(
                "spring.datasource.url", "jdbc:postgresql://localhost:" + Server.INSTANCE.getPort() + "/mojud",
                "spring.datasource.password", PASSWORDS.get("app_password"),
                "spring.flyway.password", PASSWORDS.get("owner_password"),
                "mojud.db.system.password", PASSWORDS.get("system_password"));
    }

    /** For tests. Local runs pass the same settings as arguments ({@code TestMojudApplication}). */
    @Bean
    DynamicPropertyRegistrar embeddedPostgresProperties() {
        return registry -> connectionProperties().forEach((name, value) -> registry.add(name, () -> value));
    }
}
