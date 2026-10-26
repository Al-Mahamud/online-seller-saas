package com.mojud.tenancy.staff;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** 256-bit random tokens for invite links; only their SHA-256 is stored. */
final class InviteTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private InviteTokens() {
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** The invited address with most of it hidden, for showing to whoever holds the link. */
    static String mask(String destination) {
        if (destination.startsWith("+880")) {
            String national = destination.substring(3);
            return national.substring(0, 3) + "•••••" + national.substring(national.length() - 3);
        }
        int at = destination.indexOf('@');
        return destination.charAt(0) + "•••" + destination.substring(at);
    }
}
