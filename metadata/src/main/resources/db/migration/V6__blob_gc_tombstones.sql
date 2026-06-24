-- V6__blob_gc_tombstones.sql — gap #1: safe two-phase blob GC
--
-- The blob GC can't make filesystem deletion atomic with the DB transaction.
-- Instead, we use a two-phase mark+sweep:
--
-- Phase 1 (mark): inside a transaction, identify unreferenced blobs and
--   record their sha256 in this table. Commit.
-- Phase 2 (sweep): delete the files. But first, re-check each tombstone
--   against live references — if a new reference appeared between mark
--   and sweep, skip that blob.
--
-- This closes the race where:
--   1. GC marks blob X as unreferenced
--   2. An upload writes blob X and commits metadata
--   3. GC deletes blob X
--   4. The object now points to a missing blob
--
-- With the tombstone table, step 3 re-checks and sees the new reference,
-- so it skips the deletion.

CREATE TABLE IF NOT EXISTS blob_gc_tombstones (
    blob_sha256_hex TEXT PRIMARY KEY,
    marked_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
