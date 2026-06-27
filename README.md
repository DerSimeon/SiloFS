# silofs — single-node S3-compatible object storage (Kotlin / Ktor)

A from-scratch, production-leaning S3-compatible storage server written in
Kotlin with Ktor, coroutines, PostgreSQL for metadata, and a local filesystem
for blob storage. Designed to be testable against the M6 Core 5 path-style
client matrix: AWS SDK Java v2, AWS CLI, boto3, AWS SDK JavaScript v3, and AWS
SDK Go v2.

> **Status**: Milestones 1 through 6 delivered for the single-node envelope. See [docs/MILESTONES.md](docs/MILESTONES.md)
> for the roadmap and [docs/S3_FEATURES.md](docs/S3_FEATURES.md) for the
> supported/unsupported matrix.

## Quick start (Docker Compose)

```bash
cd /home/z/my-project/silofs
docker compose up --build
```

This boots Postgres 16 and silofs on `http://localhost:8080`. The server
applies Flyway migrations on startup. Default credentials:

```
Access key: AKIAIOSFODNN7EXAMPLE
Secret key: wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
Region:     us-east-1
```

## Quick start (local Gradle run)

You need JDK 21 and Docker (for Postgres). Boot a Postgres on `:5432`:

```bash
docker run -d --name silofspg -p 5432:5432 \
  -e POSTGRES_DB=silofs -e POSTGRES_USER=silofs -e POSTGRES_PASSWORD=silofs \
  postgres:16-alpine
```

Then run the server:

```bash
cd /home/z/my-project/silofs
./gradlew :server:run
```

## Smoke test with the AWS CLI

```bash
aws --endpoint-url http://localhost:8080 \
    --region us-east-1 \
    s3 mb s3://my-bucket

echo "hello, s3!" > hello.txt
aws --endpoint-url http://localhost:8080 \
    s3 cp hello.txt s3://my-bucket/hello.txt

aws --endpoint-url http://localhost:8080 \
    s3 ls s3://my-bucket/

aws --endpoint-url http://localhost:8080 \
    s3 rm s3://my-bucket/hello.txt
```

Set the credentials in `~/.aws/credentials`:

```ini
[default]
aws_access_key_id = AKIAIOSFODNN7EXAMPLE
aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
```

## Smoke test with boto3

```python
import boto3
from botocore.config import Config

s3 = boto3.client(
    "s3",
    endpoint_url="http://localhost:8080",
    aws_access_key_id="AKIAIOSFODNN7EXAMPLE",
    aws_secret_access_key="wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
    region_name="us-east-1",
    config=Config(s3={"addressing_style": "path"}, signature_version="s3v4"),
)
s3.create_bucket(Bucket="boto")
s3.put_object(Bucket="boto", Key="k", Body=b"hello")
print(s3.get_object(Bucket="boto", Key="k")["Body"].read())
```

## Client configuration examples

M6 support requires path-style addressing with an explicit endpoint.

AWS CLI:

```bash
aws configure set default.s3.addressing_style path
aws --endpoint-url http://localhost:8080 --region us-east-1 s3api list-buckets
```

boto3:

```python
from botocore.config import Config

s3 = boto3.client(
    "s3",
    endpoint_url="http://localhost:8080",
    region_name="us-east-1",
    config=Config(s3={"addressing_style": "path"}, signature_version="s3v4"),
)
```

AWS SDK JavaScript v3:

```javascript
import { S3Client } from "@aws-sdk/client-s3";

const s3 = new S3Client({
  region: "us-east-1",
  endpoint: "http://localhost:8080",
  forcePathStyle: true,
  credentials: {
    accessKeyId: "AKIAIOSFODNN7EXAMPLE",
    secretAccessKey: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
  },
});
```

AWS SDK Go v2:

```go
cfg, _ := config.LoadDefaultConfig(ctx, config.WithRegion("us-east-1"))
s3c := s3.NewFromConfig(cfg, func(o *s3.Options) {
    o.BaseEndpoint = aws.String("http://localhost:8080")
    o.UsePathStyle = true
})
```

## Supported S3 operations

| Operation | Milestone | Notes |
|-----------|-----------|-------|
| `CreateBucket` | M1 | Path-style. `x-amz-bucket-region` echoed. |
| `HeadBucket` | M1 | 200 / 404. |
| `DeleteBucket` | M2.1 | Empty-check, soft-delete. |
| `ListBuckets` | M2.1 | `ListAllMyBucketsResult` XML. |
| `GetBucketLocation` | M2.1 | `LocationConstraint` XML. |
| `PutObject` | M1 | Single-shot, content-addressed, SHA-256, MD5, checksums. |
| `GetObject` | M1 | Full + `Range`, conditional (`If-Match`/`If-None-Match`). |
| `HeadObject` | M1 | Same headers as GET. |
| `DeleteObject` | M1 | Idempotent, 204. |
| `CopyObject` | M2.1 | Server-side, `COPY`/`REPLACE` directive, conditional. |
| `ListObjectsV2` | M1/M2.1 | Pagination, `prefix`, `delimiter`, `encoding-type=url`. |
| `CreateMultipartUpload` | M3 | Returns `UploadId`. |
| `UploadPart` | M3 | Fsync, content-addressed, re-upload overwrites. |
| `UploadPartCopy` | M3.1 | Copy from existing object with optional range. |
| `CompleteMultipartUpload` | M3 | Atomic concat, multipart ETag. |
| `AbortMultipartUpload` | M3 | Marks ABORTED, deletes parts. |
| `ListParts` | M3 | Part list XML. |
| `ListMultipartUploads` | M3.1 | In-progress uploads with prefix/delimiter. |
| Presigned GET/PUT | M3.1 | SigV4 over query string, expiry enforcement. |
| SigV4 auth | M1 | `AWS4-HMAC-SHA256`, clock-skew ±15 min, constant-time compare. |

## Module layout

```
silofs/
  common/           — pure JVM: S3 errors, XML, names, time, ranges, ETag, checksums
  auth/             — AWS SigV4 verifier + Ktor plugin + presigned URL generator
  metadata/         — PostgreSQL schema (V1–V4) + JDBC repository (HikariCP + Flyway)
  blob/             — FsBlobStore with crash-safe commit + RecoveryJob
  server/           — Ktor app, routes, StatusPages XML errors, multipart handlers, main()
  integration-test/ — AWS SDK for Java v2 + S3Presigner + Testcontainers
  compatibility-test/ — Docker-backed Core 5 path-style client matrix
  docs/             — ARCHITECTURE, MILESTONES, SCHEMA, ROUTES, S3_FEATURES
  scripts/          — boto3 multipart test
  docker-compose.yml
  Dockerfile
```

## Build & test commands

```bash
./gradlew build                       # compile + unit tests + detekt + ktlint
./gradlew check                       # above + 90% coverage verification
./gradlew :integration-test:test      # AWS SDK + Testcontainers suite
./gradlew :compatibility-test:test    # M6 Core 5 Docker-backed matrix
./gradlew jacocoTestReport            # aggregate coverage report
./gradlew jacocoCoverageVerification  # enforce 90% line / 85% branch
```

Coverage reports are written to `*/build/reports/jacoco/test/html/index.html`.

Standalone CLI:

```bash
cd cli
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o silofs .
./silofs version
./silofs --endpoint http://127.0.0.1:8080 mb s3://photos
./silofs cp ./image.jpg s3://photos/image.jpg
./silofs admin inspect buckets --db-url jdbc:postgresql://localhost:5432/silofs
```

## Backup and restore

M8 backup support is offline/quiesced. Stop silofs or block writes before
running the scripts.

```bash
export SILOFS_PG_URI='postgres://silofs:silofs@localhost:5432/silofs'
export S3_DATA_DIR=/var/lib/silofs/data
scripts/silofs-backup.sh
```

Restore with:

```bash
export SILOFS_PG_URI='postgres://silofs:silofs@localhost:5432/silofs'
export S3_DATA_DIR=/var/lib/silofs/data
scripts/silofs-restore.sh /path/to/backup
silofs admin backup verify --manifest /path/to/backup/manifest.json
```

See [docs/OPERATIONS_M8.md](docs/OPERATIONS_M8.md).

## Configuration

All settings are env vars; no config file is required.

| Variable                              | Default                                | Description                              |
|---------------------------------------|----------------------------------------|------------------------------------------|
| `S3_BIND_HOST`                        | `0.0.0.0`                              | HTTP bind address                        |
| `S3_BIND_PORT`                        | `8080`                                 | HTTP bind port                           |
| `S3_REGION`                           | `us-east-1`                            | AWS region reported by SigV4             |
| `S3_DATA_DIR`                         | `/var/lib/silofs/data`                 | Blob storage root                        |
| `S3_DB_URL`                           | `jdbc:postgresql://localhost:5432/silofs` | Postgres JDBC URL                    |
| `S3_DB_USER`                          | `silofs`                               | Postgres username                        |
| `S3_DB_PASSWORD`                      | `silofs`                               | Postgres password                        |
| `S3_ACCESS_KEY_ID`                    | `AKIAIOSFODNN7EXAMPLE`                 | SigV4 access key (seeded into `access_keys`) |
| `S3_SECRET_ACCESS_KEY`                | `wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY` | SigV4 secret key                    |
| `S3_ACCESS_KEY_SECRET_ENCRYPTION_KEY` | unset                                  | Base64 32-byte AES-GCM key for encrypting access-key secrets |
| `S3_REQUIRE_ENCRYPTED_SECRETS`        | `false`                                | Reject plaintext access-key secret rows when true |
| `S3_OBJECT_ENCRYPTION_MODE`           | `disabled`                             | `disabled` or `sse-s3` for transparent object encryption |
| `S3_OBJECT_ENCRYPTION_MASTER_KEY`     | unset                                  | Base64 32-byte AES-GCM key required when object encryption is `sse-s3` |
| `S3_REQUIRE_OBJECT_ENCRYPTION`        | `false`                                | Require `S3_OBJECT_ENCRYPTION_MODE=sse-s3` at startup when true |
| `S3_RATE_LIMIT_PER_ACCESS_KEY_RPS`    | `0`                                    | Per-access-key request rate limit; 0 disables |
| `S3_RATE_LIMIT_PER_ACCESS_KEY_BURST`  | `64`                                   | Per-access-key token bucket burst size |
| `S3_CORS_ALLOWED_ORIGINS`             | unset                                  | Comma-separated allowed origins; CORS disabled when unset |
| `S3_SIGV4_MAX_CLOCK_SKEW_SECONDS`     | `900`                                  | Max allowed clock skew for SigV4 (±15 min) |
| `S3_RECOVERY_ENABLED`                 | `true`                                 | Start the recovery sweep coroutine       |
| `S3_RECOVERY_TMP_MAX_AGE_SECONDS`     | `3600`                                 | Orphan temp files older than this are deleted |
| `S3_RECOVERY_MULTIPART_MAX_AGE_SECONDS` | `86400`                              | Stale multipart uploads older than this are aborted |
| `S3_RECOVERY_SWEEP_INTERVAL_SECONDS`  | `60`                                   | How often the sweep runs                 |
| `S3_RECOVERY_BLOB_SWEEP_INTERVAL_SECONDS` | `600`                              | How often unreferenced blobs are swept   |
| `S3_MAX_CONCURRENT_UPLOADS`          | `64`                                   | Global concurrent PutObject/CopyObject/UploadPart/UploadPartCopy limit |
| `S3_MAX_CONCURRENT_MULTIPART_COMPLETIONS` | `8`                              | Global concurrent CompleteMultipartUpload limit |
| `S3_COMPLETE_XML_MAX_BYTES`          | `1048576`                              | Max CompleteMultipartUpload XML body size before parsing |
| `S3_MIN_FREE_DISK_BYTES`             | `0`                                    | Minimum usable bytes required for `/readyz` |
| `S3_SHUTDOWN_QUIET_PERIOD_MS`        | `1000`                                 | Ktor shutdown quiet period before force timeout |
| `S3_SHUTDOWN_TIMEOUT_MS`             | `5000`                                 | Shutdown drain/force timeout             |

## Durability contract

When `PutObject` returns 200:

1. The blob bytes have been `fsync`'d to disk under
   `<data-dir>/objects/sha256[0:2]/sha256[2:4]/sha256` and the parent
   directories have been `fsync`'d.
2. The actual streamed byte count was verified against the declared
   `Content-Length`.
3. The metadata row has been committed in Postgres in a single transaction.
4. If the process crashes between (1) and (3), the blob is unreferenced; the
   recovery job will sweep it and the client received a 5xx, not a 200.

The same contract applies to `UploadPart` and `CompleteMultipartUpload`.

## Known limitations (post-M10 compatibility expansion)

* `aws-chunked` upload bodies are decoded for object and multipart-part uploads,
  but per-chunk SigV4 signatures are not verified yet.
* No DeleteObjects API. Some CLIs use this for wildcard or recursive cleanup.
* No virtual-host style addressing. Configure supported clients for path-style.
* SSE-S3 object encryption is supported when explicitly configured. SSE-C,
  SSE-KMS, and external KMS integration are unsupported.
* No object versioning, ACLs, IAM, lifecycle, replication, or erasure coding — out of scope

See [docs/COMPATIBILITY_M6.md](docs/COMPATIBILITY_M6.md) for the tested client matrix and [docs/MILESTONES.md](docs/MILESTONES.md) for the full M7-M9 roadmap.
The bounded production-candidate sign-off is documented in
[docs/PRODUCTION_READINESS_M9.md](docs/PRODUCTION_READINESS_M9.md).
The standalone CLI is documented in [docs/CLI_M10.md](docs/CLI_M10.md).

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Key points:

* **Crash safety**: every PutObject streams to a temp file on the same
  filesystem as the final blob, computes SHA-256/MD5 while streaming,
  `fsync`s, then atomically renames to a content-addressed path
  (`objects/sha256[0:2]/sha256[2:4]/sha256`), and only then writes the
  metadata row in a single transaction. A crash between the rename and the DB
  insert leaves an unreferenced blob that the recovery sweep will GC.
* **Content-Length enforcement**: the actual byte count streamed to disk is
  compared against the declared `Content-Length`; a mismatch produces
  `IncompleteBody` (400) and the temp file is aborted.
* **Multipart completion**: `CompleteMultipartUpload` transitions the upload
  to a `COMPLETING` state before concatenating parts, preventing concurrent
  part uploads from racing with the completion. The final object row +
  part-row deletion + `COMPLETED` state change happen in one transaction.
* **Concurrency**: Ktor Netty event loop with `Dispatchers.IO` for blocking
  calls. Large uploads and downloads never materialise fully in memory.
* **Streaming**: `respondOutputStream` for downloads; uploads use a request body
  stream, with aws-chunked framing decoded before blob ingestion when required
  by compatible clients.
* **Idempotent recovery**: every sweep step is safe to crash mid-execution.
* **Admin inspection**: `silofs admin check-blobs` reports missing referenced
  blobs and orphan content blobs without mutating data; `silofs admin
  recover-once` runs one recovery sweep and exits.
* **Operational limits**: global upload and multipart-completion limiters
  return S3 `SlowDown` (503) when saturated. `/readyz` checks DB, data-dir
  writeability, and free disk. `/metricsz` exports request, limiter, DB pool,
  recovery, and blob-store counters.
* **Compatibility**: Core 5 plus the M10 extended path-style matrix are tested
  in `:compatibility-test`; virtual-host addressing and unsupported client
  behaviors are recorded as detection rows.
* **Security**: access keys are metadata-backed, lifecycle-managed, optionally
  encrypted at rest, rate-limitable per key, and audited for mutating actions.
  Object blobs can be encrypted with local SSE-S3 AES-GCM. TLS should terminate
  outside the Ktor process.

## Next steps

1. Build the standalone M10 CLI for mc-like operator workflows.
2. Keep expanding compatibility evidence only when it tightens the declared
   support envelope.
