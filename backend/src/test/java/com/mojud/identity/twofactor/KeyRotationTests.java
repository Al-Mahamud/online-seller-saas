package com.mojud.identity.twofactor;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.crypto.SecretBox;
import com.mojud.support.ApiClient;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestClock;
import com.mojud.support.TestTotp;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Rotating ENCRYPTION_KEY (docs/operations.md): with the old key as a previous key, start-up
 * re-seals every authenticator secret, and afterwards the new key alone opens them.
 */
@IntegrationTest
class KeyRotationTests {

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    DataSource dataSource;

    @Autowired
    TestClock clock;

    @Value("${mojud.security.encryption-key}")
    String currentKey;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void aRotatedKeyReSealsEverySecretAndTheOldOneCanGo() {
        ApiClient client = new ApiClient(port);
        TestAccounts.signUp(client, messages);
        UUID userId = UUID.fromString(client.get("/api/v1/me").json("$.id"));
        String secret = client.post("/api/v1/me/two-factor/setup", Map.of("password", TestAccounts.PASSWORD)).json("$.secret");
        client.post("/api/v1/me/two-factor/enable", Map.of("code", TestTotp.code(secret, clock, 0))).expectStatus(200);
        String newKey = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().substring(0, 32).getBytes());

        try {
            TwoFactor rotating = new TwoFactor(dataSource, new SecretBox(newKey, currentKey), clock);
            assertThat(rotating.resealWithCurrentKey()).isPositive();
            assertThat(rotating.resealWithCurrentKey()).as("nothing left to re-seal").isZero();

            TwoFactor newKeyOnly = new TwoFactor(dataSource, new SecretBox(newKey, ""), clock);
            clock.advance(Duration.ofSeconds(30));
            assertThat(newKeyOnly.verify(userId, TestTotp.code(secret, clock, 0)).recoveryCode()).isFalse();
        } finally {
            // Back to the key the rest of the suite uses.
            new TwoFactor(dataSource, new SecretBox(currentKey, newKey), clock).resealWithCurrentKey();
        }
        clock.advance(Duration.ofSeconds(30));
        new ApiClient(port).post("/api/v1/auth/login", Map.of("login", client.get("/api/v1/me").json("$.email"),
                "password", TestAccounts.PASSWORD)).expectStatus(202);
    }
}
