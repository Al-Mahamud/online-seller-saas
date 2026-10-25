package com.mojud.identity.account;

import java.util.UUID;

/** The signed-in user's own account. */
public record MeResponse(
        UUID id,
        String name,
        String phone,
        String email,
        boolean phoneVerified,
        boolean emailVerified,
        String locale,
        boolean platformAdmin) {

    public static MeResponse of(User user) {
        return new MeResponse(user.getId(), user.getName(), user.getPhone().e164(),
                user.getEmail().map(Object::toString).orElse(null), user.isPhoneVerified(), user.isEmailVerified(),
                user.getLocale(), user.isPlatformAdmin());
    }
}
