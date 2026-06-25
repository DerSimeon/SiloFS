# s3-server — single-node S3-compatible object storage (Kotlin / Ktor)

A from-scratch, production-leaning S3-compatible storage server written in
Kotlin with Ktor, coroutines, PostgreSQL for metadata, and a local filesystem
for blob storage. Designed to be testable against the real AWS SDK for Java v2,
boto3, and the AWS CLI.

> **Status**: Milestones 1 through 3.1 delivered. See [docs/MILESTONES.md](docs/MILESTONES.md)
> for the roadmap and [docs/S3_FEATURES.md](docs/S3_FEATURES.md) for the
> supported/unsupported matrix.

## Quick start (Docker Compose)

```bash
cd /home/z/my-project/s3-server
docker compose up --build
```

This boots Postgres 16 and the s3-server on `http://localhost:8080`. The server
applies Flyway migrations on startup. Default credentials:

```
Access key: AKIAIOSFODNN7EXAMPLE
Secret key: wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
Region:     us-east-1
```

## Quick start (local Gradle run)

You need JDK 21 and Docker (for Postgres). Boot a Postgres on `:5432`:

```bash
docker run -d --name s3pg -p 5432:5432 \
  -e POSTGRES_DB=s3server -e POSTGRES_USER=s3server -e POSTGRES_PASSWORD=s3server \
  postgres:16-alpine
```

Then run the server:

```bash
cd /home/z/my-project/s3-server
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
s3 = boto3.client(
    "s3",
    endpoint_url="http://localhost:8080",
    aws_access_key_id="AKIAIOSFODNN7EXAMPLE",
    aws_secret_access_key="wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
    region_name="us-east-1",
)
s3.create_bucket(Bucket="boto")
s3.put_object(Bucket="boto", Key="k", Body=b"hello")
print(s3.get_object(Bucket="boto", Key="k")["Body"].read())
```

## Supported S3 operations (M1–M3.1)

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
s3-server/
  common/           — pure JVM: S3 errors, XML, names, time, ranges, ETag, checksums
  auth/             — AWS SigV4 verifier + Ktor plugin + presigned URL generator
  metadata/         — PostgreSQL schema (V1–V4) + JDBC repository (HikariCP + Flyway)
  blob/             — FsBlobStore with crash-safe commit + RecoveryJob
  server/           — Ktor app, routes, StatusPages XML errors, multipart handlers, main()
  integration-test/ — AWS SDK for Java v2 + S3Presigner + Testcontainers
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
./gradlew jacocoTestReport            # aggregate coverage report
./gradlew jacocoCoverageVerification  # enforce 90% line / 85% branch
```

Coverage reports are written to `*/build/reports/jacoco/test/html/index.html`.

## Configuration

All settings are env vars; no config file is required.

| Variable                              | Default                                | Description                              |
|---------------------------------------|----------------------------------------|------------------------------------------|
| `S3_BIND_HOST`                        | `0.0.0.0`                              | HTTP bind address                        |
| `S3_BIND_PORT`                        | `8080`                                 | HTTP bind port                           |
| `S3_REGION`                           | `us-east-1`                            | AWS region reported by SigV4             |
| `S3_DATA_DIR`                         | `/var/lib/s3server/data`               | Blob storage root                        |
| `S3_DB_URL`                           | `jdbc:postgresql://localhost:5432/s3server` | Postgres JDBC URL                  |
| `S3_DB_USER`                          | `s3server`                             | Postgres username                        |
| `S3_DB_PASSWORD`                      | `s3server`                             | Postgres password                        |
| `S3_ACCESS_KEY_ID`                    | `AKIAIOSFODNN7EXAMPLE`                 | SigV4 access key (seeded into `access_keys`) |
| `S3_SECRET_ACCESS_KEY`                | `wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY` | SigV4 secret key                    |
| `S3_SIGV4_MAX_CLOCK_SKEW_SECONDS`     | `900`                                  | Max allowed clock skew for SigV4 (±15 min) |
| `S3_RECOVERY_ENABLED`                 | `true`                                 | Start the recovery sweep coroutine       |
| `S3_RECOVERY_TMP_MAX_AGE_SECONDS`     | `3600`                                 | Orphan temp files older than this are deleted |
| `S3_RECOVERY_MULTIPART_MAX_AGE_SECONDS` | `86400`                              | Stale multipart uploads older than this are aborted |
| `S3_RECOVERY_SWEEP_INTERVAL_SECONDS`  | `60`                                   | How often the sweep runs                 |
| `S3_RECOVERY_BLOB_SWEEP_INTERVAL_SECONDS` | `600`                              | How often unreferenced blobs are swept   |

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

## Known limitations (post-M3.1)

* No streaming SigV4 (`aws-chunked` payloads) — compatibility milestone
* No virtual-host style addressing — compatibility milestone
* No server-side encryption (SSE-S3/SSE-C) — M7
* No object versioning, ACLs, IAM, lifecycle, replication, or erasure coding — out of scope

See [docs/MILESTONES.md](docs/MILESTONES.md) for the full M4–M7 roadmap.

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
* **Streaming**: `respondOutputStream` for downloads, `receiveStream` for
  uploads.
* **Idempotent recovery**: every sweep step is safe to crash mid-execution.
* **Admin inspection**: `s3server admin check-blobs` reports missing referenced
  blobs and orphan content blobs without mutating data; `s3server admin
  recover-once` runs one recovery sweep and exits.

## Next steps

1. Run `./gradlew :integration-test:test` to confirm the SDK smoke suite is
   green.
2. Continue M4 durability work: expand crash/race coverage, run repeated
   recovery tests, and keep docs aligned with [docs/MILESTONES.md](docs/MILESTONES.md).
