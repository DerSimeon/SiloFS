# M6 compatibility matrix

M6 proves a declared client matrix rather than broad AWS S3 equivalence. The supported envelope is path-style addressing with an explicit custom endpoint:

`http://host:port/bucket/key`

Virtual-host addressing and streaming SigV4 (`aws-chunked`) are detection/reporting items in M6, not supported behavior.

## Verification command

```bash
./gradlew :integration-test:test :compatibility-test:test -x detekt
```

The `:compatibility-test` module boots the Ktor server with PostgreSQL through Testcontainers, exposes the random server port to Docker clients with `host.testcontainers.internal`, and runs each non-JVM client in a pinned container image.

## Core 5 results

| Client | Version source | Required configuration | Status |
|--------|----------------|------------------------|--------|
| AWS SDK Java v2 | `software.amazon.awssdk:s3:2.46.17` | `endpointOverride`, `pathStyleAccessEnabled(true)`, `chunkedEncodingEnabled(false)` | Pass |
| AWS CLI | `amazon/aws-cli:2.15.57` | `--endpoint-url`, `s3.addressing_style = path` | Pass |
| boto3 | `python:3.12.8-slim-bookworm`, `boto3==1.35.99` | `endpoint_url`, `Config(s3={"addressing_style": "path"}, signature_version="s3v4")` | Pass |
| AWS SDK JavaScript v3 | `node:22.13.1-bookworm-slim`, `@aws-sdk/client-s3==3.731.1` | `endpoint`, `forcePathStyle: true` | Pass |
| AWS SDK Go v2 | `golang:1.23.5-bookworm`, `service/s3 v1.71.1` | `BaseEndpoint`, `UsePathStyle = true` | Pass |

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
| Streaming SigV4 / `aws-chunked` | Core 5 contract rows use file, bytes, or buffer bodies that do not require aws-chunked streaming. Detection rows record this as not required by the supported client configuration. |

## Compatibility fixes added during M6

- Bucket-root requests with a trailing slash (`/{bucket}/`) are routed as bucket operations, matching SDKs that normalize custom endpoints this way.
- Range GET responses omit full-object checksum headers to avoid client-side checksum validation against partial response bodies.
- `CompleteMultipartUpload` accepts both quoted and unquoted part ETags in the XML payload.

## Known gaps after M6

- No virtual-host addressing support.
- No streaming SigV4 / `aws-chunked` request-body decoder.
- No claim of compatibility with AWS SDK Kotlin, MinIO `mc`, `rclone`, or `s5cmd` yet.
- No ACLs, IAM policy engine, object versioning, lifecycle rules, replication, clustering, erasure coding, or server-side encryption.
