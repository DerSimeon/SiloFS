import {
  AbortMultipartUploadCommand,
  CompleteMultipartUploadCommand,
  CopyObjectCommand,
  CreateBucketCommand,
  CreateMultipartUploadCommand,
  DeleteObjectCommand,
  GetBucketLocationCommand,
  GetObjectCommand,
  HeadBucketCommand,
  HeadObjectCommand,
  ListBucketsCommand,
  ListMultipartUploadsCommand,
  ListObjectsV2Command,
  ListPartsCommand,
  PutObjectCommand,
  S3Client,
  UploadPartCommand,
  UploadPartCopyCommand,
} from "@aws-sdk/client-s3";
import { getSignedUrl } from "@aws-sdk/s3-request-presigner";
import { createHash, randomUUID } from "crypto";

const ENDPOINT = process.env.S3_ENDPOINT;
const REGION = process.env.AWS_DEFAULT_REGION || "us-east-1";
const MODE = process.env.COMPAT_MODE || "contract";
const PART_SIZE = 6 * 1024 * 1024;

function s3(forcePathStyle = true) {
  return new S3Client({
    region: REGION,
    endpoint: ENDPOINT,
    forcePathStyle,
    credentials: {
      accessKeyId: process.env.AWS_ACCESS_KEY_ID,
      secretAccessKey: process.env.AWS_SECRET_ACCESS_KEY,
    },
  });
}

function bucket(prefix) {
  return `m6-${prefix}-${randomUUID().slice(0, 8)}`;
}

function sha256Base64(bytes) {
  return createHash("sha256").update(bytes).digest("base64");
}

async function bodyBytes(body) {
  return Buffer.from(await body.transformToByteArray());
}

async function expectS3Error(fn, expected) {
  try {
    await fn();
  } catch (err) {
    const code = err?.name;
    const status = `${err?.$metadata?.httpStatusCode}`;
    if (expected.includes(code) || expected.includes(status)) return;
    throw new Error(`unexpected S3 error code=${code} status=${status}`);
  }
  throw new Error("expected S3 error");
}

async function runContract() {
  const client = s3(true);
  const name = bucket("js");

  await client.send(new CreateBucketCommand({ Bucket: name }));
  await client.send(new HeadBucketCommand({ Bucket: name }));
  const buckets = await client.send(new ListBucketsCommand({}));
  if (!buckets.Buckets?.some((b) => b.Name === name)) throw new Error("bucket missing from ListBuckets");
  await client.send(new GetBucketLocationCommand({ Bucket: name }));
  await expectS3Error(() => client.send(new HeadBucketCommand({ Bucket: bucket("missing") })), ["NoSuchBucket", "404"]);

  const payload = Buffer.from("hello from javascript");
  await client.send(new PutObjectCommand({
    Bucket: name,
    Key: "objects/hello.txt",
    Body: payload,
    ContentType: "text/plain",
    Metadata: { client: "javascript-v3" },
    ChecksumSHA256: sha256Base64(payload),
  }));
  const head = await client.send(new HeadObjectCommand({ Bucket: name, Key: "objects/hello.txt" }));
  if (head.ContentLength !== payload.length || head.ContentType !== "text/plain" || head.Metadata.client !== "javascript-v3") {
    throw new Error("head object metadata mismatch");
  }
  const got = await client.send(new GetObjectCommand({ Bucket: name, Key: "objects/hello.txt" }));
  if (!payload.equals(await bodyBytes(got.Body))) throw new Error("get object mismatch");
  await expectS3Error(() => client.send(new HeadObjectCommand({ Bucket: name, Key: "missing.txt" })), ["NoSuchKey", "404"]);

  const rangeBody = Buffer.from(Array.from({ length: 256 }, (_, i) => i));
  await client.send(new PutObjectCommand({ Bucket: name, Key: "objects/range.bin", Body: rangeBody }));
  const range = await client.send(new GetObjectCommand({ Bucket: name, Key: "objects/range.bin", Range: "bytes=10-19" }));
  if (!rangeBody.subarray(10, 20).equals(await bodyBytes(range.Body))) throw new Error("range mismatch");

  for (const [idx, key] of ["prefix/a.txt", "prefix/b.txt", "prefix/nested/c.txt", "weird space 日本.txt"].entries()) {
    await client.send(new PutObjectCommand({ Bucket: name, Key: key, Body: Buffer.from(`v${idx}`) }));
  }
  const listed = await client.send(new ListObjectsV2Command({ Bucket: name, Prefix: "prefix/", Delimiter: "/" }));
  const keys = new Set((listed.Contents || []).map((item) => item.Key));
  const prefixes = new Set((listed.CommonPrefixes || []).map((item) => item.Prefix));
  if (!keys.has("prefix/a.txt") || !keys.has("prefix/b.txt") || !prefixes.has("prefix/nested/")) throw new Error("listing mismatch");

  await client.send(new CopyObjectCommand({ Bucket: name, Key: "objects/copied.txt", CopySource: `${name}/objects/hello.txt` }));
  const copied = await client.send(new GetObjectCommand({ Bucket: name, Key: "objects/copied.txt" }));
  if (!payload.equals(await bodyBytes(copied.Body))) throw new Error("copy mismatch");

  const partPayload = Buffer.alloc(PART_SIZE, 0x41);
  const init = await client.send(new CreateMultipartUploadCommand({ Bucket: name, Key: "multipart.bin" }));
  const part = await client.send(new UploadPartCommand({
    Bucket: name,
    Key: "multipart.bin",
    UploadId: init.UploadId,
    PartNumber: 1,
    Body: partPayload,
    ContentLength: partPayload.length,
  }));
  const parts = await client.send(new ListPartsCommand({ Bucket: name, Key: "multipart.bin", UploadId: init.UploadId }));
  if (parts.Parts?.length !== 1) throw new Error("list parts mismatch");
  const uploads = await client.send(new ListMultipartUploadsCommand({ Bucket: name }));
  if (!uploads.Uploads?.some((u) => u.UploadId === init.UploadId)) throw new Error("list multipart uploads mismatch");
  await client.send(new CompleteMultipartUploadCommand({
    Bucket: name,
    Key: "multipart.bin",
    UploadId: init.UploadId,
    MultipartUpload: { Parts: [{ PartNumber: 1, ETag: part.ETag }] },
  }));
  const mpuHead = await client.send(new HeadObjectCommand({ Bucket: name, Key: "multipart.bin" }));
  if (mpuHead.ContentLength !== PART_SIZE) throw new Error("multipart size mismatch");

  const abort = await client.send(new CreateMultipartUploadCommand({ Bucket: name, Key: "abort.bin" }));
  await client.send(new AbortMultipartUploadCommand({ Bucket: name, Key: "abort.bin", UploadId: abort.UploadId }));

  await client.send(new PutObjectCommand({ Bucket: name, Key: "copy-source.bin", Body: partPayload }));
  const copyInit = await client.send(new CreateMultipartUploadCommand({ Bucket: name, Key: "copy-dest.bin" }));
  const copyPart = await client.send(new UploadPartCopyCommand({
    Bucket: name,
    Key: "copy-dest.bin",
    UploadId: copyInit.UploadId,
    PartNumber: 1,
    CopySource: `${name}/copy-source.bin`,
  }));
  if (!copyPart.CopyPartResult?.ETag) throw new Error("upload part copy missing etag");
  await client.send(new AbortMultipartUploadCommand({ Bucket: name, Key: "copy-dest.bin", UploadId: copyInit.UploadId }));

  const getUrl = await getSignedUrl(client, new GetObjectCommand({ Bucket: name, Key: "objects/hello.txt" }), { expiresIn: 300 });
  if (!payload.equals(Buffer.from(await (await fetch(getUrl)).arrayBuffer()))) throw new Error("presigned get mismatch");
  const putUrl = await getSignedUrl(client, new PutObjectCommand({ Bucket: name, Key: "presigned-put.txt" }), { expiresIn: 300 });
  const putResponse = await fetch(putUrl, { method: "PUT", body: Buffer.from("presigned") });
  if (putResponse.status !== 200) throw new Error(`presigned put status ${putResponse.status}`);
  const presigned = await client.send(new GetObjectCommand({ Bucket: name, Key: "presigned-put.txt" }));
  if (Buffer.from("presigned").compare(await bodyBytes(presigned.Body)) !== 0) throw new Error("presigned put mismatch");

  await client.send(new DeleteObjectCommand({ Bucket: name, Key: "objects/hello.txt" }));
  await expectS3Error(() => client.send(new HeadObjectCommand({ Bucket: name, Key: "objects/hello.txt" })), ["NoSuchKey", "404"]);
  console.log("COMPAT PASS javascript-v3 contract");
}

async function runDetection() {
  const name = bucket("detect-js");
  try {
    await s3(false).send(new CreateBucketCommand({ Bucket: name }));
    console.log("DETECTION javascript-v3 virtual-host=pass");
  } catch (err) {
    console.log(`DETECTION javascript-v3 virtual-host=unsupported reason=${err.name || err.constructor.name}`);
  }
  console.log("DETECTION javascript-v3 streaming-sigv4=not-required-with-Buffer-bodies");
  console.log("COMPAT PASS javascript-v3 detection");
}

if (MODE === "detect") {
  await runDetection();
} else {
  await runContract();
}
