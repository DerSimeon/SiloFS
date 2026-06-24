-- V2__checksums_and_copy.sql — M2.1: persist x-amz-checksum-* values
--
-- The AWS SDK v2 defaults to sending checksum headers on every PUT. We persist
-- them so we can echo them back on GET/HEAD, which the SDK uses to verify the
-- response integrity. All four checksum types are nullable because the client
-- may supply zero or more (typically one, but the API allows multiple).
--
-- checksum_type records whether the checksum was computed over the full object
-- ('FULL_OBJECT') or a composite of part checksums ('COMPOSITE'). For
-- single-PUT objects it is always 'FULL_OBJECT'.

ALTER TABLE objects
    ADD COLUMN IF NOT EXISTS checksum_crc32  TEXT,
    ADD COLUMN IF NOT EXISTS checksum_crc32c TEXT,
    ADD COLUMN IF NOT EXISTS checksum_sha1   TEXT,
    ADD COLUMN IF NOT EXISTS checksum_sha256 TEXT,
    ADD COLUMN IF NOT EXISTS checksum_type   TEXT;
