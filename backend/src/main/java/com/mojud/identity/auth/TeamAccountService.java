package com.mojud.identity.auth;

import com.mojud.identity.TeamAccounts;
import com.mojud.identity.account.ContactConflicts;
import com.mojud.identity.account.User;
import com.mojud.identity.account.UserIds;
import com.mojud.identity.account.UserRepository;
import com.mojud.shared.email.EmailAddress;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.phone.PhoneNumber;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class TeamAccountService implements TeamAccounts {

    /** No 0/O, 1/l/I: read aloud or copied by hand from a phone. 8 of 54 is about 46 bits. */
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final SecureRandom random = new SecureRandom();
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final LoginSessions sessions;
    private final TransactionTemplate transactions;
    private final Clock clock;

    TeamAccountService(UserRepository users, PasswordEncoder passwords, LoginSessions sessions,
            TransactionTemplate transactions, Clock clock) {
        this.users = users;
        this.passwords = passwords;
        this.sessions = sessions;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Created create(String userId, String name, PhoneNumber phone, @Nullable EmailAddress email, String locale,
            UUID businessId) {
        String id = UserIds.require(userId, "userId");
        User.cleanName(name);
        if (UserIds.isReserved(id) || users.existsByUserId(id)) {
            throw ApiException.invalidField("userId", "field.user_id_taken");
        }
        String password = startingPassword();
        Instant now = clock.instant();
        User account = User.forTeam(id, name, phone, email, passwords.encode(password), locale, businessId,
                now.plus(STARTING_PASSWORD_LIFETIME), now);
        try {
            users.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            RuntimeException translated = ContactConflicts.translate(e);
            throw translated instanceof ApiException api && api.code() == ErrorCode.USER_ID_TAKEN
                    ? ApiException.invalidField("userId", "field.user_id_taken")
                    : translated;
        }
        return new Created(account.getId(), password);
    }

    @Override
    public Optional<String> newStartingPassword(UUID userId, UUID businessId) {
        String password = startingPassword();
        String hash = passwords.encode(password);
        boolean given = Boolean.TRUE.equals(transactions.execute(status -> users.findById(userId)
                .filter(user -> user.createdForBusiness().filter(businessId::equals).isPresent())
                .map(user -> {
                    Instant now = clock.instant();
                    user.giveStartingPassword(hash, now.plus(STARTING_PASSWORD_LIFETIME), now);
                    return true;
                })
                .orElse(false)));
        if (!given) {
            return Optional.empty();
        }
        sessions.revokeAll(userId);
        return Optional.of(password);
    }

    /** Like {@code k7Qm-29xa}: two groups of four, easy to read out. */
    private String startingPassword() {
        StringBuilder password = new StringBuilder(9);
        for (int i = 0; i < 8; i++) {
            if (i == 4) {
                password.append('-');
            }
            password.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }
}
