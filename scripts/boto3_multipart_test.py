#!/usr/bin/env python3
"""
Milestone 3 — boto3 multipart upload test.

This script exercises the s3-server's multipart upload implementation
through boto3, the official AWS SDK for Python. It mirrors the AWS SDK
for Java v2 tests in S3ServerM3Test.kt but uses the Python client so we
can verify cross-SDK compatibility.

Usage:
    S3_ENDPOINT=http://localhost:8080 \
    AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE \
    AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY \
    AWS_DEFAULT_REGION=us-east-1 \
    python3 scripts/boto3_multipart_test.py

Exit code 0 = all tests passed; non-zero = failure.

Requirements:
    pip install boto3
"""
import os
import sys
import uuid
import hashlib

try:
    import boto3
    from botocore.config import Config
    from botocore.exceptions import ClientError
except ImportError:
    print("boto3 is not installed. Install with: pip install boto3", file=sys.stderr)
    sys.exit(2)


ENDPOINT = os.environ.get("S3_ENDPOINT", "http://localhost:8080")
ACCESS_KEY = os.environ.get("AWS_ACCESS_KEY_ID", "AKIAIOSFODNN7EXAMPLE")
SECRET_KEY = os.environ.get("AWS_SECRET_ACCESS_KEY", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
REGION = os.environ.get("AWS_DEFAULT_REGION", "us-east-1")

PART_SIZE = 6 * 1024 * 1024  # 6 MiB


def make_client():
    return boto3.client(
        "s3",
        endpoint_url=ENDPOINT,
        aws_access_key_id=ACCESS_KEY,
        aws_secret_access_key=SECRET_KEY,
        region_name=REGION,
        config=Config(s3={"addressing_style": "path"}, signature_version="s3v4"),
    )


def new_bucket(s3):
    name = f"boto3-m3-{uuid.uuid4().hex[:8].lower()}"
    s3.create_bucket(Bucket=name)
    return name


def assert_eq(a, b, msg=""):
    if a != b:
        raise AssertionError(f"{msg}: expected {a!r}, got {b!r}")


def test_full_multipart_upload(s3):
    bucket = new_bucket(s3)
    key = "large.bin"
    part_count = 3

    init = s3.create_multipart_upload(Bucket=bucket, Key=key, ContentType="application/octet-stream")
    upload_id = init["UploadId"]

    parts = []
    for i in range(1, part_count + 1):
        payload = bytes([i & 0xFF] * PART_SIZE)
        r = s3.upload_part(
            Bucket=bucket, Key=key, UploadId=upload_id,
            PartNumber=i, Body=payload,
        )
        parts.append({"PartNumber": i, "ETag": r["ETag"]})

    complete = s3.complete_multipart_upload(
        Bucket=bucket, Key=key, UploadId=upload_id,
        MultipartUpload={"Parts": parts},
    )
    etag = complete["ETag"]
    assert f"-{part_count}" in etag, f"multipart ETag must contain -{part_count}: {etag}"

    head = s3.head_object(Bucket=bucket, Key=key)
    assert_eq(PART_SIZE * part_count, head["ContentLength"], "size")
    assert_eq("application/octet-stream", head["ContentType"], "content-type")

    got = s3.get_object(Bucket=bucket, Key=key)["Body"].read()
    assert_eq(PART_SIZE * part_count, len(got), "downloaded size")
    for i in range(1, part_count + 1):
        slice = got[(i - 1) * PART_SIZE : i * PART_SIZE]
        assert all(b == (i & 0xFF) for b in slice), f"part {i} content mismatch"
    print("  [PASS] full multipart upload")


def test_abort(s3):
    bucket = new_bucket(s3)
    key = "aborted.bin"
    init = s3.create_multipart_upload(Bucket=bucket, Key=key)
    upload_id = init["UploadId"]

    s3.upload_part(
        Bucket=bucket, Key=key, UploadId=upload_id,
        PartNumber=1, Body=bytes([0x01] * PART_SIZE),
    )
    s3.abort_multipart_upload(Bucket=bucket, Key=key, UploadId=upload_id)

    try:
        s3.head_object(Bucket=bucket, Key=key)
        raise AssertionError("expected NoSuchKey after abort")
    except ClientError as e:
        assert e.response["Error"]["Code"] == "404", f"expected 404, got {e}"
    print("  [PASS] abort upload")


def test_list_parts(s3):
    bucket = new_bucket(s3)
    key = "listed.bin"
    init = s3.create_multipart_upload(Bucket=bucket, Key=key)
    upload_id = init["UploadId"]

    for i in range(1, 4):
        s3.upload_part(
            Bucket=bucket, Key=key, UploadId=upload_id,
            PartNumber=i, Body=bytes([i] * PART_SIZE),
        )

    resp = s3.list_parts(Bucket=bucket, Key=key, UploadId=upload_id)
    assert_eq(3, len(resp["Parts"]), "part count")
    assert_eq(1, resp["Parts"][0]["PartNumber"])
    assert_eq(2, resp["Parts"][1]["PartNumber"])
    assert_eq(3, resp["Parts"][2]["PartNumber"])

    s3.abort_multipart_upload(Bucket=bucket, Key=key, UploadId=upload_id)
    print("  [PASS] list parts")


def test_invalid_upload_id(s3):
    bucket = new_bucket(s3)
    try:
        s3.upload_part(
            Bucket=bucket, Key="k", UploadId="nonexistent",
            PartNumber=1, Body=b"hello",
        )
        raise AssertionError("expected NoSuchUpload")
    except ClientError as e:
        code = e.response["Error"]["Code"]
        assert code in ("NoSuchUpload", "404"), f"expected NoSuchUpload, got {code}"
    print("  [PASS] invalid upload id")


def test_missing_part(s3):
    bucket = new_bucket(s3)
    key = "missing-part.bin"
    init = s3.create_multipart_upload(Bucket=bucket, Key=key)
    upload_id = init["UploadId"]

    e1 = s3.upload_part(
        Bucket=bucket, Key=key, UploadId=upload_id,
        PartNumber=1, Body=bytes([0x01] * PART_SIZE),
    )["ETag"]
    e3 = s3.upload_part(
        Bucket=bucket, Key=key, UploadId=upload_id,
        PartNumber=3, Body=bytes([0x03] * PART_SIZE),
    )["ETag"]

    try:
        s3.complete_multipart_upload(
            Bucket=bucket, Key=key, UploadId=upload_id,
            MultipartUpload={"Parts": [
                {"PartNumber": 1, "ETag": e1},
                {"PartNumber": 2, "ETag": '"fake"'},
                {"PartNumber": 3, "ETag": e3},
            ]},
        )
        raise AssertionError("expected error for missing part 2")
    except ClientError as e:
        code = e.response["Error"]["Code"]
        assert code in ("NoSuchPart", "InvalidPart"), f"expected NoSuchPart/InvalidPart, got {code}"

    s3.abort_multipart_upload(Bucket=bucket, Key=key, UploadId=upload_id)
    print("  [PASS] missing part")


def test_re_upload_part(s3):
    bucket = new_bucket(s3)
    key = "overwritten.bin"
    init = s3.create_multipart_upload(Bucket=bucket, Key=key)
    upload_id = init["UploadId"]

    e_a = s3.upload_part(
        Bucket=bucket, Key=key, UploadId=upload_id,
        PartNumber=1, Body=bytes([0xAA] * PART_SIZE),
    )["ETag"]
    e_b = s3.upload_part(
        Bucket=bucket, Key=key, UploadId=upload_id,
        PartNumber=1, Body=bytes([0xBB] * PART_SIZE),
    )["ETag"]
    assert e_a != e_b, "re-uploaded part should have different ETag"

    resp = s3.list_parts(Bucket=bucket, Key=key, UploadId=upload_id)
    assert_eq(1, len(resp["Parts"]))
    assert_eq(e_b, resp["Parts"][0]["ETag"])

    s3.complete_multipart_upload(
        Bucket=bucket, Key=key, UploadId=upload_id,
        MultipartUpload={"Parts": [{"PartNumber": 1, "ETag": e_b}]},
    )
    got = s3.get_object(Bucket=bucket, Key=key)["Body"].read()
    assert all(b == 0xBB for b in got), "overwritten part content mismatch"
    print("  [PASS] re-upload same part number")


def test_small_last_part(s3):
    bucket = new_bucket(s3)
    key = "small-last.bin"
    init = s3.create_multipart_upload(Bucket=bucket, Key=key)
    upload_id = init["UploadId"]

    e1 = s3.upload_part(
        Bucket=bucket, Key=key, UploadId=upload_id,
        PartNumber=1, Body=bytes([0x01] * PART_SIZE),
    )["ETag"]
    e2 = s3.upload_part(
        Bucket=bucket, Key=key, UploadId=upload_id,
        PartNumber=2, Body=bytes([0x02] * 1024),  # 1 KiB last part
    )["ETag"]

    s3.complete_multipart_upload(
        Bucket=bucket, Key=key, UploadId=upload_id,
        MultipartUpload={"Parts": [
            {"PartNumber": 1, "ETag": e1},
            {"PartNumber": 2, "ETag": e2},
        ]},
    )
    head = s3.head_object(Bucket=bucket, Key=key)
    assert_eq(PART_SIZE + 1024, head["ContentLength"])
    print("  [PASS] small last part")


def main():
    print(f"boto3 multipart test against {ENDPOINT}")
    s3 = make_client()
    tests = [
        test_full_multipart_upload,
        test_abort,
        test_list_parts,
        test_invalid_upload_id,
        test_missing_part,
        test_re_upload_part,
        test_small_last_part,
    ]
    failed = 0
    for t in tests:
        try:
            t(s3)
        except Exception as e:
            print(f"  [FAIL] {t.__name__}: {e}")
            failed += 1
    if failed:
        print(f"\n{failed} test(s) failed")
        sys.exit(1)
    print(f"\nAll {len(tests)} tests passed")


if __name__ == "__main__":
    main()
