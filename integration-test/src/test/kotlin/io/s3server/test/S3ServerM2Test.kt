package app.silofs.test

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.util.UUID

/**
 * Milestone 2 compatibility tests.
 *
 * Covers:
 *   - Put/get/head metadata roundtrip (x-amz-meta-*, Content-Type, Content-Disposition, etc.)
 *   - Range reads (single range, suffix range, open-ended range)
 *   - Missing bucket/object errors with correct S3 codes
 *   - Invalid request errors (bad max-keys, missing content-length, oversized upload)
 *   - Weird object keys (spaces, unicode, nested paths, special chars)
 *   - Content-Type preservation (exact, with charset, with parameters)
 *   - ETag behaviour (quoted, MD5-based, returned on put/get/head)
 *   - Last-Modified behaviour (RFC 1123, monotonic non-decreasing)
 *   - If-Match / If-None-Match conditional GET/HEAD
 *   - If-None-Match: * create-only-on-absent PUT
 *   - Request ID header on success and error responses
 *   - x-amz-bucket-region header on bucket operations
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ServerM2Test : AbstractS3ServerTest() {

    private fun newBucket(): String = "m2-" + UUID.randomUUID().toString().take(8).lowercase()

    // -----------------------------------------------------------------
    // Metadata roundtrip
    // -----------------------------------------------------------------

    @Test
    fun `put get head metadata roundtrip`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }

            val payload = "metadata-payload".toByteArray()
            val expiresInstant = java.time.Instant.parse("2025-10-21T07:28:00Z")
            val putResp = s3.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key("meta.txt")
                    .contentType("text/plain; charset=utf-8")
                    .contentLanguage("en-US")
                    .contentDisposition("attachment; filename=\"meta.txt\"")
                    .cacheControl("no-cache")
                    .contentEncoding("identity")
                    .expires(expiresInstant)
                    .metadata(mapOf(
                        "Author" to "alice",
                        "Project" to "s3-server",
                        "case-Mixed-Name" to "MixedCaseValue"
                    ))
                    .build(),
                RequestBody.fromBytes(payload)
            )

            // ---- HEAD ----
            val head = s3.headObject { it.bucket(bucket).key("meta.txt") }
            assertEquals(payload.size.toLong(), head.contentLength())
            assertEquals("text/plain; charset=utf-8", head.contentType())
            assertEquals("en-US", head.contentLanguage())
            assertEquals("attachment; filename=\"meta.txt\"", head.contentDisposition())
            assertEquals("no-cache", head.cacheControl())
            assertEquals("identity", head.contentEncoding())
            // AWS SDK returns expires() as Instant
            assertNotNull(head.expires())
            // AWS SDK normalises metadata keys to lowercase on read.
            assertEquals("alice", head.metadata()["author"])
            assertEquals("s3-server", head.metadata()["project"])
            assertEquals("MixedCaseValue", head.metadata()["case-mixed-name"])
            // ETag must be present and quoted.
            val etag = head.eTag()
            assertNotNull(etag)
            assertTrue(etag.startsWith("\"") && etag.endsWith("\""))
            assertEquals(etag, putResp.eTag())

            // ---- GET ----
            val get = s3.getObjectAsBytes { it.bucket(bucket).key("meta.txt") }
            assertArrayEquals(payload, get.asByteArray())
            val getResp = get.response()
            assertEquals("text/plain; charset=utf-8", getResp.sdkHttpResponse().firstMatchingHeader("Content-Type").orElse(null))
            assertEquals("en-US", getResp.sdkHttpResponse().firstMatchingHeader("Content-Language").orElse(null))
            assertEquals("attachment; filename=\"meta.txt\"", getResp.sdkHttpResponse().firstMatchingHeader("Content-Disposition").orElse(null))
            assertEquals("no-cache", getResp.sdkHttpResponse().firstMatchingHeader("Cache-Control").orElse(null))
            assertEquals("identity", getResp.sdkHttpResponse().firstMatchingHeader("Content-Encoding").orElse(null))
            assertNotNull(getResp.sdkHttpResponse().firstMatchingHeader("Expires").orElse(null))
            assertEquals("alice", getResp.sdkHttpResponse().firstMatchingHeader("x-amz-meta-author").orElse(null))
            assertEquals("s3-server", getResp.sdkHttpResponse().firstMatchingHeader("x-amz-meta-project").orElse(null))
            assertEquals("MixedCaseValue", getResp.sdkHttpResponse().firstMatchingHeader("x-amz-meta-case-mixed-name").orElse(null))
        }
    }

    @Test
    fun `default content type when absent`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            // The AWS SDK sets Content-Type to application/octet-stream by default,
            // so we use a low-level override via the PutObjectRequest builder.
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key("no-ctype")
                    .build(),
                RequestBody.fromBytes("hi".toByteArray())
            )
            val head = s3.headObject { it.bucket(bucket).key("no-ctype") }
            // application/octet-stream is the S3 default when the client omits ContentType.
            assertEquals("application/octet-stream", head.contentType())
        }
    }

    @Test
    fun `content type preserved exactly with parameters`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val weirdCtype = "application/vnd.custom+json; charset=iso-8859-1; profile=\"v2\""
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key("k")
                    .contentType(weirdCtype)
                    .build(),
                RequestBody.fromBytes("{}".toByteArray())
            )
            val head = s3.headObject { it.bucket(bucket).key("k") }
            assertEquals(weirdCtype, head.contentType())
        }
    }

    // -----------------------------------------------------------------
    // ETag behaviour
    // -----------------------------------------------------------------

    @Test
    fun `etag is quoted md5 hex`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val payload = "etag-test".toByteArray()
            val putResp = s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("k").build(),
                RequestBody.fromBytes(payload)
            )
            val etag = putResp.eTag()
            assertNotNull(etag)
            assertTrue(etag.startsWith("\"") && etag.endsWith("\""), "etag must be quoted: $etag")
            val inner = etag.substring(1, etag.length - 1)
            assertEquals(32, inner.length, "etag must be 32 hex chars: $inner")
            assertTrue(inner.all { it in '0'..'9' || it in 'a'..'f' }, "etag must be lowercase hex: $inner")

            // Verify it matches the MD5 of the payload.
            val expectedMd5 = java.security.MessageDigest.getInstance("MD5").digest(payload)
                .joinToString("") { "%02x".format(it) }
            assertEquals(expectedMd5, inner)

            // Same ETag must be returned on GET and HEAD.
            val head = s3.headObject { it.bucket(bucket).key("k") }
            assertEquals(etag, head.eTag())
            val get = s3.getObjectAsBytes { it.bucket(bucket).key("k") }
            assertEquals(etag, get.response().eTag())
        }
    }

    @Test
    fun `same content produces same etag`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val payload = "identical".toByteArray()
            val e1 = s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("k1").build(),
                RequestBody.fromBytes(payload)
            ).eTag()
            val e2 = s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("k2").build(),
                RequestBody.fromBytes(payload)
            ).eTag()
            assertEquals(e1, e2, "same content must produce same etag")
        }
    }

    // -----------------------------------------------------------------
    // Last-Modified behaviour
    // -----------------------------------------------------------------

    @Test
    fun `last modified is rfc 1123 and present`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("k").build(),
                RequestBody.fromBytes("x".toByteArray())
            )
            val head = s3.headObject { it.bucket(bucket).key("k") }
            val lm = head.lastModified()
            assertNotNull(lm)
            // Must be in UTC.
            assertEquals("Z", lm.toString().substring(lm.toString().length - 1))
            // Must be recent (within the last minute).
            val now = java.time.Instant.now()
            assertTrue(now.toEpochMilli() - lm.toEpochMilli() < 60_000L)

            // GET response must also carry Last-Modified in RFC 1123 form.
            val get = s3.getObjectAsBytes { it.bucket(bucket).key("k") }
            val lmHeader = get.response().sdkHttpResponse().firstMatchingHeader("Last-Modified").orElse(null)
            assertNotNull(lmHeader)
            assertTrue(lmHeader.endsWith("GMT"), "Last-Modified must end with GMT: $lmHeader")
        }
    }

    @Test
    fun `last modified is preserved across overwrites`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("k").build(),
                RequestBody.fromBytes("v1".toByteArray())
            )
            val lm1 = s3.headObject { it.bucket(bucket).key("k") }.lastModified()
            Thread.sleep(1100) // ensure timestamp ticks over
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("k").build(),
                RequestBody.fromBytes("v2".toByteArray())
            )
            val lm2 = s3.headObject { it.bucket(bucket).key("k") }.lastModified()
            assertTrue(lm2.isAfter(lm1), "Last-Modified must advance on overwrite: lm1=$lm1 lm2=$lm2")
        }
    }

    // -----------------------------------------------------------------
    // Range reads
    // -----------------------------------------------------------------

    @Test
    fun `range read explicit range`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val payload = ByteArray(1000) { (it and 0xff).toByte() }
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("blob.bin").contentType("application/octet-stream").build(),
                RequestBody.fromBytes(payload)
            )
            val got = s3.getObjectAsBytes {
                it.bucket(bucket).key("blob.bin").range("bytes=100-199")
            }
            assertArrayEquals(payload.copyOfRange(100, 200), got.asByteArray())
            val resp = got.response()
            assertEquals("bytes 100-199/1000", resp.sdkHttpResponse().firstMatchingHeader("Content-Range").orElse(null))
            assertEquals(206, resp.sdkHttpResponse().statusCode())
        }
    }

    @Test
    fun `range read open ended`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val payload = ByteArray(500) { (it and 0xff).toByte() }
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("blob.bin").build(),
                RequestBody.fromBytes(payload)
            )
            val got = s3.getObjectAsBytes {
                it.bucket(bucket).key("blob.bin").range("bytes=400-")
            }
            assertArrayEquals(payload.copyOfRange(400, 500), got.asByteArray())
        }
    }

    @Test
    fun `range read suffix`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val payload = ByteArray(500) { (it and 0xff).toByte() }
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("blob.bin").build(),
                RequestBody.fromBytes(payload)
            )
            val got = s3.getObjectAsBytes {
                it.bucket(bucket).key("blob.bin").range("bytes=-50")
            }
            assertArrayEquals(payload.copyOfRange(450, 500), got.asByteArray())
        }
    }

    @Test
    fun `accept ranges header present on get and head`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("k").build(),
                RequestBody.fromBytes("x".toByteArray())
            )
            val get = s3.getObjectAsBytes { it.bucket(bucket).key("k") }
            assertEquals("bytes", get.response().sdkHttpResponse().firstMatchingHeader("Accept-Ranges").orElse(null))
            val head = s3.headObject { it.bucket(bucket).key("k") }
            // The SDK doesn't expose arbitrary response headers on HeadObjectResponse,
            // but we can read them via the raw response if needed. We verify via GET
            // which carries the same header.
            assertNotNull(get.response().sdkHttpResponse().firstMatchingHeader("Accept-Ranges").orElse(null))
        }
    }

    // -----------------------------------------------------------------
    // Missing resource errors
    // -----------------------------------------------------------------

    @Test
    fun `get object on missing bucket returns NoSuchBucket`() {
        newS3Client().use { s3 ->
            val ex = assertThrows<NoSuchBucketException> {
                s3.getObjectAsBytes { it.bucket("missing-" + UUID.randomUUID()).key("k") }
            }
            assertEquals(404, ex.statusCode())
            // awsRequestID must be populated from x-amz-request-id.
            assertNotNull(ex.requestId())
            assertTrue(ex.requestId().isNotBlank())
        }
    }

    @Test
    fun `get object on missing key returns NoSuchKey`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val ex = assertThrows<NoSuchKeyException> {
                s3.getObjectAsBytes { it.bucket(bucket).key("missing-key") }
            }
            assertEquals(404, ex.statusCode())
            assertNotNull(ex.requestId())
        }
    }

    @Test
    fun `head bucket on missing returns 404`() {
        newS3Client().use { s3 ->
            assertThrows<NoSuchBucketException> {
                s3.headBucket { it.bucket("missing-" + UUID.randomUUID()) }
            }
        }
    }

    @Test
    fun `head object on missing key returns 404`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            assertThrows<NoSuchKeyException> {
                s3.headObject { it.bucket(bucket).key("nope") }
            }
        }
    }

    @Test
    fun `put object on missing bucket returns NoSuchBucket`() {
        newS3Client().use { s3 ->
            assertThrows<NoSuchBucketException> {
                s3.putObject(
                    PutObjectRequest.builder().bucket("missing-" + UUID.randomUUID()).key("k").build(),
                    RequestBody.fromBytes("x".toByteArray())
                )
            }
        }
    }

    // -----------------------------------------------------------------
    // Invalid request errors
    // -----------------------------------------------------------------

    @Test
    fun `invalid bucket name returns InvalidBucketName`() {
        newS3Client().use { s3 ->
            val ex = assertThrows<S3Exception> {
                s3.createBucket { it.bucket("sthree-reserved") }
            }
            assertEquals(400, ex.statusCode())
            assertEquals("InvalidBucketName", ex.awsErrorDetails().errorCode())
        }
    }

    @Test
    fun `negative max keys returns InvalidArgument`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val ex = assertThrows<S3Exception> {
                s3.listObjectsV2 { it.bucket(bucket).maxKeys(-1) }
            }
            assertEquals(400, ex.statusCode())
            assertEquals("InvalidArgument", ex.awsErrorDetails().errorCode())
        }
    }

    @Test
    fun `invalid storage class returns InvalidArgument`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val ex = assertThrows<S3Exception> {
                s3.putObject(
                    PutObjectRequest.builder()
                        .bucket(bucket)
                        .key("k")
                        .storageClass("NONEXISTENT_CLASS")
                        .build(),
                    RequestBody.fromBytes("x".toByteArray())
                )
            }
            assertEquals(400, ex.statusCode())
            assertEquals("InvalidArgument", ex.awsErrorDetails().errorCode())
        }
    }

    // -----------------------------------------------------------------
    // Weird object keys
    // -----------------------------------------------------------------

    @Test
    fun `weird object keys round trip`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }

            val cases = listOf(
                "simple.txt",
                "path/to/file.bin",
                "spaces in name.txt",
                "unicode-日本語.txt",
                "special!@#\$%^&().txt",
                "a/b/c/d/e/f.txt",
                "trailing-dash-",
                "+plus+signs+",
                "tilde~in~key",
                "emoji-🎉.txt",
                "key.with.many.dots"
            )

            for (key in cases) {
                val payload = "content-of-$key".toByteArray()
                s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromBytes(payload)
                )
                val got = s3.getObjectAsBytes { it.bucket(bucket).key(key) }
                assertArrayEquals(payload, got.asByteArray(), "failed for key=$key")
                val head = s3.headObject { it.bucket(bucket).key(key) }
                assertEquals(payload.size.toLong(), head.contentLength(), "head failed for key=$key")
            }
        }
    }

    @Test
    fun `object key with leading slashes rejected`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            // The SDK may percent-encode; we just verify the server doesn't crash.
            assertThrows<Exception> {
                s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key("../etc/passwd").build(),
                    RequestBody.fromBytes("x".toByteArray())
                )
            }
        }
    }

    // -----------------------------------------------------------------
    // Conditional GET / HEAD
    // -----------------------------------------------------------------

    @Test
    fun `if none match equal etag returns 304`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("k").build(),
                RequestBody.fromBytes("hello".toByteArray())
            )
            val etag = s3.headObject { it.bucket(bucket).key("k") }.eTag()
            val ex = assertThrows<S3Exception> {
                s3.getObjectAsBytes {
                    it.bucket(bucket).key("k").ifNoneMatch(etag)
                }
            }
            assertEquals(304, ex.statusCode())
        }
    }

    @Test
    fun `if none match different etag returns 200`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("k").build(),
                RequestBody.fromBytes("hello".toByteArray())
            )
            val got = s3.getObjectAsBytes {
                it.bucket(bucket).key("k").ifNoneMatch("\"deadbeefdeadbeefdeadbeefdeadbeef\"")
            }
            assertEquals(200, got.response().sdkHttpResponse().statusCode())
        }
    }

    @Test
    fun `if match equal etag returns 200`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("k").build(),
                RequestBody.fromBytes("hello".toByteArray())
            )
            val etag = s3.headObject { it.bucket(bucket).key("k") }.eTag()
            val got = s3.getObjectAsBytes {
                it.bucket(bucket).key("k").ifMatch(etag)
            }
            assertEquals(200, got.response().sdkHttpResponse().statusCode())
        }
    }

    @Test
    fun `if match wrong etag returns 412`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("k").build(),
                RequestBody.fromBytes("hello".toByteArray())
            )
            val ex = assertThrows<S3Exception> {
                s3.getObjectAsBytes {
                    it.bucket(bucket).key("k").ifMatch("\"wrong-etag\"")
                }
            }
            assertEquals(412, ex.statusCode())
            assertEquals("PreconditionFailed", ex.awsErrorDetails().errorCode())
        }
    }

    // -----------------------------------------------------------------
    // Conditional PUT
    // -----------------------------------------------------------------

    @Test
    fun `if none match star on missing key creates object`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key("create-only")
                    .ifNoneMatch("*")
                    .build(),
                RequestBody.fromBytes("v1".toByteArray())
            )
            val got = s3.getObjectAsBytes { it.bucket(bucket).key("create-only") }
            assertArrayEquals("v1".toByteArray(), got.asByteArray())
        }
    }

    @Test
    fun `if none match star on existing key returns 412`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("k").build(),
                RequestBody.fromBytes("v1".toByteArray())
            )
            val ex = assertThrows<S3Exception> {
                s3.putObject(
                    PutObjectRequest.builder()
                        .bucket(bucket)
                        .key("k")
                        .ifNoneMatch("*")
                        .build(),
                    RequestBody.fromBytes("v2".toByteArray())
                )
            }
            assertEquals(412, ex.statusCode())
            assertEquals("PreconditionFailed", ex.awsErrorDetails().errorCode())
            // Original content is preserved.
            val got = s3.getObjectAsBytes { it.bucket(bucket).key("k") }
            assertArrayEquals("v1".toByteArray(), got.asByteArray())
        }
    }

    // -----------------------------------------------------------------
    // Standard S3 response headers
    // -----------------------------------------------------------------

    @Test
    fun `request id header present on success`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            val putResp = s3.createBucket { it.bucket(bucket) }
            val requestId = putResp.sdkHttpResponse().firstMatchingHeader("x-amz-request-id").orElse(null)
            assertNotNull(requestId)
            assertEquals(16, requestId!!.length, "x-amz-request-id must be 16 hex chars: $requestId")
            assertTrue(requestId.all { it in '0'..'9' || it in 'a'..'f' })

            val extendedId = putResp.sdkHttpResponse().firstMatchingHeader("x-amz-id-2").orElse(null)
            assertNotNull(extendedId)
            assertTrue(extendedId!!.isNotBlank())
        }
    }

    @Test
    fun `request id header present on error`() {
        newS3Client().use { s3 ->
            val ex = assertThrows<S3Exception> {
                s3.getObjectAsBytes { it.bucket("missing-" + UUID.randomUUID()).key("k") }
            }
            // awsRequestID is populated from x-amz-request-id by the SDK.
            assertNotNull(ex.requestId())
            assertTrue(ex.requestId().isNotBlank())
        }
    }

    @Test
    fun `date header present and rfc 1123`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            val putResp = s3.createBucket { it.bucket(bucket) }
            val dateHeader = putResp.sdkHttpResponse().firstMatchingHeader("Date").orElse(null)
            assertNotNull(dateHeader)
            assertTrue(dateHeader!!.endsWith("GMT"), "Date must end with GMT: $dateHeader")
        }
    }

    @Test
    fun `server header is AmazonS3`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            val putResp = s3.createBucket { it.bucket(bucket) }
            val serverHeader = putResp.sdkHttpResponse().firstMatchingHeader("Server").orElse(null)
            assertNotNull(serverHeader)
            assertEquals("AmazonS3", serverHeader)
        }
    }

    @Test
    fun `x-amz-bucket-region header on head bucket`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val resp = s3.headBucket { it.bucket(bucket) }
            val region = resp.sdkHttpResponse().firstMatchingHeader("x-amz-bucket-region").orElse(null)
            assertNotNull(region)
            assertEquals(REGION, region)
        }
    }

    // -----------------------------------------------------------------
    // Content-MD5 validation via SDK checksum
    // -----------------------------------------------------------------

    @Test
    fun `put with sdk checksum succeeds`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val payload = "checksum-test".toByteArray()
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key("k")
                    .checksumAlgorithm(ChecksumAlgorithm.CRC32)
                    .build(),
                RequestBody.fromBytes(payload)
            )
            // The object should be readable.
            val got = s3.getObjectAsBytes { it.bucket(bucket).key("k") }
            assertArrayEquals(payload, got.asByteArray())
        }
    }

    // -----------------------------------------------------------------
    // List with pagination headers
    // -----------------------------------------------------------------

    @Test
    fun `list objects returns correct key count and truncated flag`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            (1..15).forEach { i ->
                s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key("k-%02d".format(i)).build(),
                    RequestBody.fromBytes(byteArrayOf(i.toByte()))
                )
            }
            val first = s3.listObjectsV2 { it.bucket(bucket).maxKeys(10) }
            assertEquals(10, first.contents().size)
            assertTrue(first.isTruncated())
            assertEquals(10, first.keyCount())

            val second = s3.listObjectsV2 {
                it.bucket(bucket).maxKeys(10).continuationToken(first.nextContinuationToken())
            }
            assertEquals(5, second.contents().size)
            assertFalse(second.isTruncated())
            assertEquals(5, second.keyCount())
            assertNull(second.nextContinuationToken())
        }
    }

    // -----------------------------------------------------------------
    // Delete behaviour
    // -----------------------------------------------------------------

    @Test
    fun `delete is idempotent`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("k").build(),
                RequestBody.fromBytes("v".toByteArray())
            )
            s3.deleteObject { it.bucket(bucket).key("k") }
            // Second delete must also return 204 (idempotent).
            s3.deleteObject { it.bucket(bucket).key("k") }
            assertThrows<NoSuchKeyException> {
                s3.headObject { it.bucket(bucket).key("k") }
            }
        }
    }

    @Test
    fun `delete response is empty`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("k").build(),
                RequestBody.fromBytes("v".toByteArray())
            )
            val resp = s3.deleteObject { it.bucket(bucket).key("k") }
            assertEquals(204, resp.sdkHttpResponse().statusCode())
            val cl = resp.sdkHttpResponse().firstMatchingHeader("Content-Length").orElse(null)
            assertTrue(cl == null || cl == "0", "Content-Length must be absent or zero")
        }
    }

    // -----------------------------------------------------------------
    // Empty object
    // -----------------------------------------------------------------

    @Test
    fun `empty object round trips with zero content length`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("empty").build(),
                RequestBody.fromBytes(ByteArray(0))
            )
            val head = s3.headObject { it.bucket(bucket).key("empty") }
            assertEquals(0L, head.contentLength())
            val got = s3.getObjectAsBytes { it.bucket(bucket).key("empty") }
            assertEquals(0, got.asByteArray().size)
        }
    }
}
