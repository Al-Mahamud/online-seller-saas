package com.mojud.identity.account;

import com.mojud.shared.email.EmailAddress;
import com.mojud.shared.text.Text;
import com.mojud.shared.id.Ids;
import com.mojud.shared.phone.PhoneNumber;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    private String name;

    private String phone;

    private String email;

    private String passwordHash;

    private String locale;

    private Instant phoneVerifiedAt;

    private Instant emailVerifiedAt;

    private boolean isPlatformAdmin;

    @Enumerated(EnumType.STRING)
    private UserStatus status;

    private Instant createdAt;

    private Instant updatedAt;

    /** Null until first saved, which is how Spring Data tells a new user from an existing one. */
    @Version
    private Long version;

    protected User() {
    }

    /**
     * A new account. Signing up proves ownership of the phone or the email that received the code,
     * so that one starts verified; the other stays unverified until proved (Phase 1b).
     */
    public static User signUp(String name, PhoneNumber phone, EmailAddress email, String passwordHash,
            String locale, boolean codeSentToPhone, Instant now) {
        User user = new User();
        user.id = Ids.newId();
        user.name = cleanName(name);
        user.phone = phone.e164();
        user.email = email == null ? null : email.value();
        user.passwordHash = passwordHash;
        user.locale = locale;
        user.status = UserStatus.ACTIVE;
        user.createdAt = now;
        user.updatedAt = now;
        if (codeSentToPhone) {
            user.phoneVerifiedAt = now;
        } else {
            user.emailVerifiedAt = now;
        }
        return user;
    }

    public void rename(String newName, Instant now) {
        name = cleanName(newName);
        updatedAt = now;
    }

    /** @throws com.mojud.shared.error.ApiException a validation error on {@code name} when it is blank */
    public static String cleanName(String name) {
        return Text.requireNonBlank(name, "name");
    }

    /** @param newLocale {@code "bn"} or {@code "en"} */
    public void changeLocale(String newLocale, Instant now) {
        locale = newLocale;
        updatedAt = now;
    }

    public void changePassword(String newPasswordHash, Instant now) {
        passwordHash = newPasswordHash;
        updatedAt = now;
    }

    /** The phone received a code: it becomes this account's verified phone, replacing any other. */
    public void verifyPhone(PhoneNumber verified, Instant now) {
        phone = verified.e164();
        phoneVerifiedAt = now;
        updatedAt = now;
    }

    /** The email received a code: it becomes this account's verified email, replacing any other. */
    public void verifyEmail(EmailAddress verified, Instant now) {
        email = verified.value();
        emailVerifiedAt = now;
        updatedAt = now;
    }

    /** True when {@code destination} (E.164 phone or lower-case email) is already verified here. */
    public boolean hasVerified(String destination) {
        return destination.equals(phone) ? phoneVerifiedAt != null
                : destination.equals(email) && emailVerifiedAt != null;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public PhoneNumber getPhone() {
        return new PhoneNumber(phone);
    }

    public Optional<EmailAddress> getEmail() {
        return Optional.ofNullable(email).map(EmailAddress::new);
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getLocale() {
        return locale;
    }

    public boolean isPhoneVerified() {
        return phoneVerifiedAt != null;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public boolean isPlatformAdmin() {
        return isPlatformAdmin;
    }
}
