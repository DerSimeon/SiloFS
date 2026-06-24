-- V5__completing_at.sql — gap #4: track when an upload entered COMPLETING
--
-- Recovery of stuck COMPLETING uploads used initiated_at, which is wrong
-- because an upload can be initiated long before it enters COMPLETING.
-- This column records the actual transition time so recovery can use it.

ALTER TABLE multipart_uploads
    ADD COLUMN IF NOT EXISTS completing_at TIMESTAMPTZ;
