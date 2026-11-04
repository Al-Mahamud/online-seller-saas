-- Why goods were damaged on the shelf, for the damaged products page. Only DAMAGE movements carry
-- one; those recorded before this have none.
ALTER TABLE stock_movements ADD COLUMN reason text;
ALTER TABLE stock_movements ADD CONSTRAINT ck_stock_movements_reason
    CHECK (reason IS NULL OR (type = 'DAMAGE' AND reason IN ('BROKEN', 'STAIN', 'LOST', 'EXPIRED', 'OTHER')));

-- The damaged products page reads damage, and returns, by date across the whole business.
CREATE INDEX ix_stock_movements_damage ON stock_movements (business_id, created_at) WHERE type = 'DAMAGE';
CREATE INDEX ix_returns_created ON returns (business_id, created_at);
