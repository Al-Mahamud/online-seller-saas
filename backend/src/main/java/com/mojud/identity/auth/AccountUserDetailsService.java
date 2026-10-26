package com.mojud.identity.auth;

import com.mojud.identity.account.User;
import com.mojud.identity.account.UserRepository;
import com.mojud.identity.otp.OtpDestination;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Looks accounts up by verified phone ({@code +880…}) or verified email, for password checks only:
 * an unverified phone or email may belong to someone else (V5__verified_contacts_unique.sql). Account
 * status is checked after the password, in {@link AuthService}, so a wrong password never reveals
 * whether an account is disabled. For unknown accounts Spring still hashes the given password, so
 * the answer takes as long as for a wrong password.
 */
@Service
class AccountUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    AccountUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String login) {
        Optional<User> user = users.findVerified(OtpDestination.ofStored(login));
        return user.map(found -> new Account(found.getId(), found.getPasswordHash()))
                .orElseThrow(() -> new UsernameNotFoundException("No account"));
    }

    /** Lives only during the password check; never stored in the session. */
    record Account(UUID id, String passwordHash) implements UserDetails {

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return List.of();
        }

        @Override
        public String getPassword() {
            return passwordHash;
        }

        @Override
        public String getUsername() {
            return id.toString();
        }
    }
}
