# S3 feature matrix (through Milestone 15)

## Supported

| Operation | Notes |
|-----------|-------|
| `CreateBucket` | Path-style. `/{bucket}` and `/{bucket}/` are both treated as bucket-root requests for SDK compatibility. `x-amz-bucket-region` echoed. Region is stored but not validated. |
| `HeadBucket` | 200 / 404. `/{bucket}` and `/{bucket}/` are both accepted. `x-amz-bucket-region` header on success. |
| `DeleteBucket` | Soft-deletes the bucket row. Returns `BucketNotEmpty` (409) if the bucket contains any non-deleted objects. Idempotent — deleting a missing bucket returns `NoSuchBucket`. |
| `ListBuckets` | Returns all non-deleted buckets in `ListAllMyBucketsResult` XML with `Owner` block. |
| `GetBucketLocation` | `GET /{bucket}?location` returns `LocationConstraint` XML; empty body for `us-east-1` (AWS convention). |
| `PutObject` | Single-shot. Streams to disk via temp file, fsync, rename. User metadata via `x-amz-meta-*`. ETag is the quoted MD5 hex. Validates `Content-Length` (required, ≥0, ≤5 GiB) and verifies actual streamed byte count. Validates `x-amz-storage-class`, honours `If-None-Match: *`. Persists and **verifies** `x-amz-checksum-{crc32,crc32c,sha1,sha256}` against actual blob content. |
| `GetObject` | Full body + `Range: bytes=...` support (single range). Honours `If-Match` and `If-None-Match`; returns `304 Not Modified` when the latter matches. Echoes persisted `x-amz-checksum-*` headers on full-object GET. Range GET intentionally omits full-object checksum headers so SDKs do not validate a partial body against a full-object checksum. |
| `HeadObject` | Returns the same headers as `GetObject` with no body. Honours `If-Match` / `If-None-Match`. Echoes checksums. |
| `DeleteObject` | Idempotent. Always returns 204 with `Content-Length: 0`. Soft-deletes the object row so the blob can be GC'd. |
| `DeleteObjects` | `POST /{bucket}?delete` batch-deletes object keys using the same soft-delete semantics as `DeleteObject`. Missing keys are reported as deleted for S3 compatibility; invalid keys appear in per-key `<Error>` entries. |
| Bucket versioning | `GET/PUT /{bucket}?versioning` supports `Enabled` and `Suspended`. Enabled buckets create opaque version IDs for `PutObject`, `CopyObject`, and completed multipart uploads. |
| List object versions | `GET /{bucket}?versions` returns object versions and delete markers for the practical S3 subset. |
| Versioned reads/deletes | `GET`, `HEAD`, and `DELETE` accept `versionId`. Delete without `versionId` creates a delete marker when bucket versioning is enabled. |
| Lifecycle configuration | `GET/PUT/DELETE /{bucket}?lifecycle` stores practical expiration rules. The lifecycle worker is opt-in via `S3_LIFECYCLE_ENABLED=true` and performs metadata-first expiry; blob GC happens later. |
| Object Lock subset | `GET/PUT /{bucket}?object-lock`, `GET/PUT ?retention`, and `GET/PUT ?legal-hold` support bucket-level enablement, default retention, per-object retention, and legal hold. Governance and Compliance are both enforced strictly. |
| `CopyObject` | Server-side copy via `x-amz-copy-source` header. Supports `x-amz-metadata-directive` (`COPY` default, `REPLACE`). Cross-bucket and in-place copy. Conditional copy via `x-amz-copy-source-if-match` / `x-amz-copy-source-if-none-match`. Returns `CopyObjectResult` XML with new ETag + LastModified. Source checksums are propagated. |
| `CreateMultipartUpload` | `POST /{bucket}/{key}?uploads`. Returns `UploadId`. Persists content-type, user metadata, storage class, and content headers for completion time. |
| `UploadPart` | `PUT /{bucket}/{key}?partNumber=N&uploadId=X`. Streams to temp file, fsyncs, renames to content-addressed blob. Re-upload overwrites (UPSERT). Validates part number (1–10000) and part size (≤5 GiB). Verifies `Content-Length` against actual byte count. Persists and verifies per-part checksums. Returns ETag header. |
| `UploadPartCopy` | `PUT /{bucket}/{key}?partNumber=N&uploadId=X` with `x-amz-copy-source`. Copies a byte range from an existing object into a part. Supports `x-amz-copy-source-range`. Returns `CopyPartResult` XML. |
| `CompleteMultipartUpload` | `POST /{bucket}/{key}?uploadId=X` with XML part list (parsed via SAX). Transitions `INITIATED -> COMPLETING` before reading parts (closes the mutation window). Publishes the final blob under a DB-backed blob write intent, then commits object metadata, `COMPLETED` state, part-row deletion, and intent cleanup in one DB transaction. Unsafe completion failures do **not** reopen the upload; recovery marks stale `COMPLETING` uploads `FAILED_COMPLETION`. Returns `CompleteMultipartUploadResult` XML on success. |
| `AbortMultipartUpload` | `DELETE /{bucket}/{key}?uploadId=X`. Performs a conditional `INITIATED -> ABORTED` transition before deleting part rows in the same transaction. If completion has already moved the upload to `COMPLETING`, abort fails and leaves parts untouched. Blobs are GC'd by recovery sweep. |
| `ListParts` | `GET /{bucket}/{key}?uploadId=X`. Returns `ListPartsResult` XML with all parts ordered by part number, including per-part checksums. |
| `ListMultipartUploads` | `GET /{bucket}?uploads`. Returns `ListMultipartUploadsResult` XML with all in-progress uploads. Supports `prefix` and `delimiter` with `CommonPrefixes` grouping. |
| `ListObjectsV2` | Full pagination with `max-keys`, `continuation-token`, `start-after`. Supports `prefix`, `delimiter` (with `CommonPrefixes` grouping), and `encoding-type=url` (URL-encodes keys and prefixes in the response). `KeyCount` counts both `Contents` and `CommonPrefixes`. `max-keys < 0` returns `InvalidArgument`. |
| Presigned GET/PUT | SigV4 over query string. Expiry enforcement (0–604800 seconds). Clock-skew validation. Tampered signatures → `SignatureDoesNotMatch` (403). |
| SigV4 | `AWS4-HMAC-SHA256`, unsigned payload or full payload hash. Constant-time compare. Clock-skew ±15 min (configurable). Strict signed-headers validation (all must be present, `host` mandatory). SigV4-specific percent decoding (no `+`-as-space). No canonical request leakage in errors. |
| Streaming SigV4 / `aws-chunked` | Object and part uploads decode `STREAMING-AWS4-HMAC-SHA256-PAYLOAD` and `STREAMING-UNSIGNED-PAYLOAD-TRAILER` request bodies used by clients such as MinIO `mc`. Signed aws-chunked payloads verify each chunk signature before data is committed. |
| XML errors | `<Error><Code/><Message/><Resource/><RequestId/></Error>` with per-request `x-amz-request-id` (16 hex) and `x-amz-id-2` on every response. |
| XML parsing | SAX-based parser for CompleteMultipartUpload (XXE-hardened: DOCTYPE disabled, external entities disabled). |
| Complete XML size limit | `CompleteMultipartUpload` request bodies are bounded by `S3_COMPLETE_XML_MAX_BYTES` before XML parsing. Oversized bodies return `MaxMessageLengthExceeded` (400). |
| Range reads | Single range, inclusive end, suffix range, open-ended range. Returns 206 with `Content-Range`. `Accept-Ranges: bytes` advertised on GET/HEAD. |
| User metadata | `x-amz-meta-*` headers echoed back on `GetObject` / `HeadObject` (lowercased name, verbatim value). |
| Content-Type | Preserved exactly, including parameters (`charset=`, `profile=`, etc.). Defaults to `application/octet-stream`. |
| Content-Length | Validated on PUT/UploadPart; required, non-negative, ≤5 GiB. Actual byte count verified against declared value; mismatch → `IncompleteBody` (400). |
| ETag | Quoted lowercase MD5 hex on PUT/GET/HEAD. Same content → same ETag across keys. Multipart ETag = `"<md5-of-concatenated-part-md5s>-N"`. |
| Last-Modified | RFC 1123 UTC; advances on overwrite. |
| Conditional GET/HEAD | `If-Match` / `If-None-Match` (incl. `*` wildcard and comma-separated lists). Weak `W/` prefix accepted. |
| Conditional PUT | `If-None-Match: *` enforces create-only-if-absent → `412 PreconditionFailed` if exists. |
| Conditional Copy | `x-amz-copy-source-if-match` / `x-amz-copy-source-if-none-match` on the source object. |
| Storage class | Validated against AWS-allowed set; echoed in `x-amz-storage-class` response header. |
| Standard response headers | `Date` (RFC 1123 UTC), `Server: AmazonS3`, `x-amz-request-id`, `x-amz-id-2` on every response. |
| Content-MD5 | Verified when supplied (RFC 1864); mismatch → `BadDigest`. |
| Checksums | `x-amz-checksum-{crc32,crc32c,sha1,sha256}` + `x-amz-checksum-type` persisted on PUT, **verified** against actual blob content, echoed on GET/HEAD, propagated by CopyObject. CRC32C uses a from-scratch Castagnoli implementation (JDK has no built-in). Default `checksumType` is `FULL_OBJECT` when any checksum is supplied. |
| Blob GC | Content-addressed blobs are GC'd by a tombstone plus quarantine sweep. Live object rows, multipart part rows, and active `blob_write_intents` all count as references; each tombstone is rechecked before moving a blob to quarantine, and quarantined blobs are rechecked again before final deletion. Soft-deleted objects are excluded so their blobs can be reclaimed. |
| Multipart race safety | `CompleteMultipartUpload` transitions to `COMPLETING` before reading parts, preventing concurrent `UploadPart` mutation. `AbortMultipartUpload` must win `INITIATED -> ABORTED` before deleting parts, so it cannot interfere with active completion. Stuck `COMPLETING` uploads are marked `FAILED_COMPLETION` by recovery based on `completing_at`; they are not reverted to `INITIATED`. |
| Operational limits | Object uploads, multipart part uploads/copies, object copies, and multipart completions are protected by configurable global semaphores. Saturation returns S3 `SlowDown` (503). |
| Readiness | `/readyz` probes PostgreSQL, data-directory writeability, and minimum free disk space. |
| Metrics | `/metricsz` exports request counters, latency histograms, request/response byte counters, active multipart uploads, orphan temp files, quarantined blobs, blob disk bytes, in-flight/rejected limiter counters, DB pool gauges, recovery sweep counters, and blob-store error counters. |
| Compatibility matrix | Docker-backed tests pass for the Core 5 path-style matrix plus the M10 extended clients: AWS SDK Kotlin, MinIO `mc`, `rclone`, and `s5cmd`. See `docs/COMPATIBILITY_M6.md`. |
| Access keys | Metadata-backed access keys with ACTIVE/DISABLED/DELETED lifecycle. DB-backed auth lookup means create/disable/rotate/delete take effect without server restart. |
| Bucket-scoped grants | Access keys can be granted `READ`, `WRITE`, or `ADMIN` on a bucket or wildcard `*`. Full IAM JSON, S3 ACL APIs, and bucket policies remain unsupported. |
| Secret storage | Access-key secrets can be stored encrypted with AES-GCM using `S3_ACCESS_KEY_SECRET_ENCRYPTION_KEY`; plaintext dev bootstrap remains available unless `S3_REQUIRE_ENCRYPTED_SECRETS=true`. |
| Object encryption | Optional SSE-S3-style encryption at rest with AES-GCM when `S3_OBJECT_ENCRYPTION_MODE=sse-s3` and `S3_OBJECT_ENCRYPTION_MASTER_KEY` are configured. New object, copy, multipart-part, and completed multipart blobs are stored encrypted while S3 ETag, checksum, size, range, copy, recovery, GC, backup, and restore semantics remain plaintext-compatible. `GetObject`/`HeadObject` echo `x-amz-server-side-encryption: AES256` for encrypted metadata rows. Existing plaintext blobs remain readable. |
| Rate limiting | Optional per-access-key rate limiting returns S3 `SlowDown` (503) and exports a rejection counter. |
| Audit logging | Mutating S3 requests and admin access-key changes are recorded in `audit_events` without secrets or presigned signatures. |
| CORS | Disabled by default. `S3_CORS_ALLOWED_ORIGINS` enables explicit origins; `*` is accepted only when explicitly configured. |
| Admin inspection | `silofs admin inspect ...`, `check-blobs`, `storage usage`, `repair --dry-run`, and `gc --dry-run` provide read-only operator visibility. |
| Backup/restore | Offline/quiesced backup and restore scripts cover PostgreSQL metadata dumps, content-addressed blob copies, manifests, and post-restore consistency verification. |
| Release automation | Tag pushes `v*.*.*` build CLI Linux binaries, `.deb` packages, GitHub Release assets, a GHCR server image, and Cloudsmith apt packages when Cloudsmith secrets are configured. |

## Not supported

| Operation | Status |
|-----------|--------|
| Virtual-host style addressing | Unsupported. M6 Core 5 support requires explicit path-style endpoint configuration; detection tests record virtual-host behavior separately. |
| `If-Modified-Since` / `If-Unmodified-Since` on CopyObject | M6 compatibility hardening (currently accepted but not enforced) |
| Full IAM policy engine / S3 ACL APIs / bucket policies | Unsupported. M13 implements bucket-scoped access-key grants only. |
| Lifecycle transitions to alternate storage classes | Unsupported. Lifecycle only expires metadata versions in the practical subset. |
| Replication / clustering | out of scope |
| Erasure coding | out of scope |
| SSE-KMS / external KMS | Unsupported. Local single-node SSE-S3 is the only M8.5 encryption mode. |
| SSE-C | Unsupported. Requests with SSE-C headers return `NotImplemented`. |
| Object Lock governance bypass | Unsupported. Governance and Compliance both block deletes strictly in this first implementation. |
| Storage classes other than `STANDARD` (accepted but treated as STANDARD) | out of scope |

## Durability contract

When `PutObject` returns 200:

1. The blob bytes have been `fsync`'d to disk under
   `<data-dir>/objects/sha256[0:2]/sha256[2:4]/sha256` and the parent
   directories have been `fsync`'d.
   If SSE-S3 is enabled, the published final blob bytes are AES-GCM ciphertext
   authenticated with the plaintext SHA-256, plaintext size, and encryption key
   id.
2. The actual streamed byte count was verified against the declared
   `Content-Length`.
3. All supplied `x-amz-checksum-*` headers were verified against the actual
   blob content.
4. The metadata row has been committed in Postgres in a single transaction.
5. If the process crashes between (1) and (4), the blob is unreferenced; the
   recovery job will sweep it and the client received a 5xx, not a 200.

The same contract applies to `UploadPart` and `CompleteMultipartUpload`.

## Concurrency guarantees

* Concurrent `PutObject` to the same key from two clients: both will write
  their own metadata row; the last writer wins (no compare-and-swap in M1).
  Use `If-None-Match: *` for a "create only if absent" semantic.
* Concurrent `GetObject` and `DeleteObject` on the same key: `GetObject`
  streams from a snapshot taken before the delete is committed, so it sees the
  old data. A `GetObject` issued after the delete commits gets a 404.
* Concurrent `PutObject` with the same content: writes to the same
  content-addressed blob path; both succeed because the rename is idempotent.
* Concurrent `CompleteMultipartUpload` on the same upload: the `COMPLETING`
  state transition is atomic — only one completer can win. The loser gets
  `NoSuchUpload`.
* Concurrent `UploadPart` and `CompleteMultipartUpload`: once the upload
  transitions to `COMPLETING`, `UploadPart` is rejected with `NoSuchUpload`.
