-- Code review of 1a: a phone or email nobody has proved must not block anyone. Until the SMS
-- gateway exists, sign-up proves only the email, so with plain UNIQUE constraints anyone could
-- register a stranger's phone number and keep its owner out.
--
-- Now a phone or email is unique only once it is verified, and only verified ones can be used to
-- sign in (AccountUserDetailsService, AuthService). Unverified copies may exist on several
-- accounts; whoever proves the number first owns it.

ALTER TABLE users DROP CONSTRAINT uq_users_phone;
ALTER TABLE users DROP CONSTRAINT uq_users_email;

CREATE UNIQUE INDEX uq_users_verified_phone ON users (phone) WHERE phone_verified_at IS NOT NULL;
CREATE UNIQUE INDEX uq_users_verified_email ON users (email) WHERE email_verified_at IS NOT NULL;

-- Every account can sign in with at least one proved contact.
ALTER TABLE users ADD CONSTRAINT ck_users_one_verified_contact
    CHECK (phone_verified_at IS NOT NULL OR email_verified_at IS NOT NULL);
