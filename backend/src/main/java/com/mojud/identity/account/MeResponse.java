package com.mojud.identity.account;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The signed-in user's own account. */
public record MeResponse(
        UUID id,
        String userId,
        String name,
        String phone,
        @Nullable String email,
        boolean phoneVerified,
        boolean emailVerified,
        String locale,
        boolean platformAdmin,
        boolean twoFactorEnabled) {

    public static MeResponse of(User user) {
        return new MeResponse(user.getId(), user.getUserId(), user.getName(), user.getPhone().e164(),
                user.getEmail().map(Object::toString).orElse(null), user.isPhoneVerified(), user.isEmailVerified(),
                user.getLocale(), user.isPlatformAdmin(), user.isTwoFactorEnabled());
    }
}
