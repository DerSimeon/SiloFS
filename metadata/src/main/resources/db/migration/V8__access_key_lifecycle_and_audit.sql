-- M7: access-key lifecycle, encrypted secret material, and audit events.

ALTER TABLE access_keys
    ADD COLUMN IF NOT EXISTS state TEXT NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS secret_ciphertext BYTEA,
    ADD COLUMN IF NOT EXISTS secret_nonce BYTEA,
    ADD COLUMN IF NOT EXISTS secret_key_id TEXT,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS disabled_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS rotated_at TIMESTAMPTZ;

ALTER TABLE access_keys
    ALTER COLUMN secret_access_key DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_access_keys_state ON access_keys(state)
    WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS audit_events (
    event_id        UUID PRIMARY KEY,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    request_id      TEXT,
    access_key_id   TEXT,
    operation       TEXT NOT NULL,
    bucket          TEXT,
    object_key      TEXT,
    status          INTEGER,
    latency_ms      BIGINT,
    source          TEXT NOT NULL DEFAULT 's3',
    detail          JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS idx_audit_events_occurred ON audit_events(occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_events_access_key ON audit_events(access_key_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_events_operation ON audit_events(operation, occurred_at DESC);
