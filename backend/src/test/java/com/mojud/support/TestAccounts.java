package com.mojud.support;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fresh, unique accounts for integration tests. All tests share one database, so every test
 * creates its own users instead of assuming any exist.
 */
public final class TestAccounts {

    /** Numbers 019xxxxxxxx, starting at a random point so repeated local runs do not collide. */
    private static final AtomicInteger NEXT = new AtomicInteger((int) (Math.random() * 50_000_000));

    private static final AtomicLong NEXT_USER_ID = new AtomicLong((long) (Math.random() * 100_000_000_000_000L));

    public static final String PASSWORD = "correct horse battery";

    public record Account(String userId, String phone, String email, String password) {

        /** The phone as stored and as codes are addressed: E.164. */
        public String e164() {
            return "+88" + phone;
        }
    }

    private TestAccounts() {
    }

    /** A phone number nobody has used yet, written the way people type it ({@code 019…}). */
    public static String newPhone() {
        return "019%08d".formatted(NEXT.incrementAndGet());
    }

    /** A User ID nobody has chosen yet. */
    public static String newUserId() {
        return "test_%015d".formatted(NEXT_USER_ID.incrementAndGet());
    }

    public static String newEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    /**
     * Signs up through the API with a phone code, so the phone is verified; there is no email. The
     * client is signed in afterwards.
     */
    public static Account signUpWithPhone(ApiClient client, CapturedMessages messages) {
        Account account = new Account(newUserId(), newPhone(), null, PASSWORD);
        client.post("/api/v1/auth/otp", Map.of("to", account.phone(), "purpose", "SIGNUP")).expectStatus(202);
        String code = messages.lastCode(account.e164()).orElseThrow();
        client.post("/api/v1/auth/signup", Map.of(
                "userId", account.userId(), "name", "Test Seller",
                "phone", account.phone(),
                "password", account.password(),
                "codeSentTo", "PHONE",
                "code", code)).expectStatus(201);
        return account;
    }

    /**
     * Signs up through the API with an email code, so the email is verified and the phone is not.
     * The client is signed in afterwards.
     */
    public static Account signUp(ApiClient client, CapturedMessages messages) {
        Account account = new Account(newUserId(), newPhone(), newEmail(), PASSWORD);
        client.post("/api/v1/auth/otp", Map.of("to", account.email(), "purpose", "SIGNUP")).expectStatus(202);
        String code = messages.lastCode(account.email()).orElseThrow();
        client.post("/api/v1/auth/signup", Map.of(
                "userId", account.userId(), "name", "Test Seller",
                "phone", account.phone(),
                "email", account.email(),
                "password", account.password(),
                "codeSentTo", "EMAIL",
                "code", code)).expectStatus(201);
        return account;
    }
}
