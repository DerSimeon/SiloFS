import base64
import hashlib
import os
import sys
import time
import uuid

import boto3
import requests
from botocore.config import Config
from botocore.exceptions import ClientError

ENDPOINT = os.environ["S3_ENDPOINT"]
ACCESS_KEY = os.environ["AWS_ACCESS_KEY_ID"]
SECRET_KEY = os.environ["AWS_SECRET_ACCESS_KEY"]
REGION = os.environ.get("AWS_DEFAULT_REGION", "us-east-1")
MODE = os.environ.get("COMPAT_MODE", "contract")
PART_SIZE = 6 * 1024 * 1024


def client(path_style=True):
    style = "path" if path_style else "virtual"
    return boto3.client(
        "s3",
        endpoint_url=ENDPOINT,
        aws_access_key_id=ACCESS_KEY,
        aws_secret_access_key=SECRET_KEY,
        region_name=REGION,
        config=Config(s3={"addressing_style": style}, signature_version="s3v4"),
    )


def bucket(prefix):
    return f"m6-{prefix}-{uuid.uuid4().hex[:8]}"


def expect_client_error(fn, expected_codes):
    try:
        fn()
    except ClientError as exc:
        code = exc.response["Error"]["Code"]
        status = exc.response["ResponseMetadata"]["HTTPStatusCode"]
        if code in expected_codes or str(status) in expected_codes:
            return
        raise AssertionError(f"unexpected S3 error code={code} status={status}") from exc
    raise AssertionError("expected S3 client error")


def sha256_b64(data):
    return base64.b64encode(hashlib.sha256(data).digest()).decode("ascii")


def run_contract():
    s3 = client(path_style=True)
    name = bucket("boto3")

    s3.create_bucket(Bucket=name)
    s3.head_bucket(Bucket=name)
    assert any(b["Name"] == name for b in s3.list_buckets()["Buckets"])
    s3.get_bucket_location(Bucket=name)
    expect_client_error(lambda: s3.head_bucket(Bucket=bucket("missing")), {"NoSuchBucket", "404"})

    payload = b"hello from boto3"
    s3.put_object(
        Bucket=name,
        Key="objects/hello.txt",
        Body=payload,
        ContentType="text/plain",
        Metadata={"client": "boto3"},
        ChecksumSHA256=sha256_b64(payload),
    )
    head = s3.head_object(Bucket=name, Key="objects/hello.txt")
    assert head["ContentLength"] == len(payload)
    assert head["ContentType"] == "text/plain"
    assert head["Metadata"]["client"] == "boto3"
    assert s3.get_object(Bucket=name, Key="objects/hello.txt")["Body"].read() == payload
    expect_client_error(lambda: s3.head_object(Bucket=name, Key="missing.txt"), {"NoSuchKey", "404"})

    range_body = bytes(range(256))
    s3.put_object(Bucket=name, Key="objects/range.bin", Body=range_body)
    got_range = s3.get_object(Bucket=name, Key="objects/range.bin", Range="bytes=10-19")["Body"].read()
    assert got_range == range_body[10:20]

    for idx, key in enumerate(["prefix/a.txt", "prefix/b.txt", "prefix/nested/c.txt", "weird space 日本.txt"]):
        s3.put_object(Bucket=name, Key=key, Body=f"v{idx}".encode())
    listed = s3.list_objects_v2(Bucket=name, Prefix="prefix/", Delimiter="/")
    keys = {item["Key"] for item in listed.get("Contents", [])}
    prefixes = {item["Prefix"] for item in listed.get("CommonPrefixes", [])}
    assert {"prefix/a.txt", "prefix/b.txt"}.issubset(keys)
    assert "prefix/nested/" in prefixes

    s3.copy_object(Bucket=name, Key="objects/copied.txt", CopySource=f"{name}/objects/hello.txt")
    assert s3.get_object(Bucket=name, Key="objects/copied.txt")["Body"].read() == payload

    part_payload = b"A" * PART_SIZE
    init = s3.create_multipart_upload(Bucket=name, Key="multipart.bin")
    upload_id = init["UploadId"]
    part = s3.upload_part(Bucket=name, Key="multipart.bin", UploadId=upload_id, PartNumber=1, Body=part_payload)
    assert len(s3.list_parts(Bucket=name, Key="multipart.bin", UploadId=upload_id)["Parts"]) == 1
    assert any(u["UploadId"] == upload_id for u in s3.list_multipart_uploads(Bucket=name).get("Uploads", []))
    s3.complete_multipart_upload(
        Bucket=name,
        Key="multipart.bin",
        UploadId=upload_id,
        MultipartUpload={"Parts": [{"PartNumber": 1, "ETag": part["ETag"]}]},
    )
    assert s3.head_object(Bucket=name, Key="multipart.bin")["ContentLength"] == PART_SIZE

    abort = s3.create_multipart_upload(Bucket=name, Key="abort.bin")
    s3.abort_multipart_upload(Bucket=name, Key="abort.bin", UploadId=abort["UploadId"])

    s3.put_object(Bucket=name, Key="copy-source.bin", Body=part_payload)
    copy_init = s3.create_multipart_upload(Bucket=name, Key="copy-dest.bin")
    copy_part = s3.upload_part_copy(
        Bucket=name,
        Key="copy-dest.bin",
        UploadId=copy_init["UploadId"],
        PartNumber=1,
        CopySource=f"{name}/copy-source.bin",
    )
    assert copy_part["CopyPartResult"]["ETag"]
    s3.abort_multipart_upload(Bucket=name, Key="copy-dest.bin", UploadId=copy_init["UploadId"])

    get_url = s3.generate_presigned_url("get_object", Params={"Bucket": name, "Key": "objects/hello.txt"}, ExpiresIn=300)
    assert requests.get(get_url, timeout=10).content == payload
    put_url = s3.generate_presigned_url("put_object", Params={"Bucket": name, "Key": "presigned-put.txt"}, ExpiresIn=300)
    put_resp = requests.put(put_url, data=b"presigned", timeout=10)
    assert put_resp.status_code == 200, put_resp.text
    assert s3.get_object(Bucket=name, Key="presigned-put.txt")["Body"].read() == b"presigned"

    s3.delete_object(Bucket=name, Key="objects/hello.txt")
    expect_client_error(lambda: s3.head_object(Bucket=name, Key="objects/hello.txt"), {"NoSuchKey", "404"})
    print("COMPAT PASS boto3 contract")


def run_detection():
    name = bucket("detect-boto3")
    try:
        c = client(path_style=False)
        c.create_bucket(Bucket=name)
        print("DETECTION boto3 virtual-host=pass")
    except Exception as exc:
        print(f"DETECTION boto3 virtual-host=unsupported reason={type(exc).__name__}")
    print("DETECTION boto3 streaming-sigv4=not-emitted-by-default")
    print("COMPAT PASS boto3 detection")


if __name__ == "__main__":
    if MODE == "detect":
        run_detection()
    else:
        run_contract()
