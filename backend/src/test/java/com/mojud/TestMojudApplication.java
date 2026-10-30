package com.mojud;

import com.mojud.support.DevSupportConfig;
import com.mojud.support.EmbeddedPostgresConfig;
import java.util.stream.Stream;
import org.springframework.boot.SpringApplication;

/**
 * Runs the application locally against the embedded PostgreSQL: {@code ./mvnw spring-boot:test-run}.
 * The database is created fresh on every start and discarded on stop. One-time codes are not
 * sent: they appear in the log and at {@code /dev/otp?to=…} ({@code DevOtpController}). Paying goes
 * to the stand-in gateway ({@code FakeSslCommerz}) on this app's own port: no real money.
 */
public class TestMojudApplication {

    private static final String[] FAKE_GATEWAY = {
        "--mojud.billing.sslcommerz.base-url=http://localhost:${server.port:8080}/api/v1/public/gateway/fake-sslcommerz",
        "--mojud.billing.sslcommerz.store-id=test-store",
        "--mojud.billing.sslcommerz.store-password=test-password",
    };

    public static void main(String[] args) {
        String[] database = EmbeddedPostgresConfig.connectionProperties().entrySet().stream()
                .map(property -> "--" + property.getKey() + "=" + property.getValue())
                .toArray(String[]::new);
        SpringApplication.from(MojudApplication::main)
                .with(DevSupportConfig.class)
                .withAdditionalProfiles("local")
                .run(Stream.of(Stream.of(database), Stream.of(FAKE_GATEWAY), Stream.of(args)).flatMap(s -> s)
                        .toArray(String[]::new));
    }
}
