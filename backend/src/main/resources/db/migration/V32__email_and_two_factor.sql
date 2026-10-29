-- Email and two-step sign-in (implementation-plan.md 13a).

-- Emails a member has turned off, per business. Only the daily low-stock digest can be turned off;
-- billing and security emails always go. No row: the email is on.
CREATE TABLE notification_preferences (
    business_id uuid        NOT NULL REFERENCES businesses (id),
    user_id     uuid        NOT NULL REFERENCES users (id),
    kind        text        NOT NULL,
    email       boolean     NOT NULL,
    updated_at  timestamptz NOT NULL,

    PRIMARY KEY (business_id, user_id, kind),
    CONSTRAINT ck_notification_preferences_kind CHECK (kind IN ('stock.low_digest'))
);
SELECT mojud_enable_tenant_rls('notification_preferences');

-- A user's authenticator (TOTP, RFC 6238). Not business data: no business_id, no row-level security.
CREATE TABLE two_factor (
    user_id    uuid        PRIMARY KEY REFERENCES users (id),
    -- AES-GCM with a key outside the database (mojud.security.encryption-key): nonce, then ciphertext.
    secret     bytea       NOT NULL,
    -- Null while being set up: on only once a code from the app has been accepted.
    enabled_at timestamptz,
    -- The newest 30-second step whose code was accepted; a code works once.
    last_step  bigint,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

-- One-time codes for when the authenticator is lost. Hex SHA-256 of the code (random, 50 bits).
CREATE TABLE two_factor_recovery_codes (
    user_id    uuid        NOT NULL REFERENCES users (id),
    code_hash  text        NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL,

    PRIMARY KEY (user_id, code_hash)
);
