-- M14: per-bucket versioning, lifecycle, and Object Lock metadata.

ALTER TABLE buckets
    ADD COLUMN IF NOT EXISTS versioning_status TEXT NOT NULL DEFAULT 'DISABLED',
    ADD COLUMN IF NOT EXISTS object_lock_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS default_retention_mode TEXT,
    ADD COLUMN IF NOT EXISTS default_retention_days INTEGER;

ALTER TABLE objects
    ADD COLUMN IF NOT EXISTS is_delete_marker BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS retention_mode TEXT,
    ADD COLUMN IF NOT EXISTS retain_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS legal_hold BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_objects_latest_visible
    ON objects(bucket, object_key, is_latest)
    WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS bucket_lifecycle_rules (
    bucket                         TEXT NOT NULL REFERENCES buckets(name),
    rule_id                        TEXT NOT NULL,
    enabled                        BOOLEAN NOT NULL DEFAULT TRUE,
    prefix                         TEXT,
    current_version_expiration_days INTEGER,
    noncurrent_version_expiration_days INTEGER,
    abort_incomplete_multipart_days INTEGER,
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (bucket, rule_id)
);
