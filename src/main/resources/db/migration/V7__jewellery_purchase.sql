-- The transfer that paid for a piece of jewellery (empty for a piece that was already owned), so removing the piece or correcting its price
-- can undo or change that transfer instead of rewriting the account's opening balance, which would change past net worth.
ALTER TABLE jewellery_item ADD COLUMN purchase_transaction_id BIGINT;
