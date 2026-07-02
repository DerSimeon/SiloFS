# M6 compatibility matrix

M6 proves a declared client matrix rather than broad AWS S3 equivalence. The supported envelope is path-style addressing with an explicit custom endpoint:

`http://host:port/bucket/key`

Virtual-host addressing is a detection/reporting item, not supported behavior. `aws-chunked` request bodies are decoded for object and part uploads as of M10 compatibility expansion, and signed chunked payloads now verify each chunk signature before data is committed.

## Verification command

```bash
./gradlew dockerBackedVerification -x detekt
./gradlew :compatibility-test:extendedCompatibilityTest -x detekt
```

The `:compatibility-test` module boots the Ktor server with PostgreSQL through Testcontainers, exposes the random server port to Docker clients with `host.testcontainers.internal`, and runs each non-JVM client in a pinned container image.
The named extended task writes to isolated Gradle test-result and JaCoCo
outputs. Use one Gradle invocation when forcing recompilation with
`--rerun-tasks`, because module compilation outputs are still shared.

## Core 5 results

| Client | Version source | Required configuration | Status |
|--------|----------------|------------------------|--------|
| AWS SDK Java v2 | `software.amazon.awssdk:s3:2.46.17` | `endpointOverride`, `pathStyleAccessEnabled(true)`, `chunkedEncodingEnabled(false)` | Pass |
| AWS CLI | `amazon/aws-cli:2.15.57` | `--endpoint-url`, `s3.addressing_style = path` | Pass |
| boto3 | `python:3.12.8-slim-bookworm`, `boto3==1.35.99` | `endpoint_url`, `Config(s3={"addressing_style": "path"}, signature_version="s3v4")` | Pass |
| AWS SDK JavaScript v3 | `node:22.13.1-bookworm-slim`, `@aws-sdk/client-s3==3.1078.0` | `endpoint`, `forcePathStyle: true` | Pass |
| AWS SDK Go v2 | `golang:1.25-bookworm`, `service/s3 v1.97.3` | `BaseEndpoint`, `UsePathStyle = true` | Pass |

## Shared scenario contract

Each Core 5 row exercises:

- bucket lifecycle: create, head, list, get location
- object CRUD: put, head, get, delete, expected missing bucket/object errors
- metadata and content type roundtrip
- `ListObjectsV2` with prefix, delimiter, and unusual keys
- ranged GET
- `CopyObject`
- multipart create, upload part, list parts, list multipart uploads, complete, abort
- `UploadPartCopy`
- presigned GET and PUT where the client exposes generation cleanly
- checksum upload behavior where the client exposes the checksum header cleanly

## Detection rows

| Behavior | M6 result |
|----------|-----------|
| Virtual-host addressing | Detection tests run where practical. Required support remains path-style only. Local Docker DNS and the path-style router mean virtual-host results are recorded but do not gate the build. |
| Streaming SigV4 / `aws-chunked` | Core 5 contract rows use file, bytes, or buffer bodies that do not require aws-chunked streaming. M10 added aws-chunked body decoding after MinIO `mc` exposed it as required for the extended matrix; M12 adds per-chunk signature verification for signed aws-chunked bodies. |

## Extended client results

M10 expands the evidence matrix beyond the original Core 5 clients.

| Client | Version source | Required configuration | Status |
|--------|----------------|------------------------|--------|
| AWS SDK Kotlin | `aws.sdk.kotlin:s3:1.6.102` | explicit endpoint URL, path-style access, static test credentials | Pass |
| MinIO `mc` | `minio/mc:RELEASE.2024-11-21T17-21-54Z` | alias with `--api S3v4 --path on` | Pass with detections |
| `rclone` | `rclone/rclone:1.68.2` | S3 provider `Other`, explicit endpoint, static credentials | Pass with detections |
| `s5cmd` | `peakcom/s5cmd:v2.3.0` | `--endpoint-url`, static credentials from environment | Pass with detections |

Extended detections:

| Behavior | M10 result |
|----------|------------|
| `aws-chunked` streaming SigV4 | `mc` sends `STREAMING-AWS4-HMAC-SHA256-PAYLOAD`; Silofs decodes aws-chunked request bodies for object and part uploads and verifies signed chunks. |
| DeleteObjects | `mc rm --recursive` and `s5cmd rm s3://bucket/*` use S3 DeleteObjects. Silofs supports the batch delete API for those cleanup workflows. |
| `s5cmd` bucket listing | `s5cmd ls s3://` is not supported by the pinned CLI; bucket creation and object workflows are verified instead. |
| `s5cmd` wildcard listing | Basic prefix listing is gated; wildcard output shape is detection-only. |
| `rclone` missing object errors | Some missing-key workflows are smoothed into empty output by rclone; raw S3-shaped error assertions remain covered by SDK rows. |

## Compatibility fixes added during M6

- Bucket-root requests with a trailing slash (`/{bucket}/`) are routed as bucket operations, matching SDKs that normalize custom endpoints this way.
- Range GET responses omit full-object checksum headers to avoid client-side checksum validation against partial response bodies.
- `CompleteMultipartUpload` accepts both quoted and unquoted part ETags in the XML payload.

## Compatibility fixes added during M10

- `PutObject` and `UploadPart` decode aws-chunked request bodies before blob ingestion when `x-amz-content-sha256` is `STREAMING-AWS4-HMAC-SHA256-PAYLOAD` or `STREAMING-UNSIGNED-PAYLOAD-TRAILER`.
- Decoded content length is taken from `x-amz-decoded-content-length`, while the encoded HTTP `Content-Length` is treated as a wire framing length.
- Signed aws-chunked bodies verify every chunk signature against the SigV4 seed signature before data is committed.
- `DeleteObjects` is implemented for batch delete cleanup used by the extended CLI clients.

## Known gaps after M10 compatibility expansion

- No virtual-host addressing support.
- No ACLs, IAM policy engine, object versioning, lifecycle rules, replication, clustering, erasure coding, SSE-C, or SSE-KMS. Local SSE-S3 encryption is supported by M8.5.
