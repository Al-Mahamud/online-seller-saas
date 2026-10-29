-- CI only: a little business data (behind row-level security), so the restore drill shows it
-- survives a backup. Run as the superuser, which row-level security does not apply to.
INSERT INTO users (id, name, phone, email, password_hash, locale, status, email_verified_at, created_at, updated_at, version)
VALUES ('01900000-0000-7000-8000-000000000001', 'Drill Owner', '+8801711000001', 'drill-owner@example.com', '{noop}x', 'bn',
        'ACTIVE', now(), now(), now(), 0);
INSERT INTO businesses (id, name, phone, address, status, created_at, updated_at, version)
VALUES ('01900000-0000-7000-8000-0000000000b1', 'ড্রিল শপ', '+8801711000001', 'Mirpur, Dhaka', 'ACTIVE', now(), now(), 0);
