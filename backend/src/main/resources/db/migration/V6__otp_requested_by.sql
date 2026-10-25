-- Code review of 1b: a code that proves a phone or email for a signed-in user belongs to that
-- user. Without this, another signed-in user could request a new code to the same address
-- (replacing the pending one) or burn its guesses. Set only for VERIFY_CONTACT codes; sign-up,
-- sign-in and reset codes have no account yet or are keyed by the account's own contact.
ALTER TABLE otp_challenges ADD COLUMN requested_by uuid;

CREATE INDEX ix_otp_challenges_requested_by ON otp_challenges (requested_by, destination, created_at DESC)
    WHERE requested_by IS NOT NULL;
