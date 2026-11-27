-- The day of the month a credit card's statement (the bill) is generated, apart from the day it is due. Optional: a card
-- without one behaves as before (the whole current balance is treated as due).
ALTER TABLE credit_card_detail ADD COLUMN statement_day INTEGER;
