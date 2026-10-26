package com.mojud.identity.auth;

import com.mojud.identity.PasswordConfirmation;
import com.mojud.identity.account.User;
import com.mojud.identity.account.UserRepository;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Counts under {@code user:<id>} in the sign-in failure limits. */
@Service
class PasswordConfirmationService implements PasswordConfirmation {

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final AuthRateLimits limits;

    PasswordConfirmationService(UserRepository users, PasswordEncoder passwords, AuthRateLimits limits) {
        this.users = users;
        this.passwords = passwords;
        this.limits = limits;
    }

    @Override
    public void confirm(UUID userId, String password, String clientIp) {
        String limitKey = limitKey(userId);
        limits.checkLogin(limitKey, clientIp);
        String hash = users.findById(userId).map(User::getPasswordHash).orElseThrow(ApiException::notFound);
        if (!passwords.matches(password, hash)) {
            limits.loginFailed(limitKey, clientIp);
            throw new ApiException(ErrorCode.WRONG_PASSWORD);
        }
        limits.loginSucceeded(limitKey, clientIp);
    }

    static String limitKey(UUID userId) {
        return "user:" + userId;
    }
}
