<p align="center">
  <img src="assets/silofs-logo.png" alt="SiloFS logo" width="360">
</p>

<h1 align="center">SiloFS</h1>

<p align="center">
  Single-node S3-compatible object storage you can run yourself.
</p>

<p align="center">
  <a href="https://github.com/DerSimeon/SiloFS/actions"><img alt="CI" src="https://github.com/DerSimeon/SiloFS/actions/workflows/ci.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache%202.0-blue.svg"></a>
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-JVM-7F52FF.svg">
  <img alt="S3 compatible" src="https://img.shields.io/badge/S3-compatible-0f9f9a.svg">
</p>

## Key Features

* S3-compatible API for common path-style clients.
* PostgreSQL metadata with local filesystem blob storage.
* Crash-safe writes, recovery sweeps, and blob consistency checks.
* Multipart uploads, copy, range reads, presigned URLs, and batch deletes.
* Optional SSE-S3 style object encryption.
* Bucket-scoped access grants for individual access keys.
* Optional per-bucket versioning, lifecycle expiry, and Object Lock controls.
* Docker-backed compatibility tests for AWS SDKs, AWS CLI, boto3, MinIO `mc`,
  `rclone`, and `s5cmd`.
* Standalone `silofs` CLI for object operations and local admin inspection.

## How To Use

Start the server:

```bash
docker compose up --build
```

Use the AWS CLI:

```bash
aws configure set default.s3.addressing_style path
aws --endpoint-url http://localhost:8080 --region us-east-1 s3 mb s3://photos
aws --endpoint-url http://localhost:8080 s3 cp ./image.jpg s3://photos/image.jpg
aws --endpoint-url http://localhost:8080 s3 ls s3://photos/
```

Or build and use the standalone CLI:

```bash
cd cli
go build -o silofs .
./silofs --endpoint http://localhost:8080 mb s3://photos
./silofs cp ./image.jpg s3://photos/image.jpg
./silofs ls s3://photos/
```

Install the CLI from the apt repository once a release has been published:

```bash
curl -1sLf "https://dl.cloudsmith.io/public/<owner>/<repo>/setup.deb.sh" | sudo -E bash
sudo apt update
sudo apt install silofs
silofs version
```

Default local-development credentials:

```text
Access key: AKIAIOSFODNN7EXAMPLE
Secret key: wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
Region:     us-east-1
```

More details:

* [Supported S3 features](docs/S3_FEATURES.md)
* [Compatibility matrix](docs/COMPATIBILITY_M6.md)
* [Production readiness](docs/PRODUCTION_READINESS_M9.md)
* [CLI guide](docs/CLI_M10.md)

## License

SiloFS is licensed under the [Apache License 2.0](LICENSE).
