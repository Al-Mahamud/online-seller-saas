package com.mojud.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestClock;
import com.mojud.support.TestTotp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Two-step sign-in with an authenticator app (implementation-plan.md 13a): on and off, sign-in, recovery, admins. */
@IntegrationTest
class TwoFactorTests {

    static final String RECOVERY_CODE = "[23456789A-HJ-NP-Z]{5}-[23456789A-HJ-NP-Z]{5}";

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    SystemDatabase systemDatabase;

    @Autowired
    TestClock clock;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /** Signed up, with two-step sign-in turned on. */
    record Enabled(ApiClient client, TestAccounts.Account account, String secret, List<String> recoveryCodes) {
    }

    @Test
    void turningItOnNeedsThePasswordAndACodeFromTheApp() {
        ApiClient client = new ApiClient(port);
        TestAccounts.Account account = TestAccounts.signUp(client, messages);
        ApiClient otherDevice = signIn(account);
        assertThat(client.get("/api/v1/me/two-factor").<Boolean>json("$.enabled")).isFalse();

        client.post("/api/v1/me/two-factor/setup", Map.of("password", "wrong password"))
                .expectError(400, "account.wrong_password");
        client.post("/api/v1/me/two-factor/enable", Map.of("code", "123456")).expectError(409, "two_factor.setup_missing");
        ApiResponse setup = client.post("/api/v1/me/two-factor/setup", Map.of("password", account.password())).expectStatus(200);
        String secret = setup.json("$.secret");
        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat(setup.<String>json("$.otpauthUri"))
                .startsWith("otpauth://totp/Mojud%3A%2B880").contains("secret=" + secret).contains("issuer=Mojud");
        client.post("/api/v1/me/two-factor/enable", Map.of("code", wrong(secret))).expectError(400, "two_factor.invalid_code");
        assertThat(client.get("/api/v1/me").<Boolean>json("$.twoFactorEnabled")).isFalse();

        ApiResponse enabled = client.post("/api/v1/me/two-factor/enable", Map.of("code", TestTotp.code(secret, clock, 0)))
                .expectStatus(200);

        assertThat(enabled.<List<String>>json("$.recoveryCodes")).hasSize(10).allMatch(code -> code.matches(RECOVERY_CODE));
        assertThat(client.get("/api/v1/me").<Boolean>json("$.twoFactorEnabled")).isTrue();
        ApiResponse status = client.get("/api/v1/me/two-factor");
        assertThat(status.<Boolean>json("$.enabled")).isTrue();
        assertThat(status.<Integer>json("$.recoveryCodesLeft")).isEqualTo(10);
        otherDevice.get("/api/v1/me").expectStatus(401);
        assertThat(lastEmail(account).subject()).isEqualTo("Two-step sign-in is on for your Mojud account");
        client.post("/api/v1/me/two-factor/setup", Map.of("password", account.password()))
                .expectError(409, "two_factor.already_enabled");
        // The secret is stored sealed, never as the base32 the app was given.
        byte[] stored = systemDatabase.jdbc().sql("select secret from two_factor tf join users u on u.id = tf.user_id where u.email = ?")
                .param(account.email()).query(byte[].class).single();
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(secret);
    }

    @Test
    void signingInTakesACodeAfterThePasswordAndEachCodeWorksOnce() {
        Enabled user = enabled();
        ApiClient device = new ApiClient(port);

        ApiResponse first = device.post("/api/v1/auth/login",
                Map.of("login", user.account().email(), "password", user.account().password())).expectStatus(202);
        assertThat(first.<Boolean>json("$.twoFactorRequired")).isTrue();
        device.get("/api/v1/me").expectStatus(401);
        // The code used to turn it on is spent.
        device.post("/api/v1/auth/login/two-factor", Map.of("code", TestTotp.code(user.secret(), clock, 0)))
                .expectError(400, "two_factor.invalid_code");
        String code = TestTotp.code(user.secret(), clock, 1);
        ApiResponse me = device.post("/api/v1/auth/login/two-factor", Map.of("code", code)).expectStatus(200);
        assertThat(me.<Boolean>json("$.twoFactorEnabled")).isTrue();
        device.get("/api/v1/me").expectStatus(200);

        ApiClient replay = new ApiClient(port);
        replay.post("/api/v1/auth/login", Map.of("login", user.account().email(), "password", user.account().password()))
                .expectStatus(202);
        replay.post("/api/v1/auth/login/two-factor", Map.of("code", code)).expectError(400, "two_factor.invalid_code");
        // Nothing to finish without the first step.
        new ApiClient(port).post("/api/v1/auth/login/two-factor", Map.of("code", next(user)))
                .expectError(401, "auth.two_factor_expired");
    }

    @Test
    void aSignInCodeAlsoNeedsTheSecondStep() {
        Enabled user = enabled();
        ApiClient device = new ApiClient(port);
        device.post("/api/v1/auth/otp", Map.of("to", user.account().email(), "purpose", "LOGIN")).expectStatus(202);
        String otp = messages.lastCode(user.account().email()).orElseThrow();
        device.post("/api/v1/auth/login", Map.of("login", user.account().email(), "code", otp)).expectStatus(202);
        device.get("/api/v1/me").expectStatus(401);
        device.post("/api/v1/auth/login/two-factor", Map.of("code", next(user))).expectStatus(200);
    }

    @Test
    void theSecondStepRunsOutAfterFiveMinutesOrFiveWrongCodes() {
        Enabled user = enabled();
        ApiClient slow = passwordStep(user);
        clock.advance(Duration.ofMinutes(6));
        slow.post("/api/v1/auth/login/two-factor", Map.of("code", TestTotp.code(user.secret(), clock, 0)))
                .expectError(401, "auth.two_factor_expired");

        ApiClient guesser = passwordStep(user);
        for (int i = 0; i < 5; i++) {
            guesser.post("/api/v1/auth/login/two-factor", Map.of("code", wrong(user.secret()))).expectError(400,
                    "two_factor.invalid_code");
        }
        guesser.post("/api/v1/auth/login/two-factor", Map.of("code", next(user))).expectError(401, "auth.two_factor_expired");
    }

    @Test
    void aRecoveryCodeWorksOnceAndTheOwnerIsTold() {
        Enabled user = enabled();
        String recovery = user.recoveryCodes().get(3);

        ApiClient device = passwordStep(user);
        device.post("/api/v1/auth/login/two-factor", Map.of("code", recovery.toLowerCase().replace("-", " ")))
                .expectStatus(200);
        assertThat(lastEmail(user.account()).body()).contains("with a recovery code").contains("9 recovery codes are left.");
        assertThat(device.get("/api/v1/me/two-factor").<Integer>json("$.recoveryCodesLeft")).isEqualTo(9);

        passwordStep(user).post("/api/v1/auth/login/two-factor", Map.of("code", recovery))
                .expectError(400, "two_factor.invalid_code");

        // New codes replace the old ones.
        List<String> fresh = device.post("/api/v1/me/two-factor/recovery-codes", Map.of("code", next(user)))
                .expectStatus(200).json("$.recoveryCodes");
        assertThat(fresh).hasSize(10).doesNotContainAnyElementsOf(user.recoveryCodes());
        passwordStep(user).post("/api/v1/auth/login/two-factor", Map.of("code", user.recoveryCodes().get(0)))
                .expectError(400, "two_factor.invalid_code");
        passwordStep(user).post("/api/v1/auth/login/two-factor", Map.of("code", fresh.get(0))).expectStatus(200);
        device.post("/api/v1/me/two-factor/recovery-codes", Map.of("code", fresh.get(1)))
                .expectError(400, "two_factor.invalid_code");
    }

    @Test
    void turningItOffNeedsThePasswordAndACode() {
        Enabled user = enabled();
        ApiClient client = user.client();
        client.post("/api/v1/me/two-factor/disable", Map.of("password", "wrong password", "code", next(user)))
                .expectError(400, "account.wrong_password");
        client.post("/api/v1/me/two-factor/disable", Map.of("password", user.account().password(), "code", wrong(user.secret())))
                .expectError(400, "two_factor.invalid_code");

        client.post("/api/v1/me/two-factor/disable", Map.of("password", user.account().password(), "code", next(user)))
                .expectStatus(204);

        assertThat(client.get("/api/v1/me").<Boolean>json("$.twoFactorEnabled")).isFalse();
        assertThat(lastEmail(user.account()).subject()).isEqualTo("Two-step sign-in is off for your Mojud account");
        signIn(user.account()).get("/api/v1/me").expectStatus(200);
        assertThat(systemDatabase.jdbc().sql("""
                        select count(*) from two_factor_recovery_codes c join users u on u.id = c.user_id where u.email = ?
                        """)
                .param(user.account().email()).query(Long.class).single()).isZero();
    }

    @Test
    void platformAdminsMustUseIt() {
        ApiClient client = new ApiClient(port);
        TestAccounts.Account account = TestAccounts.signUp(client, messages);
        systemDatabase.jdbc().sql("update users set is_platform_admin = true where email = ?").param(account.email()).update();
        ApiClient admin = signIn(account);
        assertThat(admin.get("/api/v1/me").<Boolean>json("$.platformAdmin")).isTrue();

        admin.get("/api/v1/admin/metrics").expectError(403, "admin.two_factor_required");

        String secret = admin.post("/api/v1/me/two-factor/setup", Map.of("password", account.password())).expectStatus(200)
                .json("$.secret");
        admin.post("/api/v1/me/two-factor/enable", Map.of("code", TestTotp.code(secret, clock, 0))).expectStatus(200);
        admin.get("/api/v1/admin/metrics").expectStatus(200);
        // A non-admin is refused as before.
        ApiClient owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        owner.get("/api/v1/admin/metrics").expectError(403, "access.denied");
    }

    private Enabled enabled() {
        ApiClient client = new ApiClient(port);
        TestAccounts.Account account = TestAccounts.signUp(client, messages);
        String secret = client.post("/api/v1/me/two-factor/setup", Map.of("password", account.password())).expectStatus(200)
                .json("$.secret");
        List<String> codes = client.post("/api/v1/me/two-factor/enable", Map.of("code", TestTotp.code(secret, clock, 0)))
                .expectStatus(200).json("$.recoveryCodes");
        return new Enabled(client, account, secret, codes);
    }

    /** The app's next code: the clock moves on a step, so each call gives an unused one. */
    private String next(Enabled user) {
        clock.advance(Duration.ofSeconds(30));
        return TestTotp.code(user.secret(), clock, 0);
    }

    /** A six-digit code the app would not show now. */
    private String wrong(String secret) {
        String code = TestTotp.code(secret, clock, 5);
        return code.equals(TestTotp.code(secret, clock, 0)) ? "000000" : code;
    }

    private ApiClient passwordStep(Enabled user) {
        ApiClient device = new ApiClient(port);
        device.post("/api/v1/auth/login", Map.of("login", user.account().email(), "password", user.account().password()))
                .expectStatus(202);
        return device;
    }

    private ApiClient signIn(TestAccounts.Account account) {
        ApiClient device = new ApiClient(port);
        device.post("/api/v1/auth/login", Map.of("login", account.email(), "password", account.password())).expectStatus(200);
        return device;
    }

    private CapturedMessages.Message lastEmail(TestAccounts.Account account) {
        List<CapturedMessages.Message> received = messages.to(account.email());
        return received.get(received.size() - 1);
    }
}
