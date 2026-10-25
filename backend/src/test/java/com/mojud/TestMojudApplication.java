package com.mojud;

import com.mojud.support.DevSupportConfig;
import com.mojud.support.EmbeddedPostgresConfig;
import java.util.stream.Stream;
import org.springframework.boot.SpringApplication;

/**
 * Runs the application locally against the embedded PostgreSQL: {@code ./mvnw spring-boot:test-run}.
 * The database is created fresh on every start and discarded on stop. One-time codes are not
 * sent: they appear in the log and at {@code /dev/otp?to=…} ({@code DevOtpController}).
 */
public class TestMojudApplication {

    public static void main(String[] args) {
        String[] database = EmbeddedPostgresConfig.connectionProperties().entrySet().stream()
                .map(property -> "--" + property.getKey() + "=" + property.getValue())
                .toArray(String[]::new);
        SpringApplication.from(MojudApplication::main)
                .with(DevSupportConfig.class)
                .withAdditionalProfiles("local")
                .run(Stream.concat(Stream.of(database), Stream.of(args)).toArray(String[]::new));
    }
}
