-- Daily Calendar Notes are sealed (AES-GCM, key from the password; see DiaryVault): a sealed note keeps its encrypted title, text and tags as
-- one base64 value in `body`, which is therefore longer than the plain text it holds. Notes already written are sealed the first time the
-- app is opened with the password.
ALTER TABLE note ALTER COLUMN body VARCHAR(70000) NOT NULL;
ALTER TABLE note ADD COLUMN sealed BOOLEAN DEFAULT FALSE NOT NULL;
