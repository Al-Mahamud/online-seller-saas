package com.mojud.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionChecksTests {

    @Test
    void aCompleteServerStarts() {
        assertThat(ProductionChecks.problems(complete())).isEmpty();
        assertThat(ProductionChecks.warnings(complete().withProperty("mojud.email.provider", "smtp")
                .withProperty("mojud.billing.sslcommerz.store-id", "store").withProperty("sentry.dsn", "https://k@sentry.io/1")
                .withProperty("mojud.storage.provider", "s3"))).isEmpty();
    }

    @Test
    void everyProblemIsNamedAtOnce() {
        MockEnvironment unsafe = new MockEnvironment()
                .withProperty("mojud.web.base-url", "http://localhost:5173")
                .withProperty("mojud.web.api-base-url", "https://api.example.com")
                .withProperty("mojud.email.provider", "log");

        assertThat(ProductionChecks.problems(unsafe)).containsExactly(
                "DB_APP_PASSWORD is not set",
                "DB_OWNER_PASSWORD is not set",
                "DB_SYSTEM_PASSWORD is not set",
                "ENCRYPTION_KEY is not set",
                "WEB_BASE_URL must be an https:// address (it is \"http://localhost:5173\")",
                "EMAIL_PROVIDER=log would write sign-in codes to the log");
        assertThatThrownBy(() -> new ProductionChecks(unsafe)).hasMessageStartingWith("Unsafe production settings:\n- DB_APP");
    }

    @Test
    void missingOptionalSettingsAreWarnings() {
        assertThat(ProductionChecks.warnings(complete().withProperty("mojud.storage.provider", "local")))
                .hasSize(4).anyMatch(warning -> warning.startsWith("No email or SMS provider"));
    }

    private static MockEnvironment complete() {
        return new MockEnvironment()
                .withProperty("spring.datasource.password", "a")
                .withProperty("spring.flyway.password", "o")
                .withProperty("mojud.db.system.password", "s")
                .withProperty("mojud.security.encryption-key", "k")
                .withProperty("mojud.web.base-url", "https://app.example.com")
                .withProperty("mojud.web.api-base-url", "https://app.example.com");
    }
}
