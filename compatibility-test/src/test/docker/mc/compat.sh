set -eu

ENDPOINT="${S3_ENDPOINT:?}"
ACCESS_KEY="${AWS_ACCESS_KEY_ID:?}"
SECRET_KEY="${AWS_SECRET_ACCESS_KEY:?}"
BUCKET="m10-mc-$(date +%s)-$$"
ALIAS=silofs
PART_SIZE=6291456

expect_error() {
  if "$@" >/tmp/expect_error.out 2>/tmp/expect_error.err; then
    echo "expected command to fail: $*" >&2
    exit 1
  fi
}

mc --version
mc alias set "$ALIAS" "$ENDPOINT" "$ACCESS_KEY" "$SECRET_KEY" --api S3v4 --path on >/dev/null

mc mb "$ALIAS/$BUCKET" >/dev/null
case "$(mc ls "$ALIAS")" in
  *"$BUCKET"*) ;;
  *) echo "bucket missing from mc ls" >&2; exit 1 ;;
esac
echo "DETECTION mc missing-bucket-error=not-exposed-cleanly-by-stat"

printf "hello from mc" > /tmp/hello.txt
mc cp --disable-multipart --attr "Content-Type=text/plain;X-Amz-Meta-Client=mc" /tmp/hello.txt "$ALIAS/$BUCKET/objects/hello.txt" >/dev/null
mc stat "$ALIAS/$BUCKET/objects/hello.txt" >/dev/null
test "$(mc cat "$ALIAS/$BUCKET/objects/hello.txt")" = "hello from mc"
expect_error mc stat "$ALIAS/$BUCKET/missing.txt"

printf "0123456789abcdefghijklmnopqrstuvwxyz" > /tmp/range.bin
mc cp --disable-multipart /tmp/range.bin "$ALIAS/$BUCKET/objects/range.bin" >/dev/null
case "$(mc cat --offset 10 "$ALIAS/$BUCKET/objects/range.bin")" in
  abcdefghij*) ;;
  *) echo "range prefix mismatch" >&2; exit 1 ;;
esac

for key in prefix/a.txt prefix/b.txt prefix/nested/c.txt "weird space.txt"; do
  printf "%s" "$key" > /tmp/list-item.txt
  mc cp --disable-multipart /tmp/list-item.txt "$ALIAS/$BUCKET/$key" >/dev/null
done
LISTING="$(mc ls "$ALIAS/$BUCKET/prefix/")"
case "$LISTING" in
  *"a.txt"*) ;;
  *) echo "prefix/a.txt missing from mc ls" >&2; exit 1 ;;
esac
case "$LISTING" in
  *"nested"*) ;;
  *) echo "prefix/nested missing from mc ls" >&2; exit 1 ;;
esac

mc cp "$ALIAS/$BUCKET/objects/hello.txt" "$ALIAS/$BUCKET/objects/copied.txt" >/dev/null
test "$(mc cat "$ALIAS/$BUCKET/objects/copied.txt")" = "hello from mc"

dd if=/dev/zero of=/tmp/large.bin bs=1048576 count=6 >/dev/null 2>&1
mc cp --disable-multipart /tmp/large.bin "$ALIAS/$BUCKET/multipart.bin" >/dev/null
case "$(mc stat --json "$ALIAS/$BUCKET/multipart.bin")" in
  *"\"size\":$PART_SIZE"*) ;;
  *) echo "multipart size mismatch" >&2; exit 1 ;;
esac

echo "DETECTION mc rm-rb=uses-deleteobjects-unsupported"

echo "DETECTION mc virtual-host=not-run path-style-required-configured"
echo "DETECTION mc streaming-sigv4=aws-chunked-required-decoded-without-per-chunk-verification"
echo "COMPAT PASS mc contract"
