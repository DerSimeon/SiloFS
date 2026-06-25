#!/usr/bin/env sh
set -eu

BACKUP_DIR="${1:-}"
DATA_DIR="${S3_DATA_DIR:-/var/lib/silofs/data}"
PG_RESTORE="${PG_RESTORE:-pg_restore}"

if [ -z "$BACKUP_DIR" ]; then
  echo "usage: silofs-restore.sh BACKUP_DIR" >&2
  exit 2
fi

if [ -z "${SILOFS_PG_URI:-}" ]; then
  echo "SILOFS_PG_URI is required, for example postgres://user:pass@host:5432/db" >&2
  exit 2
fi

if [ ! -f "$BACKUP_DIR/metadata.dump" ]; then
  echo "metadata dump not found: $BACKUP_DIR/metadata.dump" >&2
  exit 2
fi

"$PG_RESTORE" --clean --if-exists --no-owner --dbname="$SILOFS_PG_URI" "$BACKUP_DIR/metadata.dump"

mkdir -p "$DATA_DIR/objects" "$DATA_DIR/.quarantine"
if command -v rsync >/dev/null 2>&1; then
  rsync -a "$BACKUP_DIR/blobs/objects/" "$DATA_DIR/objects/"
  if [ -d "$BACKUP_DIR/blobs/.quarantine" ]; then
    rsync -a "$BACKUP_DIR/blobs/.quarantine/" "$DATA_DIR/.quarantine/"
  fi
else
  cp -R "$BACKUP_DIR/blobs/objects/." "$DATA_DIR/objects/"
  if [ -d "$BACKUP_DIR/blobs/.quarantine" ]; then
    cp -R "$BACKUP_DIR/blobs/.quarantine/." "$DATA_DIR/.quarantine/"
  fi
fi

echo "restore=ok"
