package com.mojud.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestAccounts.Account;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The signed-in user's own account (implementation-plan.md 1b). */
@IntegrationTest
class AccountTests {

    private static final String NEW_PASSWORD = "a brand new password";

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    DataSource dataSource;

    // Profile and language.

    @Test
    void nameAndLanguageCanBeChanged() {
        ApiClient client = client();
        TestAccounts.signUp(client, messages);

        ApiResponse updated = client.patch("/api/v1/me", Map.of("name", "  Rahima Khatun ", "locale", "bn"))
                .expectStatus(200);

        assertThat(updated.<String>json("$.name")).isEqualTo("Rahima Khatun");
        assertThat(updated.<String>json("$.locale")).isEqualTo("bn");
        assertThat(client.get("/api/v1/me").<String>json("$.locale")).isEqualTo("bn");
    }

    @Test
    void fieldsLeftOutStayAsTheyAre() {
        ApiClient client = client();
        TestAccounts.signUp(client, messages);

        client.patch("/api/v1/me", Map.of("locale", "bn")).expectStatus(200);

        assertThat(client.get("/api/v1/me").<String>json("$.name")).isEqualTo("Test Seller");
    }

    @Test
    void theSavedLanguageWinsOverTheBrowsersOnEveryDevice() {
        ApiClient phone = client().withHeader("Accept-Language", "en");
        Account account = TestAccounts.signUp(phone, messages);
        ApiClient laptop = client().withHeader("Accept-Language", "en");
        laptop.post("/api/v1/auth/login", Map.of("login", account.email(), "password", account.password()))
                .expectStatus(200);

        phone.patch("/api/v1/me", Map.of("locale", "bn")).expectStatus(200);

        for (ApiClient device : List.of(phone, laptop)) {
            assertThat(device.patch("/api/v1/me", Map.of("name", " ")).expectError(400, "validation.failed")
                    .<String>json("$.detail")).isEqualTo("এক বা একাধিক ঘর সঠিকভাবে পূরণ করা হয়নি।");
        }
    }

    @Test
    void signingInUsesTheSavedLanguage() {
        ApiClient client = client();
        Account account = TestAccounts.signUp(client, messages);
        client.patch("/api/v1/me", Map.of("locale", "bn")).expectStatus(200);

        ApiClient fresh = client().withHeader("Accept-Language", "en");
        fresh.post("/api/v1/auth/login", Map.of("login", account.email(), "password", account.password()))
                .expectStatus(200);

        assertThat(fresh.patch("/api/v1/me", Map.of("locale", "fr")).expectError(400, "validation.failed")
                .<List<String>>json("$.errors[*].message")).containsExactly("bn (বাংলা) অথবা en (ইংরেজি) হতে হবে");
    }

    @Test
    void profileChangesAreValidated() {
        ApiClient client = client();
        TestAccounts.signUp(client, messages);

        ApiResponse response = client.patch("/api/v1/me", Map.of("name", "", "locale", "fr"))
                .expectError(400, "validation.failed");

        assertThat(response.<List<String>>json("$.errors[*].field")).contains("name", "locale");
    }

    @Test
    void accountEndpointsNeedASession() {
        client().patch("/api/v1/me", Map.of("name", "x")).expectError(401, "auth.required");
        client().post("/api/v1/me/password", Map.of("currentPassword", "x", "newPassword", NEW_PASSWORD))
                .expectError(401, "auth.required");
        client().post("/api/v1/me/contact/code", Map.of("to", TestAccounts.newEmail()))
                .expectError(401, "auth.required");
    }

    // Password change.

    @Test
    void changingThePasswordSignsOutEveryOtherDevice() {
        ApiClient phone = client();
        Account account = TestAccounts.signUp(phone, messages);
        ApiClient laptop = signIn(account.email(), account.password());

        phone.post("/api/v1/me/password", Map.of("currentPassword", account.password(), "newPassword", NEW_PASSWORD))
                .expectStatus(204);

        phone.get("/api/v1/me").expectStatus(200);
        laptop.get("/api/v1/me").expectError(401, "auth.required");
        client().post("/api/v1/auth/login", Map.of("login", account.email(), "password", account.password()))
                .expectError(401, "auth.invalid_credentials");
        signIn(account.email(), NEW_PASSWORD);
    }

    @Test
    void aWrongCurrentPasswordChangesNothing() {
        ApiClient client = client();
        Account account = TestAccounts.signUp(client, messages);

        client.post("/api/v1/me/password", Map.of("currentPassword", "wrong password", "newPassword", NEW_PASSWORD))
                .expectError(400, "account.wrong_password");

        signIn(account.email(), account.password());
    }

    @Test
    void aStolenSessionCannotGuessThePasswordForever() {
        ApiClient client = client();
        Account account = TestAccounts.signUp(client, messages);

        for (int i = 0; i < 10; i++) {
            client.post("/api/v1/me/password", Map.of("currentPassword", "guess " + i, "newPassword", NEW_PASSWORD))
                    .expectError(400, "account.wrong_password");
        }

        client.post("/api/v1/me/password", Map.of("currentPassword", account.password(), "newPassword", NEW_PASSWORD))
                .expectError(429, "rate_limited");
    }

    // Password reset.

    @Test
    void aResetCodeSetsANewPasswordAndSignsOutEverywhere() {
        ApiClient existing = client();
        Account account = TestAccounts.signUp(existing, messages);
        ApiClient forgetful = client().withHeader("X-Forwarded-For", "203.0.113.60");
        forgetful.post("/api/v1/auth/otp", Map.of("to", account.email(), "purpose", "PASSWORD_RESET"))
                .expectStatus(202);

        forgetful.post("/api/v1/auth/password/reset",
                Map.of("login", account.email(), "code", codeFor(account.email()), "newPassword", NEW_PASSWORD))
                .expectStatus(204);

        existing.get("/api/v1/me").expectError(401, "auth.required");
        signIn(account.email(), NEW_PASSWORD);
    }

    @Test
    void resetCodesGoOnlyToVerifiedContacts() {
        Account account = TestAccounts.signUp(client(), messages);
        String unknown = TestAccounts.newEmail();

        client().post("/api/v1/auth/otp", Map.of("to", account.phone(), "purpose", "PASSWORD_RESET")).expectStatus(202);
        client().post("/api/v1/auth/otp", Map.of("to", unknown, "purpose", "PASSWORD_RESET")).expectStatus(202);

        assertThat(messages.to(account.e164())).isEmpty();
        assertThat(messages.to(unknown)).isEmpty();
    }

    @Test
    void aWrongResetCodeChangesNothing() {
        Account account = TestAccounts.signUp(client(), messages);

        client().post("/api/v1/auth/password/reset",
                Map.of("login", account.email(), "code", "000000", "newPassword", NEW_PASSWORD))
                .expectError(400, "otp.invalid");

        signIn(account.email(), account.password());
    }

    @Test
    void contactCodesCannotBeRequestedBeforeSignIn() {
        client().post("/api/v1/auth/otp", Map.of("to", TestAccounts.newEmail(), "purpose", "VERIFY_CONTACT"))
                .expectError(400, "validation.failed");
    }

    // Phones and emails.

    @Test
    void provingTheSignUpPhoneMakesItUsableForSignIn() {
        ApiClient client = client();
        Account account = TestAccounts.signUp(client, messages);
        client.post("/api/v1/me/contact/code", Map.of("to", account.phone())).expectStatus(202);

        ApiResponse me = client.post("/api/v1/me/contact", Map.of(
                "to", account.phone(), "code", codeFor(account.e164()), "currentPassword", account.password()))
                .expectStatus(200);

        assertThat(me.<Boolean>json("$.phoneVerified")).isTrue();
        signIn(account.phone(), account.password());
    }

    @Test
    void anEmailCanBeAddedAndThenReplaced() {
        ApiClient client = client();
        Account account = TestAccounts.signUpWithPhone(client, messages);
        String first = TestAccounts.newEmail();
        String second = TestAccounts.newEmail();

        confirm(client, account, first);
        signIn(first, account.password());
        ApiResponse me = confirm(client, account, second);

        assertThat(me.<String>json("$.email")).isEqualTo(second);
        signIn(second, account.password());
        client().post("/api/v1/auth/login", Map.of("login", first, "password", account.password()))
                .expectError(401, "auth.invalid_credentials");
    }

    @Test
    void aNewPhoneNumberReplacesTheOldOne() {
        ApiClient client = client();
        Account account = TestAccounts.signUpWithPhone(client, messages);
        String newPhone = TestAccounts.newPhone();

        ApiResponse me = confirm(client, account, newPhone);

        assertThat(me.<String>json("$.phone")).isEqualTo("+88" + newPhone);
        signIn(newPhone, account.password());
        client().post("/api/v1/auth/login", Map.of("login", account.phone(), "password", account.password()))
                .expectError(401, "auth.invalid_credentials");
    }

    @Test
    void noCodeIsSentForAContactAnotherAccountHasProved() {
        Account owner = TestAccounts.signUpWithPhone(client(), messages);
        ApiClient other = client().withHeader("X-Forwarded-For", "203.0.113.70");
        TestAccounts.signUp(other, messages);
        int before = messages.to(owner.e164()).size();

        other.post("/api/v1/me/contact/code", Map.of("to", owner.phone())).expectStatus(202);

        assertThat(messages.to(owner.e164())).hasSize(before);
    }

    @Test
    void changingAContactNeedsTheCurrentPassword() {
        ApiClient client = client();
        Account account = TestAccounts.signUpWithPhone(client, messages);
        String email = TestAccounts.newEmail();
        client.post("/api/v1/me/contact/code", Map.of("to", email)).expectStatus(202);

        client.post("/api/v1/me/contact", Map.of("to", email, "code", codeFor(email), "currentPassword", "stolen session"))
                .expectError(400, "account.wrong_password");
        client.post("/api/v1/me/contact", Map.of("to", email, "code", "000000", "currentPassword", account.password()))
                .expectError(400, "otp.invalid");

        assertThat(client.get("/api/v1/me").<String>json("$.email")).isNull();
    }

    @Test
    void aContactProvedByAnotherAccountMeanwhileIsTaken() {
        ApiClient client = client();
        Account account = TestAccounts.signUpWithPhone(client, messages);
        String email = TestAccounts.newEmail();
        client.post("/api/v1/me/contact/code", Map.of("to", email)).expectStatus(202);
        Account other = TestAccounts.signUpWithPhone(client(), messages);
        JdbcClient.create(dataSource)
                .sql("update users set email = ?, email_verified_at = now() where phone = ? and phone_verified_at is not null")
                .params(email, other.e164()).update();

        client.post("/api/v1/me/contact", Map.of("to", email, "code", codeFor(email), "currentPassword", account.password()))
                .expectError(409, "account.email_taken");
    }

    private ApiResponse confirm(ApiClient client, Account account, String to) {
        client.post("/api/v1/me/contact/code", Map.of("to", to)).expectStatus(202);
        String destination = to.contains("@") ? to : "+88" + to;
        return client.post("/api/v1/me/contact",
                Map.of("to", to, "code", codeFor(destination), "currentPassword", account.password()))
                .expectStatus(200);
    }

    private ApiClient signIn(String login, String password) {
        ApiClient client = client();
        client.post("/api/v1/auth/login", Map.of("login", login, "password", password)).expectStatus(200);
        client.get("/api/v1/me").expectStatus(200);
        return client;
    }

    private ApiClient client() {
        return new ApiClient(port);
    }

    private String codeFor(String destination) {
        return messages.lastCode(destination).orElseThrow(() -> new AssertionError("No code sent to " + destination));
    }
}
