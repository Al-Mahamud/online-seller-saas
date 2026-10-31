-- A User ID each person chooses at sign-up; with a password, the only way to sign in
-- (docs/auth-redesign.md). Phones and emails stay, for codes, recovery and invites.

ALTER TABLE users ADD COLUMN user_id text;

-- Accounts from before this have no chosen ID. There are no real sellers yet, only test and local
-- data; "forgot user ID" tells anyone who needs theirs.
UPDATE users SET user_id = 'user_' || right(replace(id::text, '-', ''), 15);

ALTER TABLE users ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT uq_users_user_id UNIQUE (user_id);
ALTER TABLE users ADD CONSTRAINT ck_users_user_id CHECK (user_id ~ '^[a-z0-9_]{4,20}$');

-- No more signing in with a code; a code can now recover a forgotten User ID instead.
DELETE FROM otp_challenges WHERE purpose = 'LOGIN';
ALTER TABLE otp_challenges DROP CONSTRAINT ck_otp_challenges_purpose;
ALTER TABLE otp_challenges ADD CONSTRAINT ck_otp_challenges_purpose
    CHECK (purpose IN ('SIGNUP', 'USER_ID_RECOVERY', 'PASSWORD_RESET', 'VERIFY_CONTACT'));
