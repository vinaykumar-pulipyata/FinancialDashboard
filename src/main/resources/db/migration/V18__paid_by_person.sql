-- An expense somebody else paid for, that you owe them your share of: who paid and your share. No account is involved (from_account_id stays empty),
-- so no balance moves; the share is what you owe that person until you pay it back. Two new nullable columns: no existing row is changed.
ALTER TABLE financial_transaction ADD COLUMN paid_by_borrower_id BIGINT;
ALTER TABLE financial_transaction ADD COLUMN own_share NUMERIC(19, 2);
