#!/usr/bin/env sh
set -eu

BACKUP_ROOT="${SILOFS_BACKUP_DIR:-./silofs-backups}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$BACKUP_ROOT/$STAMP"
DATA_DIR="${S3_DATA_DIR:-/var/lib/silofs/data}"
PG_DUMP="${PG_DUMP:-pg_dump}"

if [ -z "${SILOFS_PG_URI:-}" ]; then
  echo "SILOFS_PG_URI is required, for example postgres://user:pass@host:5432/db" >&2
  exit 2
fi

mkdir -p "$OUT/blobs"

"$PG_DUMP" --format=custom --file="$OUT/metadata.dump" "$SILOFS_PG_URI"

if command -v rsync >/dev/null 2>&1; then
  rsync -a --ignore-existing "$DATA_DIR/objects/" "$OUT/blobs/objects/"
  if [ -d "$DATA_DIR/.quarantine" ]; then
    rsync -a --ignore-existing "$DATA_DIR/.quarantine/" "$OUT/blobs/.quarantine/"
  fi
else
  mkdir -p "$OUT/blobs/objects"
  cp -R -n "$DATA_DIR/objects/." "$OUT/blobs/objects/"
  if [ -d "$DATA_DIR/.quarantine" ]; then
    mkdir -p "$OUT/blobs/.quarantine"
    cp -R -n "$DATA_DIR/.quarantine/." "$OUT/blobs/.quarantine/"
  fi
fi

OBJECT_BLOBS="$(find "$OUT/blobs/objects" -type f 2>/dev/null | wc -l | tr -d ' ')"
QUARANTINED_BLOBS="$(find "$OUT/blobs/.quarantine" -type f 2>/dev/null | wc -l | tr -d ' ')"

cat > "$OUT/manifest.json" <<EOF
{
  "format": "silofs-offline-backup-v1",
  "created_at": "$STAMP",
  "metadata_dump": "metadata.dump",
  "blob_root": "blobs",
  "source_data_dir": "$DATA_DIR",
  "object_blob_count": $OBJECT_BLOBS,
  "quarantined_blob_count": $QUARANTINED_BLOBS,
  "mode": "offline-quiesced"
}
EOF

echo "$OUT"
