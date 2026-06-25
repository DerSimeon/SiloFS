set -eu

ENDPOINT="${S3_ENDPOINT:?}"
REGION="${AWS_DEFAULT_REGION:-us-east-1}"
PART_SIZE=6291456
BUCKET="m6-cli-$(date +%s)-$$"
CONFIG_FILE=/tmp/aws-config

cat > "$CONFIG_FILE" <<EOF
[default]
region = $REGION
s3 =
    addressing_style = path
EOF

export AWS_CONFIG_FILE="$CONFIG_FILE"
export AWS_EC2_METADATA_DISABLED=true

aws_s3() {
  aws --endpoint-url "$ENDPOINT" --region "$REGION" s3api "$@"
}

expect_error() {
  if "$@" >/tmp/expect_error.out 2>/tmp/expect_error.err; then
    echo "expected command to fail: $*" >&2
    exit 1
  fi
}

aws_s3 create-bucket --bucket "$BUCKET" >/dev/null
aws_s3 head-bucket --bucket "$BUCKET" >/dev/null
aws_s3 list-buckets --query "Buckets[?Name=='$BUCKET'].Name" --output text | grep "$BUCKET" >/dev/null
aws_s3 get-bucket-location --bucket "$BUCKET" >/dev/null
expect_error aws_s3 head-bucket --bucket "m6-cli-missing-$$"

printf "hello from aws cli" > /tmp/hello.txt
aws_s3 put-object \
  --bucket "$BUCKET" \
  --key objects/hello.txt \
  --body /tmp/hello.txt \
  --content-type text/plain \
  --metadata client=aws-cli >/dev/null

test "$(aws_s3 head-object --bucket "$BUCKET" --key objects/hello.txt --query ContentLength --output text)" = "18"
test "$(aws_s3 head-object --bucket "$BUCKET" --key objects/hello.txt --query ContentType --output text)" = "text/plain"
test "$(aws_s3 head-object --bucket "$BUCKET" --key objects/hello.txt --query Metadata.client --output text)" = "aws-cli"
aws_s3 get-object --bucket "$BUCKET" --key objects/hello.txt /tmp/hello.out >/dev/null
cmp /tmp/hello.txt /tmp/hello.out
expect_error aws_s3 head-object --bucket "$BUCKET" --key missing.txt

printf "0123456789abcdefghijklmnopqrstuvwxyz" > /tmp/range.bin
aws_s3 put-object --bucket "$BUCKET" --key objects/range.bin --body /tmp/range.bin >/dev/null
aws_s3 get-object --bucket "$BUCKET" --key objects/range.bin --range bytes=10-19 /tmp/range.out >/dev/null
printf "abcdefghij" > /tmp/range.expected
cmp /tmp/range.expected /tmp/range.out

for key in prefix/a.txt prefix/b.txt prefix/nested/c.txt "weird space.txt"; do
  printf "%s" "$key" > /tmp/list-item.txt
  aws_s3 put-object --bucket "$BUCKET" --key "$key" --body /tmp/list-item.txt >/dev/null
done
aws_s3 list-objects-v2 --bucket "$BUCKET" --prefix prefix/ --delimiter / --query "Contents[].Key" --output text | grep "prefix/a.txt" >/dev/null
aws_s3 list-objects-v2 --bucket "$BUCKET" --prefix prefix/ --delimiter / --query "CommonPrefixes[].Prefix" --output text | grep "prefix/nested/" >/dev/null

aws_s3 copy-object --bucket "$BUCKET" --key objects/copied.txt --copy-source "$BUCKET/objects/hello.txt" >/dev/null
aws_s3 get-object --bucket "$BUCKET" --key objects/copied.txt /tmp/copied.out >/dev/null
cmp /tmp/hello.txt /tmp/copied.out

dd if=/dev/zero of=/tmp/part.bin bs=1048576 count=6 >/dev/null 2>&1
UPLOAD_ID="$(aws_s3 create-multipart-upload --bucket "$BUCKET" --key multipart.bin --query UploadId --output text)"
ETAG="$(aws_s3 upload-part --bucket "$BUCKET" --key multipart.bin --upload-id "$UPLOAD_ID" --part-number 1 --body /tmp/part.bin --query ETag --output text)"
test "$(aws_s3 list-parts --bucket "$BUCKET" --key multipart.bin --upload-id "$UPLOAD_ID" --query 'length(Parts)' --output text)" = "1"
aws_s3 list-multipart-uploads --bucket "$BUCKET" --query "Uploads[?UploadId=='$UPLOAD_ID'].UploadId" --output text | grep "$UPLOAD_ID" >/dev/null
cat > /tmp/complete.json <<EOF
{"Parts":[{"PartNumber":1,"ETag":$ETAG}]}
EOF
aws_s3 complete-multipart-upload --bucket "$BUCKET" --key multipart.bin --upload-id "$UPLOAD_ID" --multipart-upload file:///tmp/complete.json >/dev/null
test "$(aws_s3 head-object --bucket "$BUCKET" --key multipart.bin --query ContentLength --output text)" = "$PART_SIZE"

ABORT_ID="$(aws_s3 create-multipart-upload --bucket "$BUCKET" --key abort.bin --query UploadId --output text)"
aws_s3 abort-multipart-upload --bucket "$BUCKET" --key abort.bin --upload-id "$ABORT_ID" >/dev/null

aws_s3 put-object --bucket "$BUCKET" --key copy-source.bin --body /tmp/part.bin >/dev/null
COPY_ID="$(aws_s3 create-multipart-upload --bucket "$BUCKET" --key copy-dest.bin --query UploadId --output text)"
aws_s3 upload-part-copy --bucket "$BUCKET" --key copy-dest.bin --upload-id "$COPY_ID" --part-number 1 --copy-source "$BUCKET/copy-source.bin" >/dev/null
aws_s3 abort-multipart-upload --bucket "$BUCKET" --key copy-dest.bin --upload-id "$COPY_ID" >/dev/null

aws_s3 delete-object --bucket "$BUCKET" --key objects/hello.txt >/dev/null
expect_error aws_s3 head-object --bucket "$BUCKET" --key objects/hello.txt

echo "DETECTION aws-cli virtual-host=not-run path-style-required-configured"
echo "DETECTION aws-cli streaming-sigv4=not-required-for-file-body"
echo "COMPAT PASS aws-cli contract"
