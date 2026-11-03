-- For now every business takes stock when an order is handed to the courier; the page no longer
-- offers "on confirming". Safe for open orders: each one records what it did to stock (V22,
-- orders.stock_state), so one confirmed under CONFIRM stays DEDUCTED and isn't taken again.
UPDATE business_settings SET stock_commit_point = 'SHIP' WHERE stock_commit_point = 'CONFIRM';
