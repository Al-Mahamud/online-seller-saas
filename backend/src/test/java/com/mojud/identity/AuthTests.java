package com.mojud.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestAccounts.Account;
import com.mojud.support.TestClock;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
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
        assertThat(me.<String>json("$.userId")).isEqualTo(account.userId());
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
                "userId", TestAccounts.newUserId(), "name", "Rahima", "phone", phone, "password", TestAccounts.PASSWORD,
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
        Account existing = TestAccounts.signUpWithPhone(client(), messages);

        int before = messages.to(existing.e164()).size();

        // Same answer as for a new number, so nobody can probe which numbers have accounts.
        client().withHeader("X-Forwarded-For", "203.0.113.50")
                .post("/api/v1/auth/otp", Map.of("to", existing.phone(), "purpose", "SIGNUP")).expectStatus(202);

        assertThat(messages.to(existing.e164())).hasSize(before);
    }

    @Test
    void anUnverifiedPhoneBlocksNobody() {
        // A stranger signs up with an email code and someone else's phone number.
        Account squatter = TestAccounts.signUp(client(), messages);

        // The number's owner can still sign up with it, proving it with a code.
        ApiClient owner = client();
        owner.post("/api/v1/auth/otp", Map.of("to", squatter.phone(), "purpose", "SIGNUP")).expectStatus(202);
        Map<String, Object> body = signUp(squatter.phone(), null, codeFor(squatter.e164()));
        body.put("codeSentTo", "PHONE");
        ApiResponse created = owner.post("/api/v1/auth/signup", body).expectStatus(201);

        ApiResponse login = client().post("/api/v1/auth/login",
                Map.of("userId", created.<String>json("$.userId"), "password", TestAccounts.PASSWORD)).expectStatus(200);
        assertThat(login.<String>json("$.id")).isEqualTo(created.<String>json("$.id"));
    }

    @Test
    void anEmailProvedByAnotherAccountMeanwhileIsTaken() {
        ApiClient client = client();
        String email = TestAccounts.newEmail();
        client.post("/api/v1/auth/otp", Map.of("to", email, "purpose", "SIGNUP")).expectStatus(202);
        // Another account proves the same email before this sign-up completes.
        Account other = TestAccounts.signUpWithPhone(client(), messages);
        JdbcClient.create(dataSource)
                .sql("update users set email = ?, email_verified_at = now() where phone = ?")
                .params(email, other.e164()).update();

        client.post("/api/v1/auth/signup", signUp(TestAccounts.newPhone(), email, codeFor(email)))
                .expectError(409, "account.email_taken");
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

        client().post("/api/v1/auth/user-id/recover", Map.of("to", account.email(), "code", signUpCode))
                .expectError(400, "otp.invalid");
        client().post("/api/v1/auth/signup", signUp(TestAccounts.newPhone(), account.email(), signUpCode))
                .expectError(400, "otp.invalid");
    }

    @Test
    void signUpFieldsAreValidated() {
        ApiResponse response = client().post("/api/v1/auth/signup", Map.of(
                        "userId", "x", "name", "", "phone", "12345", "password", "short", "codeSentTo", "EMAIL", "code", "12"))
                .expectError(400, "validation.failed");

        assertThat(response.<List<String>>json("$.errors[*].field")).contains("name", "password", "code");
        assertThat(response.<List<String>>json("$.errors[?(@.field == 'code')].message"))
                .containsExactly("must be the 6-digit code");
    }

    // User IDs.

    @Test
    void theAvailabilityCheckAnswersForFreeTakenAndReservedIds() {
        Account account = TestAccounts.signUp(client(), messages);

        assertThat(availability(TestAccounts.newUserId())).isTrue();
        assertThat(availability(account.userId())).isFalse();
        assertThat(availability(account.userId().toUpperCase())).isFalse();
        assertThat(availability("mojud")).isFalse();
    }

    @Test
    void theAvailabilityCheckRefusesAnIdThatBreaksTheRules() {
        for (String broken : List.of("abc", "my shop", "a".repeat(21), "রহিম", "dash-ed")) {
            ApiResponse response = client().get("/api/v1/auth/user-id/availability?userId="
                    + URLEncoder.encode(broken, StandardCharsets.UTF_8)).expectError(400, "validation.failed");
            assertThat(response.<List<String>>json("$.errors[*].field")).containsExactly("userId");
        }
    }

    @Test
    void capitalsInAChosenIdAreFolded() {
        String email = TestAccounts.newEmail();
        String chosen = TestAccounts.newUserId();
        client().post("/api/v1/auth/otp", Map.of("to", email, "purpose", "SIGNUP")).expectStatus(202);
        Map<String, Object> body = signUp(TestAccounts.newPhone(), email, codeFor(email));
        body.put("userId", chosen.toUpperCase());

        ApiResponse created = client().post("/api/v1/auth/signup", body).expectStatus(201);

        assertThat(created.<String>json("$.userId")).isEqualTo(chosen);
    }

    @Test
    void aTakenOrReservedIdIsRefusedWithoutUsingTheCodeUp() {
        Account existing = TestAccounts.signUp(client(), messages);
        String email = TestAccounts.newEmail();
        client().post("/api/v1/auth/otp", Map.of("to", email, "purpose", "SIGNUP")).expectStatus(202);
        Map<String, Object> body = signUp(TestAccounts.newPhone(), email, codeFor(email));

        body.put("userId", existing.userId());
        client().post("/api/v1/auth/signup", body).expectError(409, "account.user_id_taken");
        body.put("userId", "support");
        client().post("/api/v1/auth/signup", body).expectError(409, "account.user_id_taken");

        body.put("userId", TestAccounts.newUserId());
        client().post("/api/v1/auth/signup", body).expectStatus(201);
    }

    @Test
    void twoSignUpsRacingForOneIdGetOneAccount() throws Exception {
        String chosen = TestAccounts.newUserId();
        List<String> emails = List.of(TestAccounts.newEmail(), TestAccounts.newEmail(), TestAccounts.newEmail());
        List<Map<String, Object>> bodies = new ArrayList<>();
        for (String email : emails) {
            client().post("/api/v1/auth/otp", Map.of("to", email, "purpose", "SIGNUP")).expectStatus(202);
            Map<String, Object> body = signUp(TestAccounts.newPhone(), email, codeFor(email));
            body.put("userId", chosen);
            bodies.add(body);
        }

        List<ApiResponse> answers = inParallel(3, bodies::get);

        assertThat(answers).filteredOn(answer -> answer.status() == 201).hasSize(1);
        assertThat(answers).filteredOn(answer -> answer.status() == 409).hasSize(2)
                .allSatisfy(answer -> answer.expectError(409, "account.user_id_taken"));
    }

    @Test
    void aForgottenIdComesBackToWhoeverProvesThePhoneOrEmail() {
        Account account = TestAccounts.signUp(client(), messages);
        ApiClient forgetful = client().withHeader("X-Forwarded-For", "203.0.113.70");

        forgetful.post("/api/v1/auth/otp", Map.of("to", account.email(), "purpose", "USER_ID_RECOVERY")).expectStatus(202);
        ApiResponse recovered = forgetful.post("/api/v1/auth/user-id/recover",
                Map.of("to", account.email(), "code", codeFor(account.email()))).expectStatus(200);

        assertThat(recovered.<String>json("$.userId")).isEqualTo(account.userId());
    }

    @Test
    void recoveringAnIdRevealsNothingWithoutTheCode() {
        Account account = TestAccounts.signUp(client(), messages);
        String unknown = TestAccounts.newEmail();

        ApiResponse known = client().post("/api/v1/auth/user-id/recover", Map.of("to", account.email(), "code", "000000"))
                .expectError(400, "otp.invalid");
        ApiResponse stranger = client().post("/api/v1/auth/user-id/recover", Map.of("to", unknown, "code", "000000"))
                .expectError(400, "otp.invalid");

        assertThat(stranger.body()).isEqualTo(known.body());
    }

    @Test
    void recoveryCodesAreNotSentToUnknownOrUnverifiedContacts() {
        String unknown = TestAccounts.newPhone();
        Account account = TestAccounts.signUp(client(), messages);

        client().post("/api/v1/auth/otp", Map.of("to", unknown, "purpose", "USER_ID_RECOVERY")).expectStatus(202);
        client().post("/api/v1/auth/otp", Map.of("to", account.phone(), "purpose", "USER_ID_RECOVERY")).expectStatus(202);

        assertThat(messages.to("+88" + unknown)).isEmpty();
        assertThat(messages.to(account.e164())).isEmpty();
    }

    @Test
    void anEmailCodeNeedsAnEmailAddress() {
        Map<String, Object> body = signUp(TestAccounts.newPhone(), null, "123456");

        ApiResponse response = client().post("/api/v1/auth/signup", body).expectError(400, "validation.failed");

        assertThat(response.<List<String>>json("$.errors[*].field")).containsExactly("email");
    }

    // Sign-in.

    @Test
    void signingInTakesTheUserIdInAnyCase() {
        Account account = TestAccounts.signUp(client(), messages);

        for (String typed : List.of(account.userId(), account.userId().toUpperCase(), " " + account.userId() + " ")) {
            ApiClient client = client();
            client.post("/api/v1/auth/login", Map.of("userId", typed, "password", account.password())).expectStatus(200);
            client.get("/api/v1/me").expectStatus(200);
        }
    }

    @Test
    void aPhoneOrEmailNoLongerSignsIn() {
        ApiClient signedUp = client();
        Account withPhone = TestAccounts.signUpWithPhone(signedUp, messages);
        Account withEmail = TestAccounts.signUp(client(), messages);

        for (String typed : List.of(withPhone.phone(), withPhone.e164(), withEmail.email())) {
            client().post("/api/v1/auth/login", Map.of("userId", typed, "password", TestAccounts.PASSWORD))
                    .expectError(401, "auth.invalid_credentials");
        }
    }

    @Test
    void aWrongPasswordAndAnUnknownAccountGetTheSameAnswer() {
        Account account = TestAccounts.signUp(client(), messages);

        ApiResponse wrongPassword = client()
                .post("/api/v1/auth/login", Map.of("userId", account.userId(), "password", "wrong password"))
                .expectError(401, "auth.invalid_credentials");
        ApiResponse unknown = client()
                .post("/api/v1/auth/login", Map.of("userId", TestAccounts.newUserId(), "password", "wrong password"))
                .expectError(401, "auth.invalid_credentials");
        ApiResponse notEvenAnId = client()
                .post("/api/v1/auth/login", Map.of("userId", "not an id!", "password", "wrong password"))
                .expectError(401, "auth.invalid_credentials");

        assertThat(unknown.body()).isEqualTo(wrongPassword.body());
        assertThat(notEvenAnId.body()).isEqualTo(wrongPassword.body());
    }

    @Test
    void codesCannotSignIn() {
        Account account = TestAccounts.signUpWithPhone(client(), messages);

        client().post("/api/v1/auth/otp", Map.of("to", account.phone(), "purpose", "LOGIN"))
                .expectError(400, "validation.failed");
        client().post("/api/v1/auth/login", Map.of("userId", account.userId(), "code", "123456"))
                .expectError(400, "validation.failed");
    }

    @Test
    void signingInNeedsAUserIdAndAPassword() {
        Account account = TestAccounts.signUp(client(), messages);

        client().post("/api/v1/auth/login", Map.of("userId", account.userId()))
                .expectError(400, "validation.failed");
        client().post("/api/v1/auth/login", Map.of("password", "x"))
                .expectError(400, "validation.failed");
    }

    @Test
    void aDisabledAccountCannotSignIn() {
        Account account = TestAccounts.signUp(client(), messages);
        JdbcClient.create(dataSource).sql("update users set status = 'DISABLED' where email = ?")
                .param(account.email()).update();

        client().post("/api/v1/auth/login", Map.of("userId", account.userId(), "password", account.password()))
                .expectError(403, "auth.account_disabled");
    }

    // Sessions.

    @Test
    void theSessionCookieIsHttpOnlyAndSameSite() {
        Account account = TestAccounts.signUp(client(), messages);

        ApiResponse login = client().post("/api/v1/auth/login",
                Map.of("userId", account.userId(), "password", account.password())).expectStatus(200);

        assertThat(login.setCookies()).anySatisfy(cookie -> assertThat(cookie)
                .startsWith("SESSION=").contains("HttpOnly").contains("SameSite=Lax"));
    }

    @Test
    void signingInGivesANewSessionId() {
        Account account = TestAccounts.signUp(client(), messages);
        ApiClient client = client();
        client.get("/api/v1/auth/csrf").expectStatus(204);
        client.post("/api/v1/auth/login", Map.of("userId", account.userId(), "password", "wrong password"));
        String before = client.cookie("SESSION").orElse("none");

        client.post("/api/v1/auth/login", Map.of("userId", account.userId(), "password", account.password()))
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
        client().postWithoutCsrf("/api/v1/auth/login", Map.of("userId", TestAccounts.newUserId(), "password", "x"))
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
    void tenFailedSignInsFromOneClientBlockOnlyThatClient() {
        Account account = TestAccounts.signUp(client(), messages);
        ApiClient attacker = client().withHeader("X-Forwarded-For", "203.0.113.10");

        for (int i = 0; i < 10; i++) {
            attacker.post("/api/v1/auth/login", Map.of("userId", account.userId(), "password", "wrong password"))
                    .expectError(401, "auth.invalid_credentials");
        }

        attacker.post("/api/v1/auth/login", Map.of("userId", account.userId(), "password", account.password()))
                .expectError(429, "rate_limited");
        client().withHeader("X-Forwarded-For", "198.51.100.20")
                .post("/api/v1/auth/login", Map.of("userId", account.userId(), "password", account.password()))
                .expectStatus(200);
    }

    @Test
    void aSuccessfulSignInClearsEarlierTypos() {
        Account account = TestAccounts.signUp(client(), messages);

        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < 9; i++) {
                client().post("/api/v1/auth/login", Map.of("userId", account.userId(), "password", "typo"))
                        .expectError(401, "auth.invalid_credentials");
            }
            client().post("/api/v1/auth/login", Map.of("userId", account.userId(), "password", account.password()))
                    .expectStatus(200);
        }
    }

    @Test
    void failedSignInsFromManyClientsAreCappedPerAccount() {
        Account account = TestAccounts.signUp(client(), messages);

        for (int i = 0; i < 50; i++) {
            client().withHeader("X-Forwarded-For", "192.0.2." + (i % 10))
                    .post("/api/v1/auth/login", Map.of("userId", account.userId(), "password", "guess " + i))
                    .expectError(401, "auth.invalid_credentials");
        }

        client().withHeader("X-Forwarded-For", "192.0.2.99")
                .post("/api/v1/auth/login", Map.of("userId", account.userId(), "password", account.password()))
                .expectError(429, "rate_limited");
    }

    @Test
    void someoneElseAskingForCodesDoesNotStopTheOwnerGettingOne() {
        Account account = TestAccounts.signUpWithPhone(client(), messages);
        client().withHeader("X-Forwarded-For", "203.0.113.30")
                .post("/api/v1/auth/otp", Map.of("to", account.phone(), "purpose", "USER_ID_RECOVERY")).expectStatus(202);

        ApiClient owner = client().withHeader("X-Forwarded-For", "198.51.100.40");
        owner.post("/api/v1/auth/otp", Map.of("to", account.phone(), "purpose", "USER_ID_RECOVERY")).expectStatus(202);

        owner.post("/api/v1/auth/user-id/recover", Map.of("to", account.phone(), "code", codeFor(account.e164())))
                .expectStatus(200);
    }

    @Test
    void parallelGuessesAtACodeGetFiveTriesInTotal() throws Exception {
        String email = TestAccounts.newEmail();
        client().post("/api/v1/auth/otp", Map.of("to", email, "purpose", "SIGNUP")).expectStatus(202);
        String code = codeFor(email);
        String wrong = code.equals("111111") ? "222222" : "111111";

        List<ApiResponse> answers = inParallel(50, i -> signUp(TestAccounts.newPhone(), email, wrong));

        assertThat(answers).allSatisfy(answer -> answer.expectError(400, "otp.invalid"));
        assertThat(JdbcClient.create(dataSource)
                .sql("select attempts from otp_challenges where destination = ?").param(email)
                .query(Integer.class).single()).isEqualTo(5);
        client().post("/api/v1/auth/signup", signUp(TestAccounts.newPhone(), email, code))
                .expectError(400, "otp.invalid");
    }

    @Test
    void aRightCodeSentInParallelWorksOnce() throws Exception {
        String email = TestAccounts.newEmail();
        client().post("/api/v1/auth/otp", Map.of("to", email, "purpose", "SIGNUP")).expectStatus(202);
        String code = codeFor(email);

        List<ApiResponse> answers = inParallel(10, i -> signUp(TestAccounts.newPhone(), email, code));

        assertThat(answers).filteredOn(answer -> answer.status() == 201).hasSize(1);
        assertThat(answers).filteredOn(answer -> answer.status() != 201)
                .allSatisfy(answer -> answer.expectError(400, "otp.invalid"));
    }

    // Session cookie.

    @Test
    void anActiveUsersCookieIsRenewedOnceADay() {
        ApiClient client = client();
        TestAccounts.signUp(client, messages);

        ApiResponse sameDay = client.get("/api/v1/me").expectStatus(200);
        clock.advance(Duration.ofDays(1).plusMinutes(1));
        ApiResponse nextDay = client.get("/api/v1/me").expectStatus(200);
        ApiResponse sameDayAgain = client.get("/api/v1/me").expectStatus(200);

        assertThat(sameDay.setCookies()).noneMatch(cookie -> cookie.startsWith("SESSION="));
        assertThat(nextDay.setCookies()).anySatisfy(cookie -> assertThat(cookie)
                .startsWith("SESSION=").contains("Max-Age=604800").contains("HttpOnly"));
        assertThat(sameDayAgain.setCookies()).noneMatch(cookie -> cookie.startsWith("SESSION="));
    }

    @Test
    void optionsListTheWaysCodesCanBeSent() {
        assertThat(client().get("/api/v1/auth/options").expectStatus(200).<List<String>>json("$.otpChannels"))
                .containsExactlyInAnyOrder("PHONE", "EMAIL");
    }

    private ApiClient client() {
        return new ApiClient(port);
    }

    /** Sends {@code count} sign-ups at once, each from its own client with its CSRF token ready. */
    private List<ApiResponse> inParallel(int count, IntFunction<Map<String, Object>> body) throws Exception {
        List<ApiClient> clients = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ApiClient client = client();
            client.get("/api/v1/auth/csrf").expectStatus(204);
            clients.add(client);
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<ApiResponse>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ApiClient client = clients.get(i);
                Map<String, Object> request = body.apply(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return client.post("/api/v1/auth/signup", request);
                }));
            }
            start.countDown();
            List<ApiResponse> answers = new ArrayList<>();
            for (Future<ApiResponse> future : futures) {
                answers.add(future.get(60, TimeUnit.SECONDS));
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean availability(String userId) {
        return client().get("/api/v1/auth/user-id/availability?userId=" + userId).expectStatus(200).json("$.available");
    }

    private String codeFor(String destination) {
        return messages.lastCode(destination).orElseThrow(() -> new AssertionError("No code sent to " + destination));
    }

    private static Map<String, Object> signUp(String phone, String email, String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", TestAccounts.newUserId());
        body.put("name", "Test Seller");
        body.put("phone", phone);
        body.put("email", email);
        body.put("password", TestAccounts.PASSWORD);
        body.put("codeSentTo", "EMAIL");
        body.put("code", code);
        return body;
    }
}
