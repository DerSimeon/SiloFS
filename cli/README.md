# silofs CLI

Build a static Linux binary:

```bash
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o silofs .
```

The root command accepts flags first, then `SILOS_*` environment variables, then compatible `S3_*` environment variables.

Examples:

```bash
silofs --endpoint http://127.0.0.1:8080 mb s3://photos
silofs cp ./image.jpg s3://photos/image.jpg
silofs stat s3://photos/image.jpg
silofs cat s3://photos/image.jpg > image.jpg
silofs presign get s3://photos/image.jpg --expires 15m
silofs admin inspect buckets --db-url jdbc:postgresql://localhost:5432/silofs
silofs admin check-blobs --data-dir /var/lib/silofs/data
```

`admin repair` and `admin gc` intentionally require `--dry-run`; M10 does not perform mutating repair or GC from the CLI.
