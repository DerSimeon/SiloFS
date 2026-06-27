set -eu

ENDPOINT="${S3_ENDPOINT:?}"
REGION="${AWS_DEFAULT_REGION:-us-east-1}"
BUCKET="m10-s5cmd-$(date +%s)-$$"
PART_SIZE=6291456

s5cmd_bin() {
  /s5cmd "$@"
}

s5() {
  /s5cmd --endpoint-url "$ENDPOINT" "$@"
}

expect_error() {
  if s5 "$@" >/tmp/expect_error.out 2>/tmp/expect_error.err; then
    echo "expected command to fail: $*" >&2
    exit 1
  fi
}

s5cmd_bin version
s5 mb "s3://$BUCKET" >/dev/null
echo "DETECTION s5cmd list-buckets=not-supported-by-cli"
expect_error ls "s3://m10-s5cmd-missing-$$"

printf "hello from s5cmd" > /tmp/hello.txt
s5 cp /tmp/hello.txt "s3://$BUCKET/objects/hello.txt" >/dev/null
s5 head "s3://$BUCKET/objects/hello.txt" >/dev/null
test "$(s5 cat "s3://$BUCKET/objects/hello.txt")" = "hello from s5cmd"
expect_error head "s3://$BUCKET/missing.txt"

printf "0123456789abcdefghijklmnopqrstuvwxyz" > /tmp/range.bin
s5 cp /tmp/range.bin "s3://$BUCKET/objects/range.bin" >/dev/null
test "$(s5 cat "s3://$BUCKET/objects/range.bin")" = "0123456789abcdefghijklmnopqrstuvwxyz"
s5 pipe "s3://$BUCKET/objects/copied.txt" < /tmp/hello.txt >/dev/null
test "$(s5 cat "s3://$BUCKET/objects/copied.txt")" = "hello from s5cmd"

for key in prefix/a.txt prefix/b.txt prefix/nested/c.txt "weird space.txt"; do
  printf "%s" "$key" > /tmp/list-item.txt
  s5 cp /tmp/list-item.txt "s3://$BUCKET/$key" >/dev/null
done
LISTING="$(s5 ls "s3://$BUCKET/prefix/")"
case "$LISTING" in
  *"a.txt"*) ;;
  *) echo "prefix/a.txt missing from s5cmd ls" >&2; exit 1 ;;
esac
case "$LISTING" in
  *"nested"*) ;;
  *) echo "prefix/nested missing from s5cmd ls" >&2; exit 1 ;;
esac
echo "DETECTION s5cmd wildcard-listing=not-gated"

dd if=/dev/zero of=/tmp/large.bin bs=1048576 count=6 >/dev/null 2>&1
s5 cp --part-size 6 /tmp/large.bin "s3://$BUCKET/multipart.bin" >/dev/null
case "$(s5 head "s3://$BUCKET/multipart.bin")" in
  *"$PART_SIZE"*) ;;
  *) echo "multipart size missing from s5cmd head" >&2; exit 1 ;;
esac

echo "DETECTION s5cmd rm-rb=wildcard-delete-uses-deleteobjects-unsupported"

echo "DETECTION s5cmd virtual-host=not-run path-style-required-configured"
echo "DETECTION s5cmd streaming-sigv4=not-required-for-file-body"
echo "COMPAT PASS s5cmd contract"
