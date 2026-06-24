# PostgreSQL schema

All migrations live in `metadata/src/main/resources/db/migration/V*.sql` and are
applied by Flyway on boot. The schema is normalised around three core tables:
`buckets`, `objects`, and `multipart_uploads` / `multipart_parts`.

## V1__init.sql

```sql
-- Credentials for SigV4. Single-row in M1, multi-row later.
CREATE TABLE access_keys (
    access_key_id   TEXT PRIMARY KEY,
    secret_access_key TEXT NOT NULL,
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE buckets (
    name            TEXT PRIMARY KEY,
    region          TEXT NOT NULL,
    owner_id        TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);
CREATE INDEX idx_buckets_created_at ON buckets(created_at);

CREATE TABLE objects (
    bucket          TEXT NOT NULL REFERENCES buckets(name),
    object_key      TEXT NOT NULL,
    blob_path       TEXT NOT NULL,
    blob_sha256     BYTEA NOT NULL,
    etag            TEXT NOT NULL,
    size_bytes      BIGINT NOT NULL,
    content_type    TEXT NOT NULL,
    content_encoding TEXT,
    content_language TEXT,
    cache_control   TEXT,
    content_disposition TEXT,
    expires         TEXT,
    user_metadata   JSONB NOT NULL DEFAULT '{}'::jsonb,
    version_id      TEXT NOT NULL DEFAULT 'null',
    is_latest       BOOLEAN NOT NULL DEFAULT TRUE,
    storage_class   TEXT NOT NULL DEFAULT 'STANDARD',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    PRIMARY KEY (bucket, object_key, version_id)
);
CREATE INDEX idx_objects_bucket_key ON objects(bucket, object_key);
CREATE INDEX idx_objects_bucket_created ON objects(bucket, created_at DESC);
CREATE INDEX idx_objects_blob_sha256 ON objects(blob_sha256);

CREATE TABLE multipart_uploads (
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
CREATE INDEX idx_mpu_bucket_key ON multipart_uploads(bucket, object_key);
CREATE INDEX idx_mpu_state_initiated ON multipart_uploads(state, initiated_at)
    WHERE state = 'INITIATED';

CREATE TABLE multipart_parts (
    upload_id       TEXT NOT NULL REFERENCES multipart_uploads(upload_id),
    part_number     INTEGER NOT NULL,
    blob_path       TEXT NOT NULL,
    blob_sha256     BYTEA NOT NULL,
    etag            TEXT NOT NULL,
    size_bytes      BIGINT NOT NULL,
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (upload_id, part_number)
);
CREATE INDEX idx_mpu_parts_blob ON multipart_parts(blob_sha256);
```

## Notes on the design

* `objects` is keyed on `(bucket, object_key, version_id)` even though M1 does
  not expose versioning. The `version_id` column defaults to the literal string
  `'null'` (which is also the value AWS uses for non-versioned objects). This
  makes adding public versioning in a later milestone a schema-free change.
* `blob_sha256` is `BYTEA` (32 bytes) rather than hex text — half the index
  size, and faster to compare.
* `user_metadata` is a `JSONB` column. The S3 API only stores string values, so
  we coerce on read.
* `multipart_uploads.state` is a plain `TEXT` enum rather than a Postgres enum
  so we can add new states without a migration lock.
* There are no `acl` or `policy` tables — out of scope by design.

## Index strategy

* `(bucket, object_key)` is the hot path for `HeadObject` / `GetObject`.
* `(bucket, created_at DESC)` supports `ListObjectsV2` ordering.
* The partial index on `multipart_uploads(state, initiated_at) WHERE state = 'INITIATED'`
  keeps the recovery sweep cheap.

## V7__blob_write_intents.sql

```sql
CREATE TABLE IF NOT EXISTS blob_write_intents (
    intent_id        TEXT PRIMARY KEY,
    blob_sha256_hex  TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`blob_write_intents` protects the crash window after a blob is published to the
filesystem but before object or multipart-part metadata commits. Blob GC treats
active intents as references and expires old intents before reclaiming truly
orphaned blobs.
