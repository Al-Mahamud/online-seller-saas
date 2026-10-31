package com.mojud.identity.account;

import com.mojud.shared.error.ApiException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The User ID someone chooses at sign-up and signs in with. Capitals are folded, so {@code Rahim}
 * and {@code rahim} are one ID and a phone's automatic capital doesn't stop a sign-in.
 */
public final class UserIds {

    private static final Pattern RULES = Pattern.compile("[a-z0-9_]{4,20}");

    /** Would look official in a message or to a customer; treated as taken. */
    private static final Set<String> RESERVED = Set.of("admin", "administrator", "help", "mojud", "moderator", "owner",
            "root", "security", "staff", "support", "system");

    private UserIds() {
    }

    /** The stored form of what was typed, which may still break the rules. */
    public static String normalize(String input) {
        return input == null ? "" : input.strip().toLowerCase(Locale.ROOT);
    }

    /** @throws ApiException a validation error on {@code field} when the ID breaks the rules */
    public static String require(String input, String field) {
        String userId = normalize(input);
        if (!RULES.matcher(userId).matches()) {
            throw ApiException.invalidField(field, "field.user_id_invalid");
        }
        return userId;
    }

    public static boolean isReserved(String userId) {
        return RESERVED.contains(userId);
    }
}
