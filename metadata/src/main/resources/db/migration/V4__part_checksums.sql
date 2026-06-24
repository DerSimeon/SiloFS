-- V4__part_checksums.sql — M3.1: persist per-part x-amz-checksum-* values
--
-- Each part can carry a checksum (CRC32, CRC32C, SHA1, SHA256). We persist
-- them so CompleteMultipartUpload can verify the client-supplied part list
-- and so the final object can echo a composite checksum.

ALTER TABLE multipart_parts
    ADD COLUMN IF NOT EXISTS checksum_crc32  TEXT,
    ADD COLUMN IF NOT EXISTS checksum_crc32c TEXT,
    ADD COLUMN IF NOT EXISTS checksum_sha1   TEXT,
    ADD COLUMN IF NOT EXISTS checksum_sha256 TEXT;
