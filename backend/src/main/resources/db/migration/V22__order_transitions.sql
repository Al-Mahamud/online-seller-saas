-- Confirming and cancelling orders (implementation-plan.md 7b).

-- What the order has done to stock, so a cancel or an edit undoes exactly that, even if the
-- business changed its stock_commit_point since: NONE (draft), RESERVED (confirmed, stock taken at
-- shipping), DEDUCTED (stock taken: at confirmation, or at shipping from Phase 8).
ALTER TABLE orders
    ADD COLUMN stock_state   text NOT NULL DEFAULT 'NONE',
    ADD COLUMN cancel_reason text,
    ADD CONSTRAINT ck_orders_stock_state CHECK (stock_state IN ('NONE', 'RESERVED', 'DEDUCTED')),
    ADD CONSTRAINT ck_orders_cancel_reason CHECK (length(cancel_reason) BETWEEN 1 AND 500),
    ADD CONSTRAINT ck_orders_cancelled CHECK ((status = 'CANCELLED') = (cancel_reason IS NOT NULL));
