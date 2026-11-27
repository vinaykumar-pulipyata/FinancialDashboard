-- A new kind of account: a digital wallet (PhonePe, Paytm, Amazon Pay balance). The type column is an ENUM, so the new value must be added to it.
ALTER TABLE account ALTER COLUMN type ENUM('BANK', 'CASH', 'CREDIT_CARD', 'DEBT', 'GOAL_FUND', 'GOLD', 'INVESTMENT', 'OTHER', 'PROPERTY', 'RECEIVABLE', 'VEHICLE', 'WALLET') NOT NULL;
