-- Mutual Funds and Shares: the profit (or loss) booked by selling, the part of every withdrawal above (or below) the cost of what was sold.
-- Empty until the first sale, which counts as nothing.
ALTER TABLE portfolio_detail ADD COLUMN realized_gain NUMERIC(19, 2);
