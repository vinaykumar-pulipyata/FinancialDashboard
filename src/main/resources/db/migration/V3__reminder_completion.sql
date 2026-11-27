-- A repeating reminder is never done as a whole, but each of its occurrences can be ticked: one row per occurrence day, with the
-- day it was ticked. Deleting the reminder removes its rows (Hibernate does that; the foreign key would refuse otherwise).
CREATE TABLE reminder_completion (
    reminder_id BIGINT NOT NULL,
    occurrence_date DATE NOT NULL,
    done_on DATE NOT NULL,
    PRIMARY KEY (reminder_id, occurrence_date),
    FOREIGN KEY (reminder_id) REFERENCES reminder (id)
);
