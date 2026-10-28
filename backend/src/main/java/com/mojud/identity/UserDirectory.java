package com.mojud.identity;

import com.mojud.identity.account.User;
import com.mojud.identity.account.UserRepository;
import com.mojud.identity.otp.OtpDestination;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** What other modules may learn about users. */
@Component
public class UserDirectory {

    /** A user as shown to colleagues: name and contacts, nothing about sign-in. */
    public record UserSummary(UUID id, String name, String phone, String email) {
    }

    private final UserRepository users;

    UserDirectory(UserRepository users) {
        this.users = users;
    }

    /** The user who has verified this E.164 phone or lower-case email, if any. */
    @Transactional(readOnly = true)
    public Optional<UUID> findByVerifiedContact(String destination) {
        return users.findVerified(OtpDestination.ofStored(destination)).map(User::getId);
    }

    /** True when this user has verified this E.164 phone or lower-case email. */
    @Transactional(readOnly = true)
    public boolean hasVerified(UUID userId, String destination) {
        return users.findById(userId).map(user -> user.hasVerified(destination)).orElse(false);
    }

    @Transactional(readOnly = true)
    public Map<UUID, UserSummary> summaries(Collection<UUID> ids) {
        return users.findAllById(ids).stream()
                .map(user -> new UserSummary(user.getId(), user.getName(), user.getPhone().e164(),
                        user.getEmail().map(Object::toString).orElse(null)))
                .collect(Collectors.toMap(UserSummary::id, Function.identity()));
    }

    /** The user's phone (E.164) if they have proved it with a code; an unverified number could be anyone's. */
    @Transactional(readOnly = true)
    public Optional<String> verifiedPhone(UUID userId) {
        return users.findById(userId).map(user -> user.getPhone().e164()).filter(phone -> hasVerified(userId, phone));
    }

    @Transactional(readOnly = true)
    public Optional<String> name(UUID userId) {
        return users.findById(userId).map(User::getName);
    }
}
