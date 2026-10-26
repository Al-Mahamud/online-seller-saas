-- Code review of 2b: a former member who rejoins shows when they joined this time, not their first
-- join (created_at stays the row's creation).
ALTER TABLE memberships ADD COLUMN joined_at timestamptz;
UPDATE memberships SET joined_at = created_at;
ALTER TABLE memberships ALTER COLUMN joined_at SET NOT NULL;
