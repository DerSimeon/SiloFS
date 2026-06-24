-- V7__blob_write_intents.sql
--
-- Blob files are published to the filesystem before their metadata rows are
-- committed. A GC pass that sees the file during that window must not delete
-- it merely because objects/multipart_parts do not reference it yet.
--
-- Each writer inserts one intent row after it knows the blob sha256 and before
-- the final rename. The successful metadata transaction clears that exact
-- intent row after inserting the object/part reference. Recovery treats intent
-- rows as live references; old rows are later removed so crash-orphaned blobs
-- can be collected.

CREATE TABLE IF NOT EXISTS blob_write_intents (
    intent_id        TEXT PRIMARY KEY,
    blob_sha256_hex  TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_blob_write_intents_sha
    ON blob_write_intents(blob_sha256_hex);
CREATE INDEX IF NOT EXISTS idx_blob_write_intents_created
    ON blob_write_intents(created_at);