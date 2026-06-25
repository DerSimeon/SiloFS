-- V9__object_encryption_metadata.sql — M8.5: persist object encryption-at-rest metadata.

ALTER TABLE objects
    ADD COLUMN IF NOT EXISTS encryption_mode   TEXT,
    ADD COLUMN IF NOT EXISTS encryption_key_id TEXT,
    ADD COLUMN IF NOT EXISTS encryption_nonce  BYTEA;

ALTER TABLE multipart_parts
    ADD COLUMN IF NOT EXISTS encryption_mode   TEXT,
    ADD COLUMN IF NOT EXISTS encryption_key_id TEXT,
    ADD COLUMN IF NOT EXISTS encryption_nonce  BYTEA;

