# silofs CLI

Build a static Linux binary:

```bash
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "-s -w -X main.version=0.15.0" -o silofs .
```

Or install a published Debian package:

```bash
curl -1sLf "https://dl.cloudsmith.io/public/<owner>/<repo>/setup.deb.sh" | sudo -E bash
sudo apt update
sudo apt install silofs
```

The root command accepts flags first, then `SILOS_*` or `SILOFS_*`
environment variables, then compatible `S3_*` environment variables, then an
optional `--from-env PATH` file, then stored CLI config.

Examples:

```bash
silofs configure
silofs admin configure --from-env /opt/silofs/.env
silofs --endpoint http://127.0.0.1:8080 mb s3://photos
silofs cp ./image.jpg s3://photos/image.jpg
silofs stat s3://photos/image.jpg
silofs cat s3://photos/image.jpg > image.jpg
silofs presign get s3://photos/image.jpg --expires 15m
silofs admin inspect buckets --db-url jdbc:postgresql://localhost:5432/silofs
silofs admin check-blobs --data-dir /var/lib/silofs/data
silofs admin grant add --access-key-id AKIA... --bucket photos --permission READ
```

`silofs configure` and its `login` alias prompt for endpoint, region, access
key ID, and secret access key, then store them in the OS user config directory.
The stored file is an env-style file with restrictive permissions where the OS
supports them. It may contain plaintext secrets.

Access-key create and rotate commands require `SILOS_ACCESS_KEY_SECRET_ENCRYPTION_KEY`
or `S3_ACCESS_KEY_SECRET_ENCRYPTION_KEY`. The generated secret is printed once;
the database stores only encrypted secret material.

`admin repair` and `admin gc` intentionally require `--dry-run`; M10 does not perform mutating repair or GC from the CLI.
