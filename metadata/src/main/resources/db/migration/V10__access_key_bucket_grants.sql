-- M13: bucket-scoped access-key grants.

CREATE TABLE IF NOT EXISTS access_key_bucket_grants (
    access_key_id TEXT NOT NULL REFERENCES access_keys(access_key_id),
    bucket        TEXT NOT NULL,
    permission    TEXT NOT NULL CHECK (permission IN ('READ', 'WRITE', 'ADMIN')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (access_key_id, bucket, permission)
);

CREATE INDEX IF NOT EXISTS idx_access_key_bucket_grants_bucket
    ON access_key_bucket_grants(bucket, permission);
