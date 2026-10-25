-- Users, one-time codes and login sessions (implementation-plan.md 1a). None of these belong to
-- a business, so they have no business_id and no row-level security.

CREATE TABLE users (
    id                uuid        PRIMARY KEY,
    name              text        NOT NULL,
    -- E.164 Bangladesh mobile; how sellers sign in.
    phone             text        NOT NULL,
    -- Stored lower-case. Optional in the long run; needed while one-time codes go by email.
    email             text,
    -- Argon2id, with Spring Security's {argon2} prefix.
    password_hash     text        NOT NULL,
    locale            text        NOT NULL,
    phone_verified_at timestamptz,
    email_verified_at timestamptz,
    is_platform_admin boolean     NOT NULL DEFAULT false,
    status            text        NOT NULL,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint      NOT NULL,

    CONSTRAINT uq_users_phone UNIQUE (phone),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_phone CHECK (phone ~ '^\+8801[3-9][0-9]{8}$'),
    CONSTRAINT ck_users_email CHECK (email = lower(email) AND email LIKE '%_@_%'),
    CONSTRAINT ck_users_name CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    CONSTRAINT ck_users_locale CHECK (locale IN ('en', 'bn')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

-- Six-digit codes sent by SMS or email. Only the newest challenge per destination and purpose
-- can be used.
CREATE TABLE otp_challenges (
    id          uuid        PRIMARY KEY,
    channel     text        NOT NULL,
    -- E.164 phone or lower-case email, matching users.phone / users.email.
    destination text        NOT NULL,
    purpose     text        NOT NULL,
    -- Hex SHA-256 of "<id>:<code>"; the code itself is never stored.
    code_hash   text        NOT NULL,
    attempts    smallint    NOT NULL DEFAULT 0,
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at  timestamptz NOT NULL,

    CONSTRAINT ck_otp_challenges_channel CHECK (channel IN ('PHONE', 'EMAIL')),
    -- PASSWORD_RESET and VERIFY_CONTACT arrive in Phase 1b.
    CONSTRAINT ck_otp_challenges_purpose CHECK (purpose IN ('SIGNUP', 'LOGIN', 'PASSWORD_RESET', 'VERIFY_CONTACT')),
    CONSTRAINT ck_otp_challenges_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_otp_challenges_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_otp_challenges_latest ON otp_challenges (destination, purpose, created_at DESC);

-- Spring Session JDBC tables, copied from spring-session-jdbc 4.1's schema-postgresql.sql.
-- principal_name holds the user id, so all sessions of a user can be found and revoked at once.
CREATE TABLE spring_session (
    primary_id            char(36)     NOT NULL,
    session_id            char(36)     NOT NULL,
    creation_time         bigint       NOT NULL,
    last_access_time      bigint       NOT NULL,
    max_inactive_interval int          NOT NULL,
    expiry_time           bigint       NOT NULL,
    principal_name        varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id char(36)     NOT NULL,
    attribute_name     varchar(200) NOT NULL,
    attribute_bytes    bytea        NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);
