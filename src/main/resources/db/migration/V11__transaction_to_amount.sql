-- A transfer between two accounts in different currencies records what arrived as well as what left: `amount` is in the
-- from-account's currency and `to_amount` in the to-account's. Empty for everything else (the same amount arrived).
ALTER TABLE financial_transaction ADD COLUMN to_amount NUMERIC(19, 2);
