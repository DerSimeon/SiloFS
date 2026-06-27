# M9 production readiness review

## Summary

Silofs is production-candidate for the declared single-node envelope below when
the full verification matrix passes in the target deployment environment. The
claim is intentionally narrow: one JVM server, one PostgreSQL metadata store,
one local filesystem blob store, no replication, no clustering, no consensus,
no erasure coding, no IAM/ACL policy engine, and no broad AWS S3 equivalence.

## Declared operating envelope

| Area | Declared limit |
|------|----------------|
| Node count | 1 server node |
| Metadata DB | PostgreSQL 16 |
| Filesystem | Local Linux filesystem with atomic rename and reliable fsync; ext4 or XFS recommended |
| Object count | 100,000 active objects before re-benchmarking |
| Total blob data | 1 TiB before re-benchmarking |
| Single PUT size | 5 GiB |
| Multipart object size | 50 GiB before re-benchmarking |
| Concurrent upload limit | `S3_MAX_CONCURRENT_UPLOADS`, default 64 |
| Concurrent completion limit | `S3_MAX_CONCURRENT_MULTIPART_COMPLETIONS`, default 8 |
| Compatibility claim | Core 5 path-style clients from `docs/COMPATIBILITY_M6.md` |
| Encryption claim | Local SSE-S3 AES-GCM only |

The load smoke test `S3ServerLoadSmokeTest` exercises 200 concurrent 4 KiB
PUT/GET roundtrips through the AWS SDK Java v2. Larger performance targets must
be re-measured on the target hardware before increasing the limits above.

## Sign-off evidence

Durability and recovery:

- Successful object and multipart writes publish fsynced blobs before metadata
  commit and use DB-backed write intents to protect the rename-to-commit window.
- Crash/failpoint tests cover temp write, fsync, rename, DB commit, overwrite,
  delete, multipart part upload, multipart completion, recovery, quarantine,
  and final deletion windows.
- GC only deletes through tombstone/quarantine/final-delete phases with DB
  reference rechecks.

Compatibility:

- M6 Core 5 path-style matrix covers AWS SDK Java v2, AWS CLI, boto3, AWS SDK
  JavaScript v3, and AWS SDK Go v2.
- Virtual-host addressing and streaming SigV4 remain unsupported and must stay
  documented for operators and users.

Security:

- Access keys are metadata-backed, lifecycle-managed, optionally encrypted, and
  rate-limitable.
- Mutating operations are audited without secret or presigned-signature leakage.
- CORS is disabled by default.
- TLS terminates outside the Ktor process.
- SSE-S3 encrypts final blob data; PostgreSQL metadata and transient temp files
  still require host, filesystem, or block-device controls according to the
  deployment threat model.

Backup and restore:

- Supported backups are offline/quiesced.
- Metadata uses `pg_dump`/`pg_restore`; blobs use content-addressed copy.
- `silofs admin backup verify` and `silofs admin check-blobs` validate missing,
  orphan, quarantined, corrupt, and encrypted blob state.
- Operators must escrow `S3_OBJECT_ENCRYPTION_MASTER_KEY` with encrypted backup
  sets.

## Monitoring and alerting

Alert on:

- `/readyz` failure.
- PostgreSQL connection pool exhaustion or sustained waiters.
- non-zero missing or corrupt blobs from scheduled `admin check-blobs`.
- repeated recovery sweep failures.
- sustained `SlowDown` responses.
- data directory free space below the configured floor.
- unexpected 5xx rate by S3 operation.

Track:

- request counts, latency histograms, request/response bytes.
- in-flight requests, uploads, and multipart completions.
- active multipart uploads.
- orphan temp files, quarantined blobs, blob disk bytes.
- recovery sweep counters and blob-store error counters.

## Failure response

| Failure | Immediate operator action |
|---------|---------------------------|
| Missing referenced blob | Stop writes, preserve DB/data dir, run `admin check-blobs`, restore from last verified backup |
| Corrupt encrypted blob | Stop writes, verify encryption key, restore affected blob/metadata from backup |
| Lost encryption key | Treat encrypted data as unrecoverable; restore from backup only if the key is recovered |
| PostgreSQL unavailable | Server should fail readiness; restore DB service before accepting writes |
| Disk full | Server should fail readiness if `S3_MIN_FREE_DISK_BYTES` is set; free space or expand volume |
| Repeated recovery failures | Stop server, preserve evidence, run `admin recover-once` after root cause is fixed |
| Suspected secret leakage | Disable or rotate affected access keys, inspect audit log, invalidate exposed clients |

## Upgrade and rollback

- Run `silofs admin migrate` before starting a new binary when possible.
- Keep the previous binary, metadata backup, blob backup, and encryption key
  until the new version passes `admin backup verify`.
- Flyway migrations are forward-only. Rollback means restoring the previous DB
  dump and blob directory, then starting the previous binary.
- Do not enable `S3_REQUIRE_OBJECT_ENCRYPTION=true` until all intended writers
  run with `S3_OBJECT_ENCRYPTION_MODE=sse-s3`.

## Unsupported features

- replication, clustering, consensus, and erasure coding.
- IAM, ACLs, bucket policies, Object Lock, and lifecycle policies.
- object versioning.
- SSE-C, SSE-KMS, and external KMS.
- streaming SigV4 / `aws-chunked`.
- virtual-host style addressing.
- online write-consistent backups.

## Required release verification

Run before claiming this report for a release candidate:

```powershell
.\gradlew :metadata:test :blob:test :server:test :integration-test:test :compatibility-test:test -x detekt
.\gradlew dockerBackedVerification -x detekt
.\gradlew productionFocusedVerification -x detekt
.\gradlew :integration-test:failpointCrashTest :integration-test:concurrencyTest -x detekt --rerun-tasks
.\gradlew :integration-test:loadSmokeTest :integration-test:encryptionSmokeTest -x detekt --rerun-tasks
.\gradlew :compatibility-test:extendedCompatibilityTest -x detekt --rerun-tasks
git diff --check
```

Use the named focused tasks for repeated verification runs. Running two
separate Gradle processes against the same `Test` task with different
`--tests` filters can still race on Gradle's binary test-result files; the
focused tasks use isolated result, report, and JaCoCo output directories.
When forcing recompilation with `--rerun-tasks`, keep focused checks in one
Gradle invocation because Kotlin/JVM compilation outputs remain module-shared.
