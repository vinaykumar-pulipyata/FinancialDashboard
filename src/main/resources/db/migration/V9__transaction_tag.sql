-- Tags on transactions: short lower-case labels worked out from the note and description, then the sub category and the category, and
-- editable by hand. Deleting a transaction removes its tags (Hibernate does that; the foreign key would refuse otherwise).
CREATE TABLE transaction_tag (
    transaction_id BIGINT NOT NULL,
    tag VARCHAR(30) NOT NULL,
    PRIMARY KEY (transaction_id, tag),
    FOREIGN KEY (transaction_id) REFERENCES financial_transaction (id)
);
