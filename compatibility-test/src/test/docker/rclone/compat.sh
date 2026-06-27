set -eu

ENDPOINT="${S3_ENDPOINT:?}"
ACCESS_KEY="${AWS_ACCESS_KEY_ID:?}"
SECRET_KEY="${AWS_SECRET_ACCESS_KEY:?}"
REGION="${AWS_DEFAULT_REGION:-us-east-1}"
BUCKET="m10-rclone-$(date +%s)-$$"
REMOTE=silofs
PART_SIZE=6291456

expect_error() {
  if "$@" >/tmp/expect_error.out 2>/tmp/expect_error.err; then
    echo "expected command to fail: $*" >&2
    exit 1
  fi
}

rclone version
rclone config create "$REMOTE" s3 provider Other env_auth false access_key_id "$ACCESS_KEY" secret_access_key "$SECRET_KEY" endpoint "$ENDPOINT" region "$REGION" >/dev/null

rclone mkdir "$REMOTE:$BUCKET"
rclone lsd "$REMOTE:" | grep "$BUCKET" >/dev/null
echo "DETECTION rclone missing-bucket-error=not-exposed-cleanly-by-lsf"

printf "hello from rclone" > /tmp/hello.txt
rclone copyto /tmp/hello.txt "$REMOTE:$BUCKET/objects/hello.txt" --metadata-set "client=rclone" --metadata-set "content-type=text/plain"
rclone cat "$REMOTE:$BUCKET/objects/hello.txt" > /tmp/hello.out
cmp /tmp/hello.txt /tmp/hello.out
rclone lsf "$REMOTE:$BUCKET/objects/" | grep "hello.txt" >/dev/null
echo "DETECTION rclone missing-object-error=not-exposed-cleanly-by-copyto"

printf "0123456789abcdefghijklmnopqrstuvwxyz" > /tmp/range.bin
rclone copyto /tmp/range.bin "$REMOTE:$BUCKET/objects/range.bin"
rclone cat "$REMOTE:$BUCKET/objects/range.bin" --offset 10 --count 10 > /tmp/range.out
printf "abcdefghij" > /tmp/range.expected
cmp /tmp/range.expected /tmp/range.out

for key in prefix/a.txt prefix/b.txt prefix/nested/c.txt "weird space.txt"; do
  printf "%s" "$key" > /tmp/list-item.txt
  rclone copyto /tmp/list-item.txt "$REMOTE:$BUCKET/$key"
done
rclone lsf "$REMOTE:$BUCKET/prefix/" | grep "a.txt" >/dev/null
rclone lsf "$REMOTE:$BUCKET/prefix/" | grep "nested/" >/dev/null

rclone copyto "$REMOTE:$BUCKET/objects/hello.txt" "$REMOTE:$BUCKET/objects/copied.txt"
rclone cat "$REMOTE:$BUCKET/objects/copied.txt" > /tmp/copied.out
cmp /tmp/hello.txt /tmp/copied.out

dd if=/dev/zero of=/tmp/large.bin bs=1048576 count=6 >/dev/null 2>&1
rclone copyto /tmp/large.bin "$REMOTE:$BUCKET/multipart.bin" --s3-upload-cutoff 5M --s3-chunk-size 6M
test "$(rclone size --json "$REMOTE:$BUCKET/multipart.bin" | sed -n 's/.*"bytes":\([0-9]*\).*/\1/p')" = "$PART_SIZE"

rclone purge "$REMOTE:$BUCKET"

echo "DETECTION rclone virtual-host=not-run path-style-required-configured"
echo "DETECTION rclone streaming-sigv4=not-required-for-file-body"
echo "COMPAT PASS rclone contract"
