# Ktor route map (Milestone 1)

All routes are mounted under the application root and use **path-style** addressing:
`http://host:port/{bucket}` and `http://host:port/{bucket}/{key+}`.

The SigV4 auth plugin runs as a Ktor `ApplicationCallPipeline` phase *before*
routing. The only routes exempt from SigV4 are `GET /healthz`, `GET /readyz`,
and `GET /metricsz`.

## Routes

| Method     | Path pattern                    | Handler                       | S3 operation                |
|------------|---------------------------------|-------------------------------|-----------------------------|
| `GET`      | `/healthz`                      | `HealthRoute`                 | (internal liveness)         |
| `GET`      | `/readyz`                       | `ReadinessRoute`              | (internal readiness)        |
| `GET`      | `/metricsz`                     | `MetricsRoute`                | (internal Prometheus text)  |
| `PUT`      | `/{bucket}`                    | `CreateBucket`                | `CreateBucket`              |
| `HEAD`     | `/{bucket}`                    | `HeadBucket`                  | `HeadBucket`                |
| `DELETE`   | `/{bucket}`                    | `DeleteBucket`                | (M2) — returns NotImplemented |
| `GET`      | `/{bucket}`                    | `ListObjectsV2`               | `ListObjectsV2`             |
| `PUT`      | `/{bucket}/{key...}`           | `PutObject`                   | `PutObject`                 |
| `GET`      | `/{bucket}/{key...}`           | `GetObject` (Range-aware)     | `GetObject`                 |
| `HEAD`     | `/{bucket}/{key...}`           | `HeadObject`                  | `HeadObject`                |
| `DELETE`   | `/{bucket}/{key...}`           | `DeleteObject`                | `DeleteObject`              |

## Query parameter handling

* `GET /{bucket}?list-type=2` -> `ListObjectsV2`. Other `list-type` values
  return `NotImplemented` for now.
* `GET /{bucket}?location` -> `GetBucketLocation` (returns the configured region).
* Any unrecognised query parameter on `GET /{bucket}` returns
  `NotImplemented` so clients fail loudly rather than silently getting a list
  response.

## SigV4 scope of support (M1)

* `Authorization: AWS4-HMAC-SHA256` is the only accepted scheme.
* Payload signing: `x-amz-content-sha256` must be either the literal
  `UNSIGNED-PAYLOAD` or the hex SHA-256 of the body. We hash the body as we
  stream it to the temp file, so if the client claims a hash we verify it
  after the upload completes and reject with `XAmzContentSHA256Mismatch` if it
  doesn't match.
* The credential scope must reference service `s3` and a region that matches
  the server's configured region (env `S3_REGION`, default `us-east-1`).
* Presigned URL signing (query-string form) is M3.

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
