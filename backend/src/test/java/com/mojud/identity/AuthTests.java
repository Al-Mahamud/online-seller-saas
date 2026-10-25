package com.mojud.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestAccounts.Account;
import com.mojud.support.TestClock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Sign-up, sign-in and sign-out through the HTTP API (implementation-plan.md 1a). */
@IntegrationTest
class AuthTests {

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    TestClock clock;

    @Autowired
    DataSource dataSource;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    // Sign-up.

    @Test
    void signingUpWithAnEmailCodeSignsTheUserIn() {
        ApiClient client = client();

        Account account = TestAccounts.signUp(client, messages);

        ApiResponse me = client.get("/api/v1/me").expectStatus(200);
        assertThat(me.<String>json("$.phone")).isEqualTo(account.e164());
        assertThat(me.<String>json("$.email")).isEqualTo(account.email());
        assertThat(me.<String>json("$.name")).isEqualTo("Test Seller");
        assertThat(me.<Boolean>json("$.emailVerified")).isTrue();
        assertThat(me.<Boolean>json("$.phoneVerified")).isFalse();
        assertThat(me.<Boolean>json("$.platformAdmin")).isFalse();
    }

    @Test
    void signingUpWithAPhoneCodeVerifiesThePhoneAndNeedsNoEmail() {
        ApiClient client = client();
        String phone = TestAccounts.newPhone();
        client.post("/api/v1/auth/otp", Map.of("to", phone, "purpose", "SIGNUP")).expectStatus(202);

        ApiResponse created = client.post("/api/v1/auth/signup", Map.of(
                "name", "Rahima", "phone", phone, "password", TestAccounts.PASSWORD,
                "codeSentTo", "PHONE", "code", codeFor("+88" + phone))).expectStatus(201);

        assertThat(created.<Boolean>json("$.phoneVerified")).isTrue();
        assertThat(created.<String>json("$.email")).isNull();
    }

    @Test
    void theAccountTakesTheLanguageItSignedUpIn() {
        ApiClient client = client().withHeader("Accept-Language", "bn");

        TestAccounts.signUp(client, messages);

        assertThat(client.get("/api/v1/me").<String>json("$.locale")).isEqualTo("bn");
    }

    @Test
    void codesArriveInTheRequestersLanguage() {
        String email = TestAccounts.newEmail();

        client().withHeader("Accept-Language", "bn")
                .post("/api/v1/auth/otp", Map.of("to", email, "purpose", "SIGNUP")).expectStatus(202);

        CapturedMessages.Message message = messages.to(email).get(0);
        assertThat(message.subject()).startsWith("আপনার মজুদ কোড");
        assertThat(message.body()).contains("কাউকে দেবেন না");
    }

    @Test
    void signUpCodesAreNotSentToSomeoneWhoAlreadyHasAnAccount() {
        Account existing = TestAccounts.signUp(client(), messages);

        // Same answer as for a new number, so nobody can probe which numbers have accounts.
        client().post("/api/v1/auth/otp", Map.of("to", existing.phone(), "purpose", "SIGNUP")).expectStatus(202);

        assertThat(messages.to(existing.e164())).isEmpty();
    }

    @Test
    void aPhoneNumberCanHaveOnlyOneAccount() {
        Account existing = TestAccounts.signUp(client(), messages);
        ApiClient client = client();
        String email = TestAccounts.newEmail();
        client.post("/api/v1/auth/otp", Map.of("to", email, "purpose", "SIGNUP")).expectStatus(202);

        client.post("/api/v1/auth/signup", signUp(existing.phone(), email, codeFor(email)))
                .expectError(409, "account.phone_taken");
    }

    @Test
    void aWrongCodeRevealsNothingAboutTheAccount() {
        Account existing = TestAccounts.signUp(client(), messages);

        client().post("/api/v1/auth/signup", signUp(existing.phone(), TestAccounts.newEmail(), "000000"))
                .expectError(400, "otp.invalid");
    }

    @Test
    void fiveWrongGuessesUseUpTheCode() {
        ApiClient client = client();
        String email = TestAccounts.newEmail();
        client.post("/api/v1/auth/otp", Map.of("to", email, "purpose", "SIGNUP")).expectStatus(202);
        String code = codeFor(email);
        String wrong = code.equals("111111") ? "222222" : "111111";

        for (int i = 0; i < 5; i++) {
            client.post("/api/v1/auth/signup", signUp(TestAccounts.newPhone(), email, wrong))
                    .expectError(400, "otp.invalid");
        }

        client.post("/api/v1/auth/signup", signUp(TestAccounts.newPhone(), email, code))
                .expectError(400, "otp.invalid");
    }

    @Test
    void anExpiredCodeIsRefused() {
        ApiClient client = client();
        String email = TestAccounts.newEmail();
        client.post("/api/v1/auth/otp", Map.of("to", email, "purpose", "SIGNUP")).expectStatus(202);
        String code = codeFor(email);

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        client.post("/api/v1/auth/signup", signUp(TestAccounts.newPhone(), email, code))
                .expectError(400, "otp.invalid");
    }

    @Test
    void aCodeWorksOnceAndOnlyForItsPurpose() {
        ApiClient client = client();
        Account account = TestAccounts.signUp(client, messages);
        String signUpCode = codeFor(account.email());

        client().post("/api/v1/auth/login", Map.of("login", account.email(), "code", signUpCode))
                .expectError(400, "otp.invalid");
        client().post("/api/v1/auth/signup", signUp(TestAccounts.newPhone(), account.email(), signUpCode))
                .expectError(400, "otp.invalid");
    }

    @Test
    void signUpFieldsAreValidated() {
        ApiResponse response = client().post("/api/v1/auth/signup", Map.of(
                        "name", "", "phone", "12345", "password", "short", "codeSentTo", "EMAIL", "code", "12"))
                .expectError(400, "validation.failed");

        assertThat(response.<List<String>>json("$.errors[*].field")).contains("name", "password", "code");
        assertThat(response.<List<String>>json("$.errors[?(@.field == 'code')].message"))
                .containsExactly("must be the 6-digit code");
    }

    @Test
    void anEmailCodeNeedsAnEmailAddress() {
        Map<String, Object> body = signUp(TestAccounts.newPhone(), null, "123456");

        ApiResponse response = client().post("/api/v1/auth/signup", body).expectError(400, "validation.failed");

        assertThat(response.<List<String>>json("$.errors[*].field")).containsExactly("email");
    }

    // Sign-in.

    @Test
    void passwordSignInAcceptsThePhoneInAnyFormOrTheEmail() {
        Account account = TestAccounts.signUp(client(), messages);
        String spaced = account.phone().substring(0, 5) + "-" + account.phone().substring(5);

        for (String login : List.of(account.phone(), "+88" + account.phone(), spaced, account.email().toUpperCase())) {
            ApiClient client = client();
            client.post("/api/v1/auth/login", Map.of("login", login, "password", account.password())).expectStatus(200);
            client.get("/api/v1/me").expectStatus(200);
        }
    }

    @Test
    void aWrongPasswordAndAnUnknownAccountGetTheSameAnswer() {
        Account account = TestAccounts.signUp(client(), messages);

        ApiResponse wrongPassword = client()
                .post("/api/v1/auth/login", Map.of("login", account.phone(), "password", "wrong password"))
                .expectError(401, "auth.invalid_credentials");
        ApiResponse unknown = client()
                .post("/api/v1/auth/login", Map.of("login", TestAccounts.newPhone(), "password", "wrong password"))
                .expectError(401, "auth.invalid_credentials");

        assertThat(unknown.body()).isEqualTo(wrongPassword.body());
    }

    @Test
    void aSignInCodeSignsInAndVerifiesWhereItWasSent() {
        Account account = TestAccounts.signUp(client(), messages);
        ApiClient client = client();
        client.post("/api/v1/auth/otp", Map.of("to", account.phone(), "purpose", "LOGIN")).expectStatus(202);

        ApiResponse me = client.post("/api/v1/auth/login",
                Map.of("login", account.phone(), "code", codeFor(account.e164()))).expectStatus(200);

        assertThat(me.<Boolean>json("$.phoneVerified")).isTrue();
        client.get("/api/v1/me").expectStatus(200);
    }

    @Test
    void signInCodesAreNotSentToUnknownAccounts() {
        String phone = TestAccounts.newPhone();

        client().post("/api/v1/auth/otp", Map.of("to", phone, "purpose", "LOGIN")).expectStatus(202);

        assertThat(messages.to("+88" + phone)).isEmpty();
    }

    @Test
    void signInNeedsExactlyOneOfPasswordAndCode() {
        Account account = TestAccounts.signUp(client(), messages);

        client().post("/api/v1/auth/login", Map.of("login", account.phone()))
                .expectError(400, "validation.failed");
        client().post("/api/v1/auth/login", Map.of("login", account.phone(), "password", "x", "code", "123456"))
                .expectError(400, "validation.failed");
        client().post("/api/v1/auth/login", Map.of("login", "not a phone", "password", "x"))
                .expectError(400, "validation.failed");
    }

    @Test
    void aDisabledAccountCannotSignIn() {
        Account account = TestAccounts.signUp(client(), messages);
        JdbcClient.create(dataSource).sql("update users set status = 'DISABLED' where phone = ?")
                .param(account.e164()).update();

        client().post("/api/v1/auth/login", Map.of("login", account.phone(), "password", account.password()))
                .expectError(403, "auth.account_disabled");
    }

    // Sessions.

    @Test
    void theSessionCookieIsHttpOnlyAndSameSite() {
        Account account = TestAccounts.signUp(client(), messages);

        ApiResponse login = client().post("/api/v1/auth/login",
                Map.of("login", account.phone(), "password", account.password())).expectStatus(200);

        assertThat(login.setCookies()).anySatisfy(cookie -> assertThat(cookie)
                .startsWith("SESSION=").contains("HttpOnly").contains("SameSite=Lax"));
    }

    @Test
    void signingInGivesANewSessionId() {
        Account account = TestAccounts.signUp(client(), messages);
        ApiClient client = client();
        client.get("/api/v1/auth/csrf").expectStatus(204);
        client.post("/api/v1/auth/login", Map.of("login", account.phone(), "password", "wrong password"));
        String before = client.cookie("SESSION").orElse("none");

        client.post("/api/v1/auth/login", Map.of("login", account.phone(), "password", account.password()))
                .expectStatus(200);

        assertThat(client.cookie("SESSION")).isPresent().get().isNotEqualTo(before);
    }

    @Test
    void signingOutEndsTheSession() {
        ApiClient client = client();
        TestAccounts.signUp(client, messages);

        client.post("/api/v1/auth/logout", null).expectStatus(204);

        client.get("/api/v1/me").expectError(401, "auth.required");
    }

    @Test
    void theAccountNeedsASession() {
        client().get("/api/v1/me").expectError(401, "auth.required");
    }

    @Test
    void writesNeedTheCsrfToken() {
        client().postWithoutCsrf("/api/v1/auth/login", Map.of("login", TestAccounts.newPhone(), "password", "x"))
                .expectError(403, "csrf.invalid");
    }

    // Storage and limits.

    @Test
    void passwordsAndCodesAreStoredOnlyAsHashes() {
        Account account = TestAccounts.signUp(client(), messages);
        JdbcClient jdbc = JdbcClient.create(dataSource);

        String hash = jdbc.sql("select password_hash from users where phone = ?").param(account.e164())
                .query(String.class).single();
        List<String> codeHashes = jdbc.sql("select code_hash from otp_challenges where destination = ?")
                .param(account.email()).query(String.class).list();

        assertThat(hash).startsWith("{argon2}").doesNotContain(account.password());
        assertThat(codeHashes).isNotEmpty().allSatisfy(stored -> assertThat(stored)
                .hasSize(64).isNotEqualTo(codeFor(account.email())));
    }

    @Test
    void aSecondCodeWithinAMinuteIsRefusedWithRetryAfter() {
        String email = TestAccounts.newEmail();
        client().post("/api/v1/auth/otp", Map.of("to", email, "purpose", "SIGNUP")).expectStatus(202);

        ApiResponse again = client().post("/api/v1/auth/otp", Map.of("to", email, "purpose", "SIGNUP"))
                .expectError(429, "rate_limited");

        assertThat(again.headers().firstValue("Retry-After")).hasValueSatisfying(seconds ->
                assertThat(Integer.parseInt(seconds)).isBetween(1, 60));
        assertThat(messages.to(email)).hasSize(1);
    }

    @Test
    void tenSignInAttemptsPerAccountAreAllowedIn15Minutes() {
        Account account = TestAccounts.signUp(client(), messages);

        for (int i = 0; i < 10; i++) {
            client().post("/api/v1/auth/login", Map.of("login", account.phone(), "password", "wrong password"))
                    .expectError(401, "auth.invalid_credentials");
        }

        client().post("/api/v1/auth/login", Map.of("login", account.email(), "password", account.password()))
                .expectStatus(200);
        client().post("/api/v1/auth/login", Map.of("login", account.phone(), "password", account.password()))
                .expectError(429, "rate_limited");
    }

    @Test
    void optionsListTheWaysCodesCanBeSent() {
        assertThat(client().get("/api/v1/auth/options").expectStatus(200).<List<String>>json("$.otpChannels"))
                .containsExactlyInAnyOrder("PHONE", "EMAIL");
    }

    private ApiClient client() {
        return new ApiClient(port);
    }

    private String codeFor(String destination) {
        return messages.lastCode(destination).orElseThrow(() -> new AssertionError("No code sent to " + destination));
    }

    private static Map<String, Object> signUp(String phone, String email, String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Test Seller");
        body.put("phone", phone);
        body.put("email", email);
        body.put("password", TestAccounts.PASSWORD);
        body.put("codeSentTo", "EMAIL");
        body.put("code", code);
        return body;
    }
}
