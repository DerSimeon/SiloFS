# Milestone plan

Each milestone ends with a green integration-test run against the supported client matrix for that stage and a documented "known compatibility gaps" section.

The project target is a production-candidate, single-node, S3-compatible object store for dev, staging, and small-scale production workloads. Production readiness is not claimed until durability, recovery, compatibility, security, backup/restore, and load-test sign-off are complete.

## Milestone 0 — project scaffold

Foundation for the Kotlin/JVM service.

Deliverables:

- Multi-module Gradle Kotlin DSL project
- Kotlin coroutines + Ktor server foundation
- Docker Compose: Postgres + server image
- HikariCP + Flyway wiring
- JaCoCo coverage gate
- ktlint + detekt wired into `check`
- `/healthz` endpoint
- basic `/metricsz` endpoint
- local development documentation

Exit criteria:

- Project builds locally
- Server starts through Docker Compose
- Database migrations run cleanly from an empty database
- Health endpoint returns success
- Baseline unit and integration tests pass

## Milestone 1 — minimum viable S3

End-to-end CRUD with the AWS SDK as the client. Establish the first durability contract.

S3 operations:

- `CreateBucket` using path-style addressing
- `HeadBucket`
- `PutObject` for single-shot uploads
- `GetObject`
- `HeadObject`
- `DeleteObject`
- basic `ListObjectsV2`
- SigV4 header authentication with a static credential pair
- standard S3 XML errors

Storage behavior:

- local filesystem blob store
- content-addressed blobs
- SHA-256 and MD5 hashing while streaming
- user metadata persistence
- temp-file upload path
- temp file fsync before publish
- atomic blob publish
- metadata commit after blob publish
- recovery sweep for orphan temp files

Tests:

- AWS SDK for Java v2 smoke suite
- create bucket
- put object
- get object
- head object
- delete object
- list objects
- missing bucket/object errors
- basic SigV4 success/failure
- unit tests at the configured coverage gate

Known gaps at end of M1:

- No `DeleteBucket`
- No `ListBuckets`
- No `CopyObject`
- `ListObjectsV2` does not yet fully support `prefix`, `delimiter`, `continuation-token`, `start-after`, or `encoding-type=url`
- No multipart upload
- No presigned URLs
- No virtual-host addressing
- No streaming SigV4 / `aws-chunked`
- Region handling is minimal

## Milestone 2 — S3 compatibility basics

Harden the XML response shapes, error codes, headers, and conditional request behavior for the M1 API surface.

Deliverables:

- request ID generation for every response
- `x-amz-request-id`
- `x-amz-id-2`
- `Date` header in RFC 1123 UTC
- `Server: AmazonS3` compatibility header, if intentionally emulating S3
- `x-amz-bucket-region` where relevant
- `Accept-Ranges: bytes`
- `Content-Type` preservation
- `Content-Length` validation
- `Content-MD5` validation when supplied
- ETag correctness for single-shot uploads
- Last-Modified correctness
- `x-amz-meta-*` roundtrip
- storage-class validation
- `Range` GET support
- conditional `GET` and `HEAD` via `If-Match` and `If-None-Match`
- conditional `PUT` via `If-None-Match: *`
- improved S3 XML error bodies
- explicit `Content-Length: 0` for empty delete responses where applicable

Tests:

- AWS SDK metadata roundtrip
- content type preservation
- ETag format and stability
- Last-Modified behavior on overwrite
- range reads
- missing bucket/object errors
- invalid bucket names
- invalid request arguments
- weird object keys
- conditional GET/HEAD/PUT behavior
- request ID on success and error
- checksum PUT behavior
- delete idempotency
- empty object roundtrip

Known gaps at end of M2:

- No `DeleteBucket`
- No `ListBuckets`
- No `CopyObject`
- listing features still incomplete
- No multipart upload
- No presigned URLs
- No virtual-host addressing
- No streaming SigV4 / `aws-chunked`

## Milestone 2.1 — bucket lifecycle and listing features

Complete the basic bucket lifecycle and listing behavior needed by common SDKs and tools.

Deliverables:

- `DeleteBucket`, only when empty
- `ListBuckets`
- `GetBucketLocation`
- `CopyObject` within the same node
- `ListObjectsV2` with:
  - `prefix`
  - `delimiter`
  - `continuation-token`
  - `start-after`
  - `max-keys`
  - `encoding-type=url`
  - stable ordering
- persist `x-amz-checksum-*` values on PUT where supported
- echo supported checksums on GET/HEAD where appropriate

Tests:

- delete empty bucket
- reject delete of non-empty bucket
- list buckets
- get bucket location
- copy object
- copy object metadata behavior
- list with prefix
- list with delimiter
- list pagination
- list with `start-after`
- URL encoding behavior
- weird keys with slashes, spaces, unicode, and reserved characters

Known gaps at end of M2.1:

- No multipart upload
- No presigned URLs
- No virtual-host addressing
- No streaming SigV4 / `aws-chunked`

## Milestone 3 — multipart upload

Implement full multipart upload support for large-object workflows.

S3 operations:

- `CreateMultipartUpload`
- `UploadPart`
- `CompleteMultipartUpload`
- `AbortMultipartUpload`
- `ListParts`

Deliverables:

- upload IDs persisted in metadata DB
- content headers and metadata captured at initiation time
- parts streamed to temp files
- parts fsynced before success
- parts published as content-addressed blobs
- part rows committed after blob publish
- part number validation, 1 to 10000
- part size validation
- re-upload of the same part number replaces that part
- complete request XML parsing through a hardened XML parser
- multipart ETag calculation
- validation of requested parts on complete
- validation of client-supplied part ETags
- atomic object metadata commit on complete
- abort behavior that does not expose partial objects
- abandoned multipart cleanup

Tests:

- full multipart upload through AWS SDK for Java v2
- multipart ETag format
- complete with parts out of order
- abort upload
- invalid upload ID
- missing part
- duplicate part number overwrite
- part number out of range
- last part smaller than 5 MiB
- non-last part smaller than 5 MiB rejected
- empty part list rejected
- multipart metadata and content type
- range read across part boundary
- concurrent part uploads
- delete completed multipart object
- server restart with initiated multipart upload

Deferred from M3 to M3.1:

- `ListMultipartUploads`
- `UploadPartCopy`
- presigned GET/PUT URLs
- per-part checksum persistence and echo behavior

Known gaps at end of M3:

- No virtual-host addressing
- No streaming SigV4 / `aws-chunked`

## Milestone 3.1 — presigned URLs and multipart extras

Finish the multipart-adjacent feature set and add practical presigned URL support.

Deliverables:

- `ListMultipartUploads`
- `UploadPartCopy`
- optional `x-amz-copy-source-range`
- presigned GET URLs
- presigned PUT URLs
- query-string SigV4 verification
- presigned URL expiration validation
- signed header validation for presigned URLs
- tampered signature rejection
- per-part checksum persistence for supported algorithms
- checksum echo on `UploadPart` and `ListParts`
- CRC32, CRC32C, SHA1, and SHA256 behavior is either verified or explicitly rejected

Tests:

- list in-progress multipart uploads
- list multipart uploads with prefix
- list multipart uploads with delimiter
- completed uploads excluded from list
- UploadPartCopy full copy
- UploadPartCopy range copy
- UploadPartCopy missing source
- presigned GET via plain HTTP client
- presigned PUT via plain HTTP client
- expired presigned URL
- tampered signature
- missing signed header
- per-part checksums

Known gaps at end of M3.1:

- No virtual-host addressing
- No streaming SigV4 / `aws-chunked`
- copy conditionals may be incomplete unless explicitly implemented
- durability, recovery, GC, and race behavior still require dedicated hardening before production claims

## Milestone 4 — durability, recovery, and GC correctness

This milestone is mandatory before adding more broad compatibility or production-ops features. The goal is to prove that successful writes remain recoverable after crashes, restarts, failed transactions, GC, and multipart races.

Status: complete for the declared single-node architecture. The implementation now has DB-backed blob write intents, quarantine-before-delete GC, repeated failpoint crash coverage, recovery idempotency tests, and multipart race tests for completion/abort/part-upload races.

Deliverables:

- blob lifecycle design that prevents GC from deleting in-flight commits
- minimum blob age before GC eligibility
- quarantine-before-delete for blob GC
- second reference check before final deletion
- DB/blob consistency checker
- admin command to inspect orphans and missing blobs
- explicit object commit state-machine documentation
- safe overwrite semantics
- safe delete semantics
- safe multipart state transitions
- strict `INITIATED -> COMPLETING -> COMPLETED` completion flow
- strict `INITIATED -> ABORTED` abort flow
- no part mutation after upload enters `COMPLETING`
- `AbortMultipartUpload` cannot interfere with active completion
- stuck `COMPLETING` recovery based on completion timestamp, not initiation timestamp
- failure policy for unsafe completion failures, for example `FAILED_COMPLETION`
- recovery job idempotency
- startup recovery scan
- orphan temp cleanup
- orphan blob cleanup after quarantine
- disk-full handling
- fsync failure handling
- interrupted upload handling

Crash/failpoint tests:

- crash after temp write before fsync
- crash after temp fsync before rename
- crash after rename before DB metadata commit
- crash during metadata commit
- crash after DB commit before response
- crash during overwrite
- crash during delete
- crash during multipart part upload
- crash during multipart completion before final blob publish
- crash during multipart completion after final blob publish before DB commit
- crash during multipart completion after object row update before part cleanup
- crash during recovery sweep
- crash during blob quarantine
- crash during final blob deletion

Race tests:

- concurrent `PutObject` to same key
- `GetObject` while overwrite is happening
- `HeadObject` while overwrite is happening
- `DeleteObject` while read is happening
- `DeleteObject` while put is happening
- `UploadPart` racing with `CompleteMultipartUpload`
- `AbortMultipartUpload` racing with `CompleteMultipartUpload`
- duplicate `CompleteMultipartUpload`
- duplicate SDK retries
- blob GC racing with active uploads
- blob GC racing with completed overwrites

Exit criteria:

- no committed object points to a missing blob
- no successful `PutObject` is lost after crash/restart
- no partial object is visible
- GC never deletes blobs referenced by live objects or in-flight commits
- failed or abandoned multipart uploads recover to a documented safe state
- all durability and race tests pass repeatedly

Known gaps at end of M4:

- No virtual-host addressing unless already added
- No streaming SigV4 / `aws-chunked`
- compatibility matrix still limited
- backup/restore and security hardening are still later milestones

## Milestone 5 — operational hardening

Make the service operable as a single-node system.

Status: complete for the current single-node envelope. The server now tracks in-flight requests/uploads/completions, enforces upload and multipart-completion concurrency limits with S3 `SlowDown`, bounds CompleteMultipartUpload XML bodies, probes DB/data-dir/free-disk readiness, exports limiter/DB/recovery/blob-store metrics, logs request id/operation/status/latency/bytes, and drains in-flight requests during graceful shutdown.

Deliverables:

- structured JSON access logs
- request ID in every log line
- operation name in logs and metrics
- latency logging
- bytes uploaded/downloaded logging
- Prometheus metrics endpoint
- request count by operation and status
- latency histograms
- bytes uploaded/downloaded counters
- active multipart upload metric
- orphan temp file metric
- quarantined blob metric
- blob disk usage metric
- DB pool metrics
- blob store error metrics
- health endpoint
- readiness endpoint with DB and disk checks
- graceful shutdown
- drain in-flight requests
- close DB pool cleanly
- background job lifecycle management
- request body size limits
- configurable concurrent upload limits
- backpressure for multipart uploads
- per-bucket quota support if needed for deployment target

Tests:

- health and readiness behavior
- metrics endpoint shape
- structured log fields
- request ID propagation
- graceful shutdown with in-flight GET
- graceful shutdown with in-flight PUT
- concurrent upload limit enforcement
- quota enforcement if implemented

Known gaps at end of M5:

- broad SDK/client compatibility matrix still incomplete
- security hardening still incomplete
- backup/restore tooling still incomplete
- structured logs are key-value SLF4J/logback events; deployments that require strict JSON must keep the JSON encoder configuration enabled
- per-bucket quota support is not implemented because no deployment target has required it yet

## Milestone 6 — compatibility matrix expansion

Verify the implementation against real-world clients and document compatibility gaps honestly.

Status: complete for the declared Core 5 path-style matrix. The `:compatibility-test` module now runs Docker-backed compatibility rows for AWS CLI, boto3, AWS SDK JavaScript v3, and AWS SDK Go v2, plus an in-JVM AWS SDK Java v2 row. The tested support contract requires explicit path-style/custom-endpoint configuration. Virtual-host addressing and streaming SigV4 are detected and documented, not supported.

Clients:

- AWS SDK for Java v2: supported in the Core 5 path-style matrix
- AWS CLI with `--endpoint-url`: supported in the Core 5 path-style matrix
- boto3: supported in the Core 5 path-style matrix
- AWS SDK for JavaScript v3: supported in the Core 5 path-style matrix
- AWS SDK for Go v2: supported in the Core 5 path-style matrix
- AWS SDK for Kotlin: supported in the M10 extended path-style matrix
- MinIO `mc`: supported in the M10 extended path-style matrix
- `rclone`: supported in the M10 extended path-style matrix
- `s5cmd`: supported in the M10 extended path-style matrix

Deliverables:

- shared compatibility test harness
- path-style endpoint test matrix
- virtual-host style addressing detection
- streaming SigV4 / `aws-chunked` detection
- behavior comparison against real AWS S3 where practical, deferred until exact production clients need it
- documented compatibility report
- explicit unsupported feature list
- client configuration examples

Compatibility areas:

- bucket create/head/delete/list/location
- object put/get/head/delete/copy
- list objects with prefix/delimiter/pagination
- multipart upload
- multipart abort
- multipart list parts
- list multipart uploads
- upload part copy
- metadata
- checksums
- range reads
- presigned URLs
- XML errors
- retry behavior
- weird keys
- large objects

Known gaps after M10 compatibility expansion:

- virtual-host style addressing remains unsupported; Core 5 clients must be configured for path-style
- any future client-specific incompatibility must be documented with reproduction steps in `docs/COMPATIBILITY_M6.md`

## Milestone 7 — security hardening

Harden authentication, request validation, secret handling, and data protection.

Status: complete for authentication/request hardening in the single-node envelope. Access keys now have lifecycle state, optional AES-GCM encrypted secret storage, DB-backed lookup without restart, per-access-key rate limiting, audit events for mutating operations, opt-in CORS, presigned query redaction, and documented TLS/security boundaries.

Deliverables:

- access key management
- key creation, disable, rotate, delete
- secure secret hashing or storage strategy
- no secret logging
- log redaction tests
- constant-time comparisons where relevant
- request size limits
- XML parser hardening
- path traversal protections
- malformed header handling
- malformed query handling
- rate limiting per access key
- CORS policy per bucket if needed
- TLS termination guidance
- SSE-S3 encryption at rest if in scope: deferred to M8.5
- SSE-C if explicitly required: deferred to M8.5
- audit log of mutating operations
- security review notes

Tests:

- valid key
- invalid key
- disabled key
- rotated key
- malformed Authorization header
- malformed presigned URL
- oversized request
- malicious object key
- malformed XML
- XXE prevention
- secret values absent from logs
- rate limit behavior
- encryption roundtrip if SSE is implemented

Known gaps at end of M7:

- no IAM, ACL, bucket policy, Object Lock, or lifecycle authorization engine
- no SSE-S3/SSE-C until M8.5; use filesystem or block-device encryption before then
- TLS is expected to terminate at a reverse proxy, load balancer, or host TLS layer

## Milestone 8 — backup, restore, and admin tooling

Make the single-node deployment recoverable and maintainable.

Status: complete for offline/quiesced backup and restore. M8 adds `pg_dump`/`pg_restore` scripts, incremental content-blob copy, backup manifests, admin inspection commands, backup verification through the blob consistency checker, repair/GC dry-runs, migration runner, access-key lifecycle admin commands, and operator procedures.

Deliverables:

- `pg_dump` metadata backup script
- metadata restore procedure
- blob backup procedure
- incremental blob backup using content-addressed SHA-256
- backup consistency procedure between DB and blobs
- restore verification command
- `silofs admin` CLI commands
- inspect buckets
- inspect objects
- inspect multipart uploads
- inspect blob references
- trigger recovery scan
- trigger blob GC quarantine scan
- trigger final blob deletion pass
- report missing blobs
- report orphan blobs
- report storage usage
- database migration runner command
- admin access-key bootstrap command
- upgrade guide
- rollback guidance where possible

Tests:

- backup from populated server
- restore into empty environment
- verify restored object checksums
- restore with multipart state present
- migration from previous schema
- admin repair dry-run
- admin GC dry-run

Known gaps at end of M8:

- online write-consistent backup remains unsupported; quiesce writes first
- backups are local scripts and must be copied to independent storage by operators

## Milestone 8.5 — encryption at rest

Add explicit object encryption support before final production-readiness sign-off.

Status: complete for local single-node SSE-S3. New blobs can be transparently
encrypted with AES-GCM using operator-supplied local key material. Existing
plaintext blobs remain readable, encrypted blobs are authenticated during read
and consistency checks, and unsupported SSE-C/SSE-KMS modes are rejected with
S3-shaped errors.

Deliverables:

- declare supported encryption mode: SSE-S3 first, SSE-C only if explicitly required
- encrypted blob format with authenticated metadata
- key management procedure for single-node deployments
- checksum, range-read, copy, multipart, recovery, GC, backup, and restore compatibility
- migration and rollback guidance for existing plaintext blobs
- security review notes for encryption boundaries and residual plaintext metadata

Tests:

- encrypted PUT/GET/HEAD/range/copy/multipart roundtrip
- recovery and GC with encrypted blobs
- backup/restore with encrypted blobs
- wrong/missing key failure modes

Known gaps at end of M8.5:

- SSE-C, SSE-KMS, and external KMS integration are unsupported
- online bulk re-encryption of historical plaintext blobs is not implemented
- operators must back up `S3_OBJECT_ENCRYPTION_MASTER_KEY`; losing it makes
  encrypted blobs unrecoverable

## Milestone 9 — production readiness review

Final sign-off milestone. This does not add many features. It proves the system is safe enough for the declared deployment envelope.

Status: complete for the documented single-node production-candidate envelope.
The sign-off artifact is `docs/PRODUCTION_READINESS_M9.md`, including declared
limits, durability/security/backup evidence, monitoring and alerting guidance,
failure response, unsupported features, release verification commands, and the
one-class-per-file check.

Deliverables:

- production readiness checklist
- durability report
- crash-test report
- GC correctness report
- compatibility report
- security review notes
- backup/restore report
- load-test results
- performance limits
- operational runbook
- failure-mode table
- monitoring and alerting guide
- deployment guide
- upgrade guide
- unsupported S3 features document
- data corruption response procedure
- incident response checklist
- one-class-per-file rule enforced for new code and checked before sign-off

Load and scale targets:

- declare supported maximum object count
- declare supported maximum total blob storage
- declare supported maximum object size
- declare supported sustained RPS
- declare supported concurrent multipart uploads
- declare tested filesystem and OS assumptions
- declare tested PostgreSQL version

Exit criteria:

- durability, recovery, and GC tests pass repeatedly
- compatibility matrix passes for supported clients
- backup and restore are tested end to end
- security review has no unresolved critical or high-risk issues
- load tests meet declared targets
- runbook is complete enough for an operator who did not write the system
- all known gaps are documented

## Milestone 10 — standalone CLI

Status: complete for the M10 scope. The Docker-backed compatibility expansion covers AWS SDK Kotlin, MinIO `mc`, `rclone`, and `s5cmd`. The compatibility work added aws-chunked request-body decoding required by `mc`. A standalone Go `silofs` CLI now provides S3 client workflows and local PostgreSQL/blob-directory admin workflows. M12 later closed the signed aws-chunked verification and DeleteObjects cleanup gaps.

Provide a separate `silofs` operator/client CLI that can be built into an easy-to-deploy Linux binary, similar in spirit to `mc`, instead of requiring operators to invoke the server jar for admin workflows.

Deliverables:

- standalone CLI project outside the JVM server artifact
- simple Linux binary build path
- commands for admin inspection, access-key lifecycle, backup/restore verification, and basic S3-compatible object workflows
- documented installation into a server `bin` directory
- compatibility with the server's admin and S3 APIs without requiring local classpath access

Tests:

- CLI command parsing and output stability
- integration tests against a local silofs server
- binary build smoke test on Linux CI or container

Production statement:

After M9, the software may be considered production-candidate for single-node deployments, subject to successful sign-off against the declared limits and operational requirements. Do not claim general S3 compatibility or broad production readiness beyond the tested scope.

## Milestone 11 — release hardening and verification reliability

Status: implementation complete; awaiting first remote CI run for external
release evidence. M11 makes release verification repeatable and removes stale
branding/doc inconsistencies before final production-ready claims.

Deliverables:

- locked production verification scripts for Windows and Linux
- one blessed forced-rerun verification path that avoids competing Gradle processes
- CI jobs for JVM tests, Docker-backed compatibility, focused production checks, and Go CLI build/test
- physical source tree paths aligned with `app.silofs`
- stale milestone, compatibility, encryption, and unsupported-feature docs cleaned up
- release checklist updated to use the locked verification scripts

Exit criteria:

- `scripts/verify-production.ps1 -Rerun` passes on Windows with Docker Desktop
- `scripts/verify-production.sh --rerun` passes on Linux with Docker
- concurrent script invocations serialize on `build/locks/production-verification.lock`
- source paths, package names, and docs consistently use `app/silofs` and `app.silofs`
- no stale compatibility docs contradict the M10 matrix or M8.5 encryption status

## Milestone 12 — production closure and security/compatibility gaps

Status: implementation complete; awaiting first remote CI run for external
release evidence. M12 closes the remaining blockers before calling the project
production-ready for the declared single-node envelope.

Deliverables:

- per-chunk SigV4 verification for signed `aws-chunked` uploads
- `DeleteObjects` support for recursive CLI cleanup workflows
- encrypted blob decryptability verification in the Go CLI
- final CI-backed release evidence using the M11 scripts
- final production-ready report that distinguishes supported single-node operation from intentionally unsupported S3 and distributed-system features

Exit criteria:

- supported compatibility clients pass without detection-only cleanup gaps that affect normal workflows
- security review accepts the final `aws-chunked` policy
- CLI `admin check-blobs` verifies encrypted blobs with correct/missing/wrong key behavior
- full locked release verification passes from a clean checkout

## Milestone 13 — bucket-scoped access grants

Status: implemented; pending full CI-backed release evidence.

M13 adds a practical authorization layer after SigV4 authentication. Access
keys can be granted `READ`, `WRITE`, or `ADMIN` on individual buckets or the
wildcard bucket `*`.

Deliverables:

- `access_key_bucket_grants` metadata table
- request authorization before bucket/object/multipart work
- source `READ` plus destination `WRITE` checks for copy operations
- JVM admin and standalone CLI grant add/list/remove commands
- integration coverage for read-only access keys

Known non-goals:

- full IAM JSON policies
- S3 ACL APIs
- bucket policy APIs

## Milestone 14 — bucket governance

Status: implemented for the practical single-node subset; pending full
compatibility and CI evidence.

M14 adds per-bucket versioning, lifecycle expiry, and Object Lock controls.
The implementation prioritizes correctness over the full AWS governance API
surface.

Deliverables:

- bucket versioning state
- opaque object version IDs for PUT, CopyObject, and completed multipart upload
- delete markers for versioned DELETE without `versionId`
- version-aware GET, HEAD, DELETE, and ListObjectVersions
- lifecycle rule storage and opt-in metadata-first lifecycle worker
- bucket Object Lock enablement, default retention, object retention, and legal hold
- retention/legal-hold enforcement for deletes and lifecycle expiry

Known non-goals:

- lifecycle transitions to alternate storage classes
- governance bypass permissions
- complete AWS Object Lock parity

## Milestone 15 — release automation

Status: implemented; first tagged release will validate external publishing.

M15 adds release automation for GitHub Releases, GHCR server images, static CLI
binaries, Debian packages, and Cloudsmith apt publishing.

Deliverables:

- tag-triggered release workflow for `v*.*.*`
- CLI Linux binaries for `amd64` and `arm64`
- Debian package `silofs` installing `/usr/bin/silofs`
- SHA-256 checksums
- GitHub Release artifacts
- GHCR server image
- Cloudsmith Debian package publishing when `CLOUDSMITH_API_KEY`,
  `CLOUDSMITH_OWNER`, and `CLOUDSMITH_REPO` secrets are configured

Install shape:

```bash
curl -1sLf "https://dl.cloudsmith.io/public/<owner>/<repo>/setup.deb.sh" | sudo -E bash
sudo apt update
sudo apt install silofs
silofs version
```
