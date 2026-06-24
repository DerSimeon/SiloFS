-- V3__multipart_extras.sql — M3: extend multipart_uploads with optional
-- content headers and checksum algorithm so CompleteMultipartUpload can
-- materialise an object with the same metadata the client requested at
-- CreateMultipartUpload time.
--
-- All new columns are nullable because M1-created uploads (if any survived
-- across the upgrade) won't have them.

ALTER TABLE multipart_uploads
    ADD COLUMN IF NOT EXISTS content_encoding     TEXT,
    ADD COLUMN IF NOT EXISTS content_language     TEXT,
    ADD COLUMN IF NOT EXISTS cache_control        TEXT,
    ADD COLUMN IF NOT EXISTS content_disposition  TEXT,
    ADD COLUMN IF NOT EXISTS expires              TEXT,
    ADD COLUMN IF NOT EXISTS checksum_algorithm   TEXT;
