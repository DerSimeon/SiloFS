# Architecture — single-node S3-compatible object storage

## 1. Goals

* S3-compatible REST API on a single node, suitable for development and as a
  local target for the M6 Core 5 path-style matrix: AWS SDK Java v2, AWS CLI,
  boto3, AWS SDK JavaScript v3, and AWS SDK Go v2.
* Crash-safe durability for `PutObject` and multipart upload — once the API
  returns 200, the blob survives process kill, OOM, and machine restart.
* Path-style addressing (`http://host:port/bucket/key`) as the supported
  addressing mode. Virtual-host style is detected in compatibility tests but
  not supported.
* No replication, no clustering, no erasure coding, no versioning, no IAM.
* Coroutine-friendly: large uploads and downloads must stream without blocking
  request threads.

## 2. High-level component view

```
                     +---------------------------+
   AWS SDK /         |  Ktor HTTP engine         |
   boto3 / CLI  ---> |  (Netty, coroutine dispatcher) |
                     +-------------+-------------+
                                   |
                                   v
                     +-------------+-------------+
                     |  S3 request router        |
                     |  (path-style matcher)     |
                     +-------------+-------------+
                                   |
              +--------------------+--------------------+
              |                    |                    |
              v                    v                    v
   +----------+--------+ +---------+--------+ +--------+-------+
   | SigV4 auth        | | S3 XML response   | | Request parser |
   | middleware        | | / error formatter | | (query + body) |
   +-------------------+ +-------------------+ +----------------+
              |
              v
   +-------------------+        +----------------------+
   | Metadata repo     | <----> | PostgreSQL (HikariCP)|
   | (buckets, objects,|        | Flyway migrations    |
   |  multipart parts) |        +----------------------+
   +-------------------+
              |
              v
   +-------------------+
   | Blob store        | ---> local filesystem (data dir)
   | (temp + commit +  |
   |  fsync + rename)  |
   +-------------------+
              ^
              |
   +-------------------+
   | Recovery job      | (coroutine, periodic)
   | - sweep orphan tmp| 
   | - abort stale MPU |
   +-------------------+
```

## 3. Module layout

The Gradle build is split into focused modules so that each layer can be unit
tested in isolation. Module arrows denote `implementation`/`api` dependencies.

```
common        // pure JVM: S3 errors, XML, names, types — no I/O
   ^
auth          // SigV4 verifier + credential provider; depends on common
   ^
metadata      // PostgreSQL-backed MetadataRepository; depends on common
   ^
blob          // FsBlobStore (NIO.2 + fsync); depends on common
   ^
server        // Ktor app, routing, wiring; depends on all of the above
   ^
integration-test  // AWS SDK + Testcontainers; black-box against the server
   ^
compatibility-test // Docker-backed Core 5 client matrix
```

Server is the only module that depends on every other module. The
integration-test module is a `test-only` aggregate that boots the server and
exercises durability-oriented S3 behavior through the AWS SDK for Java v2. The
compatibility-test module boots the same server shape on a random port and runs
the declared Core 5 client matrix, with non-JVM clients isolated in pinned
Docker images.

## 4. Durability commit flow

`PutObject` (and `UploadPart`) follow the same five-step pattern. The order
matters; every step is idempotent from the recovery job's perspective.

1. Stream upload body to `<blob-dir>/.tmp/<uuid>` using a `ByteReadChannel`
   pulled from the Ktor `ApplicationCall`. The temp file is on the **same**
   filesystem as the final blob so the rename is atomic.
2. While streaming, compute SHA-256 and MD5, count bytes, and store them in
   memory.
3. When the upload completes, `fsync` the temp file's data and the parent
   directory. This is the durability boundary.
4. If `S3_OBJECT_ENCRYPTION_MODE=sse-s3`, encrypt the fsynced temp file into
   a second temp file using AES-GCM with authenticated header metadata
   (format version, plaintext SHA-256, plaintext size, and key id).
5. Atomically rename `<tmp>` to `<blob-dir>/objects/sha256[0:2]/sha256[2:4]/sha256`
   — content-addressed so duplicate uploads deduplicate on disk (but each
   object still has its own metadata row).
6. Insert a `blob_write_intents` row after fsync computes the SHA-256 and
   before the final rename. This intent is a durable DB reference for the
   otherwise-dangerous window between filesystem publish and metadata commit.
7. Insert the `objects` row and clear that exact write intent inside a single
   Postgres transaction. If the insert fails, the blob is left in place only
   until the failed request clears its intent or recovery later expires a stale
   intent and garbage-collects the unreferenced blob.

Multipart completion uses the same content-addressed layout. `CompleteMultipartUpload`
copies each part into the final blob using filesystem `sendfile` (via
`FileChannel.transferFrom`) into a fresh temp file, hashes while copying, fsyncs,
renames, and then writes the object metadata transactionally.

## 5. Concurrency model

* All request handling runs on Ktor's Netty event loop via `Dispatchers.IO` for
  blocking calls (Postgres, filesystem `fsync`) and the default dispatcher for
  CPU work (hashing, XML serialization).
* Streaming uses `ByteReadPacket` / `ByteWritePacket` from Ktor; large bodies
  never materialise fully in memory.
* Multipart `UploadPart` is fully concurrent — parts for the same upload ID can
  be written from different connections. Each part gets its own temp file and
  its own row in `multipart_parts` with `part_number` as part of a unique
  constraint.
* Reads (`GetObject`, `HeadObject`) acquire a read snapshot of the metadata row
  and stream straight off the blob file using `Range` headers.

## 6. Crash recovery

A coroutine scheduled by `CoroutineScope(Dispatchers.IO)` runs every 60 seconds
(default, configurable). It performs two sweeps:

1. **Orphan temp files** — any file in `<blob-dir>/.tmp/` older than
   `recovery.tmpMaxAgeSeconds` (default 1h) is deleted. If the process that
   wrote it is still running and reaches the rename step, it will fail and the
   client gets a 500 — which is the correct behaviour for a half-written
   upload.
2. **Stale multipart uploads** — any `multipart_uploads` row in `INITIATED`
   state older than `recovery.multipartMaxAgeSeconds` (default 24h) is aborted:
   `multipart_parts` rows are deleted and the upload row is marked `ABORTED`.
   Part blobs are reclaimed later by the unreferenced-blob sweep. Stale
   `COMPLETING` uploads are marked `FAILED_COMPLETION` using `completing_at`,
   not reopened to `INITIATED`.
3. **Stale blob write intents** — intent rows older than the configured maximum
   age are removed. These represent requests that crashed after reserving a
   blob SHA-256 but before committing object or part metadata.
4. **Unreferenced content blobs** — any file under `<blob-dir>/objects/` whose
   SHA-256 does not appear in live `objects`, `multipart_parts`, or active
   `blob_write_intents` is tombstoned after the minimum blob age. Before any
   filesystem move, the recovery job checks DB references again, then moves the
   blob to `<blob-dir>/.quarantine/`. A later final-delete pass rechecks DB
   references one more time; newly referenced quarantined blobs are restored to
   their content-addressed path instead of deleted.

The server runs one recovery pass during startup before it begins accepting
requests, then starts the periodic coroutine. These jobs are designed so that
crashing mid-sweep is safe — every step is idempotent.

For operator inspection, `silofs admin check-blobs` runs a read-only
consistency check that reports live DB references whose content-addressed blob
is missing, content blobs with no live DB reference, quarantined blobs, and
corrupt blobs whose decrypted plaintext SHA-256 or size does not match metadata.
`silofs admin recover-once` runs the same recovery sweep once and exits.

## 6.5. Object encryption

Object encryption is transparent SSE-S3-style encryption for local blobs. When
enabled, successful new object and multipart-part publishes store encrypted blob
bytes at the existing plaintext-SHA content-addressed path. Reads sniff the blob
header: plaintext legacy blobs are streamed directly, encrypted blobs are
authenticated and decrypted before GET, HEAD size calculation, range reads,
CopyObject, UploadPartCopy, checksum verification, and consistency checks.

The encryption master key is local single-node key material supplied through
`S3_OBJECT_ENCRYPTION_MASTER_KEY`. Operators must back it up with the metadata
and blob backup set. Losing the key makes encrypted blobs unrecoverable. SSE-C,
SSE-KMS, and external KMS are intentionally unsupported in M8.5.

## 7. Authentication

SigV4 is implemented in the `auth` module and exposed as a Ktor
`ApplicationCallPlugin`. The plugin:

1. Parses `Authorization: AWS4-HMAC-SHA256 ...` and `x-amz-date`.
2. Reconstructs the canonical request from method, URI, query, headers, signed
   headers, and payload hash. For streaming uploads the canonical hash may be
   the SigV4 streaming sentinel.
3. Derives the signing key from the secret, date, region, and service
   (`s3`).
4. Compares the computed signature to the one in the request using a constant-time
   compare.
5. Stores the verified SigV4 signing context on the Ktor call so signed
   aws-chunked request bodies can verify each chunk signature while streaming.
6. On mismatch, responds with the standard S3 `SignatureDoesNotMatch` XML.

Credentials are loaded from `access_keys` in Postgres. Access-key lifecycle
state is checked on every request, so disabling, deleting, or rotating a key
takes effect without restarting the server. Secrets are always stored encrypted
with AES-GCM using `S3_ACCESS_KEY_SECRET_ENCRYPTION_KEY`; create and rotate
commands print the raw secret once, but the database stores only ciphertext,
nonce, and key id. The schema rejects plaintext secret rows.

After authentication, bucket authorization checks `access_key_bucket_grants`.
`READ` permits object reads and listing, `WRITE` permits object and multipart
mutation, and `ADMIN` permits bucket and governance administration. Copy
operations require source `READ` and destination `WRITE`. The wildcard bucket
`*` is supported for operator/admin keys. Full IAM JSON, bucket policies, and
S3 ACL APIs are intentionally not part of this model.

Per-access-key rate limiting is optional. When configured, requests over the
token bucket return S3 `SlowDown` and increment a metrics counter.

## 8. Operational concerns

* Single configurable data directory, default `/var/lib/silofs/data`.
* Postgres connection via HikariCP, with Flyway applying migrations on boot.
* Structured logging via SLF4J + Logback JSON encoder.
* Every non-internal request is classified into an S3 operation name for logs
  and metrics. Access logs include request id, operation, status, latency,
  declared request bytes, and declared response bytes.
* Liveness endpoint at `GET /healthz` (no SigV4) for Docker Compose.
* Readiness endpoint at `GET /readyz` (no SigV4) that probes PostgreSQL and
  write access to the configured data directory, then verifies usable disk
  space is at least `S3_MIN_FREE_DISK_BYTES`.
* Metrics endpoint at `GET /metricsz` (Prometheus text format, no SigV4) with
  gauges for active multipart uploads, orphan temp files, quarantined blobs,
  and blob data directory bytes, plus request counters, request/response byte
  counters, latency histograms labelled by S3 operation and HTTP status,
  in-flight request/upload/completion gauges, limiter rejection counters,
  recovery sweep counters, blob-store error counters, and HikariCP pool gauges.
* Global semaphores bound concurrent object/part uploads and multipart
  completions. When saturated, handlers return S3 `SlowDown` (HTTP 503) before
  beginning blob publication.
* `CompleteMultipartUpload` XML bodies are capped by
  `S3_COMPLETE_XML_MAX_BYTES` before SAX parsing.
* Graceful shutdown flips the server into drain mode, stops accepting new work,
  waits for Ktor's configured quiet period and timeout, waits for in-flight
  accounting to reach zero, stops the recovery job, and closes the DB pool.
* Bucket versioning, lifecycle, and Object Lock state live in Postgres. Versioned
  deletes create metadata delete markers; lifecycle expiry marks eligible
  versions deleted first and leaves blob reclamation to the normal recovery/GC
  path. Retention and legal hold block both direct deletion and lifecycle expiry.
* CORS is disabled by default. Operators must configure
  `S3_CORS_ALLOWED_ORIGINS` to expose browser access.
* Mutating S3 operations are written to `audit_events` with request id,
  operation, access key id, bucket/key, status, and latency. Secrets,
  Authorization headers, and presigned signatures are not stored.

TLS is expected to terminate outside the Ktor process for now, such as at a
reverse proxy, load balancer, or host-level TLS endpoint. Do not expose the
plain HTTP listener directly on untrusted networks.

## 9. Compatibility envelope

M6 compatibility support is intentionally narrower than AWS S3:

* Clients must use an explicit endpoint and path-style addressing.
* Java v2 disables chunked encoding in the supported test row.
* boto3 uses `addressing_style = path`.
* JavaScript v3 uses `forcePathStyle: true`.
* Go v2 uses `UsePathStyle = true`.
* AWS CLI uses `--endpoint-url` and `s3.addressing_style = path`.

The compatibility tests also record virtual-host and streaming SigV4 detection
results. Virtual-host routing is not claimed. Signed `aws-chunked` request
bodies are decoded and per-chunk signatures are verified for object and
multipart-part uploads; unsigned trailer mode is decoded for clients that use
that framing.
