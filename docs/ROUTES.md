# Ktor route map

All routes are mounted under the application root and use **path-style** addressing:
`http://host:port/{bucket}` and `http://host:port/{bucket}/{key+}`.
For SDK compatibility, `http://host:port/{bucket}/` is treated as the same
bucket-root request as `http://host:port/{bucket}`.

The SigV4 auth plugin runs as a Ktor `ApplicationCallPipeline` phase *before*
routing. The only routes exempt from SigV4 are `GET /healthz`, `GET /readyz`,
and `GET /metricsz`.

## Routes

| Method     | Path pattern                    | Handler                       | S3 operation                |
|------------|---------------------------------|-------------------------------|-----------------------------|
| `GET`      | `/healthz`                      | `HealthRoute`                 | (internal liveness)         |
| `GET`      | `/readyz`                       | `ReadinessRoute`              | (internal readiness)        |
| `GET`      | `/metricsz`                     | `MetricsRoute`                | (internal Prometheus text)  |
| `GET`      | `/`                             | `ListBuckets`                 | `ListBuckets`               |
| `PUT`      | `/{bucket}`                    | `CreateBucket`                | `CreateBucket`              |
| `HEAD`     | `/{bucket}`                    | `HeadBucket`                  | `HeadBucket`                |
| `DELETE`   | `/{bucket}`                    | `DeleteBucket`                | `DeleteBucket`              |
| `GET`      | `/{bucket}`                    | `ListObjectsV2`               | `ListObjectsV2`             |
| `PUT`      | `/{bucket}/{key...}`           | `PutObject`                   | `PutObject`                 |
| `GET`      | `/{bucket}/{key...}`           | `GetObject` (Range-aware)     | `GetObject`                 |
| `HEAD`     | `/{bucket}/{key...}`           | `HeadObject`                  | `HeadObject`                |
| `DELETE`   | `/{bucket}/{key...}`           | `DeleteObject`                | `DeleteObject`              |
| `POST`     | `/{bucket}/{key...}?uploads`   | `CreateMultipartUpload`       | `CreateMultipartUpload`     |
| `PUT`      | `/{bucket}/{key...}?partNumber&uploadId` | `UploadPart` / `UploadPartCopy` | `UploadPart` / `UploadPartCopy` |
| `POST`     | `/{bucket}/{key...}?uploadId`  | `CompleteMultipartUpload`     | `CompleteMultipartUpload`   |
| `GET`      | `/{bucket}/{key...}?uploadId`  | `ListParts`                   | `ListParts`                 |
| `DELETE`   | `/{bucket}/{key...}?uploadId`  | `AbortMultipartUpload`        | `AbortMultipartUpload`      |

## Query parameter handling

* `GET /{bucket}?list-type=2` -> `ListObjectsV2`. Other `list-type` values
  return `NotImplemented`.
* `GET /{bucket}?location` -> `GetBucketLocation` (returns the configured region).
* `GET /{bucket}?uploads` -> `ListMultipartUploads`.
* Any unrecognised query parameter on `GET /{bucket}` returns
  `NotImplemented` so clients fail loudly rather than silently getting a list
  response.

## SigV4 scope of support

* `Authorization: AWS4-HMAC-SHA256` is the only accepted scheme.
* Payload signing: `x-amz-content-sha256` must be either the literal
  `UNSIGNED-PAYLOAD` or the hex SHA-256 of the body. We hash the body as we
  stream it to the temp file, so if the client claims a hash we verify it
  after the upload completes and reject with `XAmzContentSHA256Mismatch` if it
  doesn't match.
* The credential scope must reference service `s3` and a region that matches
  the server's configured region (env `S3_REGION`, default `us-east-1`).
* Presigned URL signing (query-string form) is supported for GET and PUT.

## Error responses

Every handler throws an `S3Exception`. The Ktor `StatusPages` plugin maps it
to an S3 XML body of the form:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Error>
  <Code>NoSuchBucket</Code>
  <Message>The specified bucket does not exist</Message>
  <Resource>/my-bucket/some/key</Resource>
  <RequestId>...</RequestId>
</Error>
```

Each error has an HTTP status and an S3 `Code` from `S3ErrorCode`.
