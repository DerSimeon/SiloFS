-- V1__init.sql — initial schema for s3-server metadata
-- See docs/SCHEMA.md for the rationale behind each table.

CREATE TABLE IF NOT EXISTS access_keys (
    access_key_id      TEXT PRIMARY KEY,
    secret_access_key  TEXT NOT NULL,
    description        TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS buckets (
    name            TEXT PRIMARY KEY,
    region          TEXT NOT NULL,
    owner_id        TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_buckets_created_at ON buckets(created_at);

CREATE TABLE IF NOT EXISTS objects (
    bucket             TEXT NOT NULL REFERENCES buckets(name),
    object_key         TEXT NOT NULL,
    blob_path          TEXT NOT NULL,
    blob_sha256        BYTEA NOT NULL,
    etag               TEXT NOT NULL,
    size_bytes         BIGINT NOT NULL,
    content_type       TEXT NOT NULL,
    content_encoding   TEXT,
    content_language   TEXT,
    cache_control      TEXT,
    content_disposition TEXT,
    expires            TEXT,
    user_metadata      JSONB NOT NULL DEFAULT '{}'::jsonb,
    version_id         TEXT NOT NULL DEFAULT 'null',
    is_latest          BOOLEAN NOT NULL DEFAULT TRUE,
    storage_class      TEXT NOT NULL DEFAULT 'STANDARD',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at         TIMESTAMPTZ,
    PRIMARY KEY (bucket, object_key, version_id)
);
CREATE INDEX IF NOT EXISTS idx_objects_bucket_key ON objects(bucket, object_key);
CREATE INDEX IF NOT EXISTS idx_objects_bucket_created ON objects(bucket, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_objects_blob_sha256 ON objects(blob_sha256);

CREATE TABLE IF NOT EXISTS multipart_uploads (
    upload_id       TEXT PRIMARY KEY,
    bucket          TEXT NOT NULL REFERENCES buckets(name),
    object_key      TEXT NOT NULL,
    content_type    TEXT NOT NULL DEFAULT 'application/octet-stream',
    user_metadata   JSONB NOT NULL DEFAULT '{}'::jsonb,
    storage_class   TEXT NOT NULL DEFAULT 'STANDARD',
    state           TEXT NOT NULL DEFAULT 'INITIATED',
    initiated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    aborted_at      TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_mpu_bucket_key ON multipart_uploads(bucket, object_key);
CREATE INDEX IF NOT EXISTS idx_mpu_state_initiated ON multipart_uploads(state, initiated_at)
    WHERE state = 'INITIATED';

CREATE TABLE IF NOT EXISTS multipart_parts (
    upload_id       TEXT NOT NULL REFERENCES multipart_uploads(upload_id),
    part_number     INTEGER NOT NULL,
    blob_path       TEXT NOT NULL,
    blob_sha256     BYTEA NOT NULL,
    etag            TEXT NOT NULL,
    size_bytes      BIGINT NOT NULL,
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (upload_id, part_number)
);
CREATE INDEX IF NOT EXISTS idx_mpu_parts_blob ON multipart_parts(blob_sha256);
