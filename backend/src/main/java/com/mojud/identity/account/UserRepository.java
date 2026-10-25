package com.mojud.identity.account;

import com.mojud.identity.otp.OtpDestination;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Phones are E.164 and emails lower-case, as stored. Only verified phones and emails identify an
 * account: an unverified one may appear on several accounts (V5__verified_contacts_unique.sql).
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("select u from User u where u.phone = :phone and u.phoneVerifiedAt is not null")
    Optional<User> findByVerifiedPhone(String phone);

    @Query("select u from User u where u.email = :email and u.emailVerifiedAt is not null")
    Optional<User> findByVerifiedEmail(String email);

    /** The account that has verified this phone or email, if any. */
    default Optional<User> findVerified(OtpDestination destination) {
        return destination.isPhone()
                ? findByVerifiedPhone(destination.value())
                : findByVerifiedEmail(destination.value());
    }
}
