-- Staff invites (implementation-plan.md 2b).

CREATE TABLE invites (
    id          uuid        PRIMARY KEY,
    business_id uuid        NOT NULL REFERENCES businesses (id),
    channel     text        NOT NULL,
    -- E.164 phone or lower-case email; only a user who has verified it may accept.
    destination text        NOT NULL,
    role        text        NOT NULL,
    -- Hex SHA-256 of the token in the invite link; the token itself is never stored.
    token_hash  text        NOT NULL,
    invited_by  uuid        NOT NULL REFERENCES users (id),
    expires_at  timestamptz NOT NULL,
    accepted_at timestamptz,
    accepted_by uuid        REFERENCES users (id),
    revoked_at  timestamptz,
    created_at  timestamptz NOT NULL,
    version     bigint      NOT NULL,

    CONSTRAINT uq_invites_token UNIQUE (token_hash),
    CONSTRAINT ck_invites_channel CHECK (channel IN ('PHONE', 'EMAIL')),
    -- Ownership moves only by transfer, never by invite.
    CONSTRAINT ck_invites_role CHECK (role IN ('MANAGER', 'STAFF')),
    CONSTRAINT ck_invites_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_invites_outcome CHECK (accepted_at IS NULL OR revoked_at IS NULL)
);

-- At most one open invite per address in a business; a new one revokes the old.
CREATE UNIQUE INDEX uq_invites_open ON invites (business_id, destination)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

SELECT mojud_enable_tenant_rls('invites');
