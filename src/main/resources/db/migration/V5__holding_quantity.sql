-- How many units or shares a holding has (optional). With it the page can show the average price paid, invested / quantity.
ALTER TABLE holding ADD COLUMN quantity NUMERIC(19, 4);
