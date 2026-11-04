-- The owner (or a member allowed to manage the team) creates a member's account: a User ID they
-- chose and a random starting password, which works once and for a limited time, and must be
-- replaced at the first sign-in. created_for_business says which business made the account, so
-- only that business may send it a new starting password.
ALTER TABLE users
    ADD COLUMN must_change_password       boolean     NOT NULL DEFAULT false,
    ADD COLUMN starting_password_expires_at timestamptz,
    ADD COLUMN created_for_business       uuid;

-- What staff and managers may do in this business, by area; the owner may do everything. The
-- defaults are the rules from before this change.
ALTER TABLE business_settings
    ADD COLUMN staff_permissions   text[] NOT NULL DEFAULT '{ORDERS,STOCK,CUSTOMERS}',
    ADD COLUMN manager_permissions text[] NOT NULL DEFAULT '{ORDERS,STOCK,CUSTOMERS,COSTS,PRODUCTS,SETTINGS,TEAM}',
    ADD CONSTRAINT ck_business_settings_staff_permissions CHECK (
        staff_permissions <@ '{ORDERS,STOCK,CUSTOMERS,COSTS,PRODUCTS,SETTINGS,TEAM}'::text[]),
    ADD CONSTRAINT ck_business_settings_manager_permissions CHECK (
        manager_permissions <@ '{ORDERS,STOCK,CUSTOMERS,COSTS,PRODUCTS,SETTINGS,TEAM}'::text[]);

-- A person signs up by proving a phone or email; an account a business made proves nothing yet,
-- until its owner verifies one like anyone else.
ALTER TABLE users DROP CONSTRAINT ck_users_one_verified_contact;
ALTER TABLE users ADD CONSTRAINT ck_users_one_verified_contact
    CHECK (phone_verified_at IS NOT NULL OR email_verified_at IS NOT NULL OR created_for_business IS NOT NULL);
