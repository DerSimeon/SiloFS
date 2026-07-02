# M10 standalone CLI

M10 adds a standalone Go CLI named `silofs`. It is independent of the JVM
server artifact and can be built as a static Linux binary for installation into
a server `bin` directory.

## Build

```bash
cd cli
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "-s -w -X main.version=0.15.0" -o silofs .
```

Docker build smoke:

```bash
docker run --rm -v "$PWD/cli:/src" -w /src golang:1.25-bookworm \
  sh -c 'CGO_ENABLED=0 GOOS=linux GOARCH=amd64 /usr/local/go/bin/go build -trimpath -ldflags "-s -w -X main.version=0.15.0" -o /tmp/silofs . && /tmp/silofs version'
```

Release packages publish the CLI as a Debian package named `silofs`:

```bash
curl -1sLf "https://dl.cloudsmith.io/public/<owner>/<repo>/setup.deb.sh" | sudo -E bash
sudo apt update
sudo apt install silofs
silofs version
```

## Configuration precedence

1. Command flags.
2. `SILOS_*` or `SILOFS_*` environment variables.
3. Existing server-compatible `S3_*` environment variables.
4. Values loaded from `--from-env PATH`.
5. Stored CLI config.
6. Local development defaults.

`silofs configure` and `silofs login` prompt for endpoint, region, access key
ID, and secret access key, then write an env-style config file under the OS user
config directory. Use `--config PATH` to choose a different file. The same
commands are also available under `silofs admin`. The config file may contain
plaintext secrets and is written with restrictive file permissions where the OS
supports them.

`--from-env PATH` reads server-style env files such as `/opt/silofs/.env` for a
single command. It is useful for local admin commands on a deployed host without
copying database credentials into the user config file.

Common variables:

| CLI flag | Canonical env | Compatible env |
|----------|---------------|----------------|
| `--endpoint` | `SILOS_ENDPOINT` | `SILOFS_ENDPOINT`, `S3_ENDPOINT` |
| `--region` | `SILOS_REGION` | `SILOFS_REGION`, `S3_REGION` |
| `--access-key-id` | `SILOS_ACCESS_KEY_ID` | `SILOFS_ACCESS_KEY_ID`, `S3_ACCESS_KEY_ID` |
| `--secret-access-key` | `SILOS_SECRET_ACCESS_KEY` | `SILOFS_SECRET_ACCESS_KEY`, `S3_SECRET_ACCESS_KEY` |
| `--db-url` | `SILOS_DB_URL` | `SILOFS_DB_URL`, `S3_DB_URL` |
| `--db-user` | `SILOS_DB_USER` | `SILOFS_DB_USER`, `S3_DB_USER` |
| `--db-password` | `SILOS_DB_PASSWORD` | `SILOFS_DB_PASSWORD`, `S3_DB_PASSWORD` |
| `--data-dir` | `SILOS_DATA_DIR` | `SILOFS_DATA_DIR`, `S3_DATA_DIR` |
| `--object-encryption-master-key` | `SILOS_OBJECT_ENCRYPTION_MASTER_KEY` | `SILOFS_OBJECT_ENCRYPTION_MASTER_KEY`, `S3_OBJECT_ENCRYPTION_MASTER_KEY` |

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
- `silofs configure` / `silofs login`

The CLI uses path-style addressing with an explicit endpoint, matching the
declared compatibility envelope.

## Admin commands

- `silofs admin inspect buckets`
- `silofs admin configure` / `silofs admin login`
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
- `silofs admin grant add --access-key-id ID --bucket BUCKET --permission READ|WRITE|ADMIN`
- `silofs admin grant list [--access-key-id ID] [--bucket BUCKET]`
- `silofs admin grant remove --access-key-id ID --bucket BUCKET --permission READ|WRITE|ADMIN`

Admin commands connect directly to PostgreSQL and the configured blob directory.
No HTTP admin API is added in M10.

Grant commands manage the M13 bucket-scoped authorization model. Permissions are
`READ`, `WRITE`, and `ADMIN`; the wildcard bucket `*` grants that permission
across all buckets. Full IAM JSON, S3 ACL APIs, and bucket policies remain
unsupported.

## Safety notes

Secrets and presigned signatures are redacted from returned errors. Mutating
repair and GC are intentionally not exposed; only dry-run reports are supported.
Human-readable success/error status is printed to stderr with color when the
terminal supports it. Use `--quiet` to suppress status lines and `--no-color` to
disable color.

Access-key creation, rotation, and reencrypt require
`SILOS_ACCESS_KEY_SECRET_ENCRYPTION_KEY` or
`S3_ACCESS_KEY_SECRET_ENCRYPTION_KEY` to be set to a base64 32-byte AES-GCM key.
The generated raw secret is printed once; the database stores only encrypted
secret material.

Encrypted object consistency verification decrypts blob headers and ciphertext
when `SILOS_OBJECT_ENCRYPTION_MASTER_KEY` or
`S3_OBJECT_ENCRYPTION_MASTER_KEY` is configured. Missing or wrong keys fail
clearly instead of silently treating encrypted blobs as plaintext.
