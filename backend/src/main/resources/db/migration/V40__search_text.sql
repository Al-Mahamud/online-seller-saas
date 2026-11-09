-- Names as search compares them: lower case and Unicode-normalised (NFC). Some keyboards type
-- য়, ড় and ঢ় as one character and others as two; NFC makes both the two-character form, so a
-- search typed either way finds a name saved either way. The search text is normalised the same
-- way before it reaches the database.
CREATE FUNCTION search_text(value text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    RETURN normalize(lower(value), NFC);

DROP INDEX ix_products_name_trgm;
CREATE INDEX ix_products_name_trgm ON products USING gin (search_text(name) gin_trgm_ops);
DROP INDEX ix_customers_name_trgm;
CREATE INDEX ix_customers_name_trgm ON customers USING gin (search_text(name) gin_trgm_ops);
