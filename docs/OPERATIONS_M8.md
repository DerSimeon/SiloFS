# M8 operations: backup, restore, and admin tooling

M8 supports **offline/quiesced** backup and restore. Stop silofs, or otherwise
block all writes, before taking a backup. Online write-consistent backup is not
claimed yet.

## Backup

Prerequisites on the operator host:

- `pg_dump`
- `rsync` preferred, `cp` fallback
- read access to `S3_DATA_DIR`

```bash
export SILOFS_PG_URI='postgres://silofs:silofs@localhost:5432/silofs'
export S3_DATA_DIR=/var/lib/silofs/data
export SILOFS_BACKUP_DIR=/var/backups/silofs

scripts/silofs-backup.sh
```

The script writes:

- `metadata.dump`: custom-format PostgreSQL dump
- `blobs/objects`: content-addressed object blobs, copied incrementally
- `blobs/.quarantine`: quarantined blobs if present
- `manifest.json`: backup metadata for verification

## Restore

Restore into an empty or intentionally replaceable metadata database and data
directory. The restore script uses `pg_restore --clean --if-exists`.

```bash
export SILOFS_PG_URI='postgres://silofs:silofs@localhost:5432/silofs'
export S3_DATA_DIR=/var/lib/silofs/data

scripts/silofs-restore.sh /var/backups/silofs/20260625T120000Z
```

After restore, run:

```bash
silofs admin backup verify --manifest /var/backups/silofs/20260625T120000Z/manifest.json
silofs admin check-blobs
```

Verification fails if any live metadata row references a missing blob. Orphan
and quarantined blobs are reported for operator review.

## Admin commands

Inspection:

```bash
silofs admin inspect buckets
silofs admin inspect objects --bucket BUCKET
silofs admin inspect multipart [--bucket BUCKET]
silofs admin inspect blob-refs --sha256 SHA256
silofs admin storage usage
```

Recovery and dry-runs:

```bash
silofs admin recover-once
silofs admin repair --dry-run
silofs admin gc --dry-run
silofs admin migrate
```

Access-key lifecycle:

```bash
silofs admin access-key create [--access-key-id ID] [--description TEXT]
silofs admin access-key list
silofs admin access-key disable ID
silofs admin access-key enable ID
silofs admin access-key rotate ID
silofs admin access-key delete ID
silofs admin access-key reencrypt
```

`create` and `rotate` print the secret exactly once. Do not store command output
in shared logs.

## Upgrade and rollback

- Run `silofs admin migrate` before starting a newly deployed server if you want
  migration failure to happen outside the service start path.
- Keep the previous server binary and database/blob backups until the new build
  has passed `admin backup verify`.
- PostgreSQL schema migrations are forward-only. Rollback means restoring the
  previous database dump and blob directory, then starting the previous binary.

## Disaster recovery limits

- M8 does not provide online snapshot isolation while writes continue.
- M8 does not replicate data off-node by itself; copy backup directories to
  independent storage.
- Metadata and blob backups must be kept together. Restoring only one side can
  create missing or orphan blob references.
