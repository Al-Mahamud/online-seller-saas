-- The colours of the search panel on every list, chosen by the owner for the whole business.
ALTER TABLE business_settings ADD COLUMN search_theme text NOT NULL DEFAULT 'LEDGER';
ALTER TABLE business_settings ADD CONSTRAINT ck_business_settings_search_theme CHECK (search_theme IN ('LEDGER', 'MINT'));
