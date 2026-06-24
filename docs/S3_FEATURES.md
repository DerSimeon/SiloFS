# S3 feature matrix (Milestone 3.1)

## Supported in M3.1

| Operation | Notes |
|-----------|-------|
| `CreateBucket` | Path-style. `x-amz-bucket-region` echoed. Region is stored but not validated. |
| `HeadBucket` | 200 / 404. `x-amz-bucket-region` header on success. |
| `DeleteBucket` | Soft-deletes the bucket row. Returns `BucketNotEmpty` (409) if the bucket contains any non-deleted objects. Idempotent — deleting a missing bucket returns `NoSuchBucket`. |
| `ListBuckets` | Returns all non-deleted buckets in `ListAllMyBucketsResult` XML with `Owner` block. |
| `GetBucketLocation` | `GET /{bucket}?location` returns `LocationConstraint` XML; empty body for `us-east-1` (AWS convention). |
| `PutObject` | Single-shot. Streams to disk via temp file, fsync, rename. User metadata via `x-amz-meta-*`. ETag is the quoted MD5 hex. Validates `Content-Length` (required, ≥0, ≤5 GiB) and verifies actual streamed byte count. Validates `x-amz-storage-class`, honours `If-None-Match: *`. Persists and **verifies** `x-amz-checksum-{crc32,crc32c,sha1,sha256}` against actual blob content. |
| `GetObject` | Full body + `Range: bytes=...` support (single range). Honours `If-Match` and `If-None-Match`; returns `304 Not Modified` when the latter matches. Echoes persisted `x-amz-checksum-*` headers. |
| `HeadObject` | Returns the same headers as `GetObject` with no body. Honours `If-Match` / `If-None-Match`. Echoes checksums. |
| `DeleteObject` | Idempotent. Always returns 204 with `Content-Length: 0`. Soft-deletes the object row so the blob can be GC'd. |
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
| XML errors | `<Error><Code/><Message/><Resource/><RequestId/></Error>` with per-request `x-amz-request-id` (16 hex) and `x-amz-id-2` on every response. |
| XML parsing | SAX-based parser for CompleteMultipartUpload (XXE-hardened: DOCTYPE disabled, external entities disabled). |
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
| Blob GC | Content-addressed blobs are GC'd by a two-phase tombstone sweep. Live object rows, multipart part rows, and active `blob_write_intents` all count as references; each tombstone is rechecked before filesystem deletion. Soft-deleted objects are excluded so their blobs can be reclaimed. |
| Multipart race safety | `CompleteMultipartUpload` transitions to `COMPLETING` before reading parts, preventing concurrent `UploadPart` mutation. `AbortMultipartUpload` must win `INITIATED -> ABORTED` before deleting parts, so it cannot interfere with active completion. Stuck `COMPLETING` uploads are marked `FAILED_COMPLETION` by recovery based on `completing_at`; they are not reverted to `INITIATED`. |

## Not supported in M3.1 (returns `NotImplemented` or `NotSupported`)

| Operation | Target milestone |
|-----------|------------------|
| Streaming SigV4 (`aws-chunked`) | M4 |
| Virtual-host style addressing | M4 |
| `If-Modified-Since` / `If-Unmodified-Since` on CopyObject | M4 (currently accepted but not enforced) |
| Object versioning | out of scope |
| ACLs / IAM policy engine | out of scope |
| Lifecycle policies | out of scope |
| Replication / clustering | out of scope |
| Erasure coding | out of scope |
| Server-side encryption (SSE-S3, SSE-KMS, SSE-C) | M7 |
| Object Lock | out of scope |
| Storage classes other than `STANDARD` (accepted but treated as STANDARD) | out of scope |

## Durability contract

When `PutObject` returns 200:

1. The blob bytes have been `fsync`'d to disk under
   `<data-dir>/objects/sha256[0:2]/sha256[2:4]/sha256` and the parent
   directories have been `fsync`'d.
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
