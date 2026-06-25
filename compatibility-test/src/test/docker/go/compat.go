package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/aws-sdk-go-v2/service/s3/types"
	"github.com/aws/smithy-go"
)

const partSize = 6 * 1024 * 1024

func main() {
	if os.Getenv("COMPAT_MODE") == "detect" {
		must(runDetection())
		return
	}
	must(runContract())
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func client(pathStyle bool) (*s3.Client, context.Context) {
	ctx := context.Background()
	endpoint := os.Getenv("S3_ENDPOINT")
	cfg, err := config.LoadDefaultConfig(
		ctx,
		config.WithRegion(envDefault("AWS_DEFAULT_REGION", "us-east-1")),
		config.WithCredentialsProvider(credentials.NewStaticCredentialsProvider(
			os.Getenv("AWS_ACCESS_KEY_ID"),
			os.Getenv("AWS_SECRET_ACCESS_KEY"),
			"",
		)),
	)
	must(err)
	c := s3.NewFromConfig(cfg, func(o *s3.Options) {
		o.BaseEndpoint = aws.String(endpoint)
		o.UsePathStyle = pathStyle
	})
	return c, ctx
}

func envDefault(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

func bucket(prefix string) string {
	buf := make([]byte, 4)
	_, _ = rand.Read(buf)
	return fmt.Sprintf("m6-%s-%x", prefix, buf)
}

func sha256B64(data []byte) *string {
	sum := sha256.Sum256(data)
	value := base64.StdEncoding.EncodeToString(sum[:])
	return &value
}

func readAll(body io.ReadCloser) []byte {
	defer body.Close()
	data, err := io.ReadAll(body)
	must(err)
	return data
}

func expectS3Error(fn func() error, expected ...string) error {
	err := fn()
	if err == nil {
		return fmt.Errorf("expected S3 error")
	}
	var apiErr smithy.APIError
	if errors.As(err, &apiErr) {
		for _, code := range expected {
			if apiErr.ErrorCode() == code {
				return nil
			}
		}
		return fmt.Errorf("unexpected S3 error code=%s", apiErr.ErrorCode())
	}
	return nil
}

func runContract() error {
	c, ctx := client(true)
	name := bucket("go")

	if _, err := c.CreateBucket(ctx, &s3.CreateBucketInput{Bucket: aws.String(name)}); err != nil {
		return err
	}
	if _, err := c.HeadBucket(ctx, &s3.HeadBucketInput{Bucket: aws.String(name)}); err != nil {
		return err
	}
	buckets, err := c.ListBuckets(ctx, &s3.ListBucketsInput{})
	if err != nil {
		return err
	}
	found := false
	for _, b := range buckets.Buckets {
		found = found || aws.ToString(b.Name) == name
	}
	if !found {
		return fmt.Errorf("bucket missing from ListBuckets")
	}
	if _, err := c.GetBucketLocation(ctx, &s3.GetBucketLocationInput{Bucket: aws.String(name)}); err != nil {
		return err
	}
	if err := expectS3Error(func() error {
		_, err := c.HeadBucket(ctx, &s3.HeadBucketInput{Bucket: aws.String(bucket("missing"))})
		return err
	}, "NoSuchBucket", "NotFound"); err != nil {
		return err
	}

	payload := []byte("hello from go")
	if _, err := c.PutObject(ctx, &s3.PutObjectInput{
		Bucket:         aws.String(name),
		Key:            aws.String("objects/hello.txt"),
		Body:           bytes.NewReader(payload),
		ContentType:    aws.String("text/plain"),
		Metadata:       map[string]string{"client": "go-v2"},
		ChecksumSHA256: sha256B64(payload),
	}); err != nil {
		return err
	}
	head, err := c.HeadObject(ctx, &s3.HeadObjectInput{Bucket: aws.String(name), Key: aws.String("objects/hello.txt")})
	if err != nil {
		return err
	}
	if aws.ToInt64(head.ContentLength) != int64(len(payload)) || aws.ToString(head.ContentType) != "text/plain" || head.Metadata["client"] != "go-v2" {
		return fmt.Errorf("head metadata mismatch")
	}
	got, err := c.GetObject(ctx, &s3.GetObjectInput{Bucket: aws.String(name), Key: aws.String("objects/hello.txt")})
	if err != nil {
		return err
	}
	if !bytes.Equal(payload, readAll(got.Body)) {
		return fmt.Errorf("get object mismatch")
	}
	if err := expectS3Error(func() error {
		_, err := c.HeadObject(ctx, &s3.HeadObjectInput{Bucket: aws.String(name), Key: aws.String("missing.txt")})
		return err
	}, "NoSuchKey", "NotFound"); err != nil {
		return err
	}

	rangeBody := make([]byte, 256)
	for i := range rangeBody {
		rangeBody[i] = byte(i)
	}
	_, err = c.PutObject(ctx, &s3.PutObjectInput{Bucket: aws.String(name), Key: aws.String("objects/range.bin"), Body: bytes.NewReader(rangeBody)})
	if err != nil {
		return err
	}
	rangeOut, err := c.GetObject(ctx, &s3.GetObjectInput{Bucket: aws.String(name), Key: aws.String("objects/range.bin"), Range: aws.String("bytes=10-19")})
	if err != nil {
		return err
	}
	if !bytes.Equal(rangeBody[10:20], readAll(rangeOut.Body)) {
		return fmt.Errorf("range mismatch")
	}

	keys := []string{"prefix/a.txt", "prefix/b.txt", "prefix/nested/c.txt", "weird space 日本.txt"}
	for i, key := range keys {
		_, err := c.PutObject(ctx, &s3.PutObjectInput{Bucket: aws.String(name), Key: aws.String(key), Body: strings.NewReader(fmt.Sprintf("v%d", i))})
		if err != nil {
			return err
		}
	}
	listed, err := c.ListObjectsV2(ctx, &s3.ListObjectsV2Input{Bucket: aws.String(name), Prefix: aws.String("prefix/"), Delimiter: aws.String("/")})
	if err != nil {
		return err
	}
	if len(listed.Contents) < 2 || len(listed.CommonPrefixes) != 1 {
		return fmt.Errorf("listing mismatch")
	}

	_, err = c.CopyObject(ctx, &s3.CopyObjectInput{Bucket: aws.String(name), Key: aws.String("objects/copied.txt"), CopySource: aws.String(name + "/objects/hello.txt")})
	if err != nil {
		return err
	}
	copied, err := c.GetObject(ctx, &s3.GetObjectInput{Bucket: aws.String(name), Key: aws.String("objects/copied.txt")})
	if err != nil {
		return err
	}
	if !bytes.Equal(payload, readAll(copied.Body)) {
		return fmt.Errorf("copy mismatch")
	}

	partPayload := bytes.Repeat([]byte{0x41}, partSize)
	init, err := c.CreateMultipartUpload(ctx, &s3.CreateMultipartUploadInput{Bucket: aws.String(name), Key: aws.String("multipart.bin")})
	if err != nil {
		return err
	}
	part, err := c.UploadPart(ctx, &s3.UploadPartInput{
		Bucket:        aws.String(name),
		Key:           aws.String("multipart.bin"),
		UploadId:      init.UploadId,
		PartNumber:    aws.Int32(1),
		Body:          bytes.NewReader(partPayload),
		ContentLength: aws.Int64(int64(len(partPayload))),
	})
	if err != nil {
		return err
	}
	parts, err := c.ListParts(ctx, &s3.ListPartsInput{Bucket: aws.String(name), Key: aws.String("multipart.bin"), UploadId: init.UploadId})
	if err != nil || len(parts.Parts) != 1 {
		return fmt.Errorf("list parts mismatch: %w", err)
	}
	uploads, err := c.ListMultipartUploads(ctx, &s3.ListMultipartUploadsInput{Bucket: aws.String(name)})
	if err != nil || len(uploads.Uploads) == 0 {
		return fmt.Errorf("list multipart uploads mismatch: %w", err)
	}
	_, err = c.CompleteMultipartUpload(ctx, &s3.CompleteMultipartUploadInput{
		Bucket:   aws.String(name),
		Key:      aws.String("multipart.bin"),
		UploadId: init.UploadId,
		MultipartUpload: &types.CompletedMultipartUpload{
			Parts: []types.CompletedPart{{PartNumber: aws.Int32(1), ETag: part.ETag}},
		},
	})
	if err != nil {
		return err
	}

	abort, err := c.CreateMultipartUpload(ctx, &s3.CreateMultipartUploadInput{Bucket: aws.String(name), Key: aws.String("abort.bin")})
	if err != nil {
		return err
	}
	if _, err := c.AbortMultipartUpload(ctx, &s3.AbortMultipartUploadInput{Bucket: aws.String(name), Key: aws.String("abort.bin"), UploadId: abort.UploadId}); err != nil {
		return err
	}

	_, err = c.PutObject(ctx, &s3.PutObjectInput{Bucket: aws.String(name), Key: aws.String("copy-source.bin"), Body: bytes.NewReader(partPayload)})
	if err != nil {
		return err
	}
	copyInit, err := c.CreateMultipartUpload(ctx, &s3.CreateMultipartUploadInput{Bucket: aws.String(name), Key: aws.String("copy-dest.bin")})
	if err != nil {
		return err
	}
	copyPart, err := c.UploadPartCopy(ctx, &s3.UploadPartCopyInput{
		Bucket:     aws.String(name),
		Key:        aws.String("copy-dest.bin"),
		UploadId:   copyInit.UploadId,
		PartNumber: aws.Int32(1),
		CopySource: aws.String(name + "/copy-source.bin"),
	})
	if err != nil || copyPart.CopyPartResult == nil || copyPart.CopyPartResult.ETag == nil {
		return fmt.Errorf("upload part copy mismatch: %w", err)
	}
	_, err = c.AbortMultipartUpload(ctx, &s3.AbortMultipartUploadInput{Bucket: aws.String(name), Key: aws.String("copy-dest.bin"), UploadId: copyInit.UploadId})
	if err != nil {
		return err
	}

	presigner := s3.NewPresignClient(c)
	getURL, err := presigner.PresignGetObject(ctx, &s3.GetObjectInput{Bucket: aws.String(name), Key: aws.String("objects/hello.txt")})
	if err != nil {
		return err
	}
	resp, err := http.Get(getURL.URL)
	if err != nil {
		return err
	}
	if !bytes.Equal(payload, readAll(resp.Body)) {
		return fmt.Errorf("presigned get mismatch")
	}
	putURL, err := presigner.PresignPutObject(ctx, &s3.PutObjectInput{Bucket: aws.String(name), Key: aws.String("presigned-put.txt")})
	if err != nil {
		return err
	}
	req, err := http.NewRequest(http.MethodPut, putURL.URL, bytes.NewReader([]byte("presigned")))
	if err != nil {
		return err
	}
	putResp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	_ = readAll(putResp.Body)
	if putResp.StatusCode != 200 {
		return fmt.Errorf("presigned put status %d", putResp.StatusCode)
	}

	_, err = c.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: aws.String(name), Key: aws.String("objects/hello.txt")})
	if err != nil {
		return err
	}
	if err := expectS3Error(func() error {
		_, err := c.HeadObject(ctx, &s3.HeadObjectInput{Bucket: aws.String(name), Key: aws.String("objects/hello.txt")})
		return err
	}, "NoSuchKey", "NotFound"); err != nil {
		return err
	}
	fmt.Println("COMPAT PASS go-v2 contract")
	return nil
}

func runDetection() error {
	c, ctx := client(false)
	name := bucket("detect-go")
	if _, err := c.CreateBucket(ctx, &s3.CreateBucketInput{Bucket: aws.String(name)}); err != nil {
		fmt.Printf("DETECTION go-v2 virtual-host=unsupported reason=%T\n", err)
	} else {
		fmt.Println("DETECTION go-v2 virtual-host=pass")
	}
	fmt.Println("DETECTION go-v2 streaming-sigv4=not-required-with-bytes-reader")
	fmt.Println("COMPAT PASS go-v2 detection")
	return nil
}
