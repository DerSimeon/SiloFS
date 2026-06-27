# M10 standalone CLI

M10 adds a standalone Go CLI named `silofs`. It is independent of the JVM
server artifact and can be built as a static Linux binary for installation into
a server `bin` directory.

## Build

```bash
cd cli
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o silofs .
```

Docker build smoke:

```bash
docker run --rm -v "$PWD/cli:/src" -w /src golang:1.23.5-bookworm \
  sh -c 'CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o /tmp/silofs . && /tmp/silofs version'
```

## Configuration precedence

1. Command flags.
2. `SILOS_*` environment variables.
3. Existing server-compatible `S3_*` environment variables.
4. Local development defaults.

Common variables:

| CLI flag | `SILOS_*` | `S3_*` |
|----------|-----------|--------|
| `--endpoint` | `SILOS_ENDPOINT` | `S3_ENDPOINT` |
| `--region` | `SILOS_REGION` | `S3_REGION` |
| `--access-key-id` | `SILOS_ACCESS_KEY_ID` | `S3_ACCESS_KEY_ID` |
| `--secret-access-key` | `SILOS_SECRET_ACCESS_KEY` | `S3_SECRET_ACCESS_KEY` |
| `--db-url` | `SILOS_DB_URL` | `S3_DB_URL` |
| `--db-user` | `SILOS_DB_USER` | `S3_DB_USER` |
| `--db-password` | `SILOS_DB_PASSWORD` | `S3_DB_PASSWORD` |
| `--data-dir` | `SILOS_DATA_DIR` | `S3_DATA_DIR` |
| `--object-encryption-master-key` | `SILOS_OBJECT_ENCRYPTION_MASTER_KEY` | `S3_OBJECT_ENCRYPTION_MASTER_KEY` |

## S3 commands

- `silofs mb s3://bucket`
- `silofs rb s3://bucket`
- `silofs ls [s3://bucket[/prefix]]`
- `silofs stat s3://bucket[/key]`
- `silofs cp SOURCE DEST`
- `silofs cat s3://bucket/key [--range bytes=0-99]`
- `silofs rm s3://bucket/key`
- `silofs presign get s3://bucket/key [--expires 15m]`
- `silofs presign put s3://bucket/key [--expires 15m]`

The CLI uses path-style addressing with an explicit endpoint, matching the
declared compatibility envelope.

## Admin commands

- `silofs admin inspect buckets`
- `silofs admin inspect objects --bucket BUCKET`
- `silofs admin inspect multipart [--bucket BUCKET]`
- `silofs admin inspect blob-refs --sha256 SHA256`
- `silofs admin storage usage`
- `silofs admin check-blobs`
- `silofs admin backup verify --manifest PATH`
- `silofs admin repair --dry-run`
- `silofs admin gc --dry-run`
- `silofs admin migrate`
- `silofs admin access-key create [--access-key-id ID] [--description TEXT]`
- `silofs admin access-key list`
- `silofs admin access-key disable ID`
- `silofs admin access-key enable ID`
- `silofs admin access-key rotate ID`
- `silofs admin access-key delete ID`
- `silofs admin access-key reencrypt`

Admin commands connect directly to PostgreSQL and the configured blob directory.
No HTTP admin API is added in M10.

## Safety notes

Secrets and presigned signatures are redacted from returned errors. Mutating
repair and GC are intentionally not exposed; only dry-run reports are supported.

Encrypted access-key secret creation and rotation are compatible with the server
when `SILOS_ACCESS_KEY_SECRET_ENCRYPTION_KEY` or
`S3_ACCESS_KEY_SECRET_ENCRYPTION_KEY` is set to a base64 32-byte AES-GCM key.

Encrypted object consistency verification decrypts blob headers and ciphertext
when `SILOS_OBJECT_ENCRYPTION_MASTER_KEY` or
`S3_OBJECT_ENCRYPTION_MASTER_KEY` is configured. Missing or wrong keys fail
clearly instead of silently treating encrypted blobs as plaintext.
