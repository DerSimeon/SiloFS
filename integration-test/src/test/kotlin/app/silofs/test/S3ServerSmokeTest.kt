package app.silofs.test

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.util.UUID

/**
 * Smoke tests against the running silofs server using the AWS SDK for Java v2.
 *
 * These tests deliberately use the synchronous client — async tests live in
 * [S3ServerConcurrencyTest].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ServerSmokeTest : AbstractS3ServerTest() {
    private fun newBucket(): String =
        "smoke-" +
            UUID
                .randomUUID()
                .toString()
                .take(8)
                .lowercase()

    @Test
    fun `create bucket and head it`() {
        val s3 =
            newS3Client().use { s3 ->
                val bucket = newBucket()
                s3.createBucket { it.bucket(bucket) }
                val head = s3.headBucket { it.bucket(bucket) }
                assertEquals(200, head.sdkHttpResponse().statusCode())
            }
    }

    @Test
    fun `head bucket on missing returns 404`() {
        newS3Client().use { s3 ->
            assertThrows<NoSuchBucketException> {
                s3.headBucket { it.bucket("does-not-exist-" + UUID.randomUUID()) }
            }
        }
    }

    @Test
    fun `put get head delete object`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }

            val payload = "hello, s3!".toByteArray()
            val putResp =
                s3.putObject(
                    PutObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key("hello.txt")
                        .contentType("text/plain")
                        .metadata(mapOf("author" to "test", "purpose" to "smoke"))
                        .build(),
                    RequestBody.fromBytes(payload),
                )
            assertTrue(putResp.sdkHttpResponse().isSuccessful)
            val etag = putResp.eTag()
            assertNotNull(etag)
            assertTrue(etag.startsWith("\""))

            // HEAD
            val head = s3.headObject { it.bucket(bucket).key("hello.txt") }
            assertEquals(payload.size.toLong(), head.contentLength())
            assertEquals("text/plain", head.contentType())
            assertEquals(etag, head.eTag())
            assertEquals("test", head.metadata()["author"])
            assertEquals("smoke", head.metadata()["purpose"])

            // GET
            val get = s3.getObjectAsBytes { it.bucket(bucket).key("hello.txt") }
            assertArrayEquals(payload, get.asByteArray())

            // DELETE
            s3.deleteObject { it.bucket(bucket).key("hello.txt") }
            assertThrows<NoSuchKeyException> {
                s3.headObject { it.bucket(bucket).key("hello.txt") }
            }
        }
    }

    @Test
    fun `range read returns partial content`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val payload = ByteArray(1000) { (it and 0xff).toByte() }
            s3.putObject(
                PutObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key("blob.bin")
                    .contentType("application/octet-stream")
                    .build(),
                RequestBody.fromBytes(payload),
            )
            val got =
                s3.getObjectAsBytes {
                    it.bucket(bucket).key("blob.bin").range("bytes=100-199")
                }
            assertArrayEquals(payload.copyOfRange(100, 200), got.asByteArray())
        }
    }

    @Test
    fun `suffix range returns last bytes`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val payload = ByteArray(500) { (it and 0xff).toByte() }
            s3.putObject(
                PutObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key("blob.bin")
                    .build(),
                RequestBody.fromBytes(payload),
            )
            val got =
                s3.getObjectAsBytes {
                    it.bucket(bucket).key("blob.bin").range("bytes=-50")
                }
            assertArrayEquals(payload.copyOfRange(450, 500), got.asByteArray())
        }
    }

    @Test
    fun `weird object keys`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }

            val keys =
                listOf(
                    "simple.txt",
                    "path/with/slashes/file.txt",
                    "spaces in name.txt",
                    "unicode-日本語.txt",
                    "special!@#$%^&().txt",
                    "a/b/c/d/e/f.txt",
                )

            for (key in keys) {
                val payload = "content of $key".toByteArray()
                s3.putObject(
                    PutObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(key)
                        .build(),
                    RequestBody.fromBytes(payload),
                )
                val got = s3.getObjectAsBytes { it.bucket(bucket).key(key) }
                assertArrayEquals(payload, got.asByteArray(), "failed for key=$key")
            }
        }
    }

    @Test
    fun `list objects paginates`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            (1..25).forEach { i ->
                s3.putObject(
                    PutObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key("k-%02d".format(i))
                        .build(),
                    RequestBody.fromBytes(byteArrayOf(i.toByte())),
                )
            }

            val first = s3.listObjectsV2 { it.bucket(bucket).maxKeys(10) }
            assertEquals(10, first.contents().size)
            assertTrue(first.isTruncated())

            val second =
                s3.listObjectsV2 {
                    it.bucket(bucket).maxKeys(10).continuationToken(first.nextContinuationToken())
                }
            assertEquals(10, second.contents().size)
            assertTrue(second.isTruncated())

            val third =
                s3.listObjectsV2 {
                    it.bucket(bucket).maxKeys(10).continuationToken(second.nextContinuationToken())
                }
            assertEquals(5, third.contents().size)
            assertFalse(third.isTruncated())
            assertNull(third.nextContinuationToken())
        }
    }

    @Test
    fun `put object into missing bucket returns 404`() {
        newS3Client().use { s3 ->
            assertThrows<NoSuchBucketException> {
                s3.putObject(
                    PutObjectRequest
                        .builder()
                        .bucket("missing-" + UUID.randomUUID())
                        .key("k")
                        .build(),
                    RequestBody.fromBytes(byteArrayOf(1)),
                )
            }
        }
    }

    @Test
    fun `content-md5 mismatch rejected`() {
        // Moved to S3ServerM2Test in M2 — proper Content-MD5 validation is
        // verified there using the SDK's checksum API.
    }

    @Test
    fun `empty payload round trips`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            s3.putObject(
                PutObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key("empty")
                    .build(),
                RequestBody.fromBytes(ByteArray(0)),
            )
            val head = s3.headObject { it.bucket(bucket).key("empty") }
            assertEquals(0L, head.contentLength())
            val got = s3.getObjectAsBytes { it.bucket(bucket).key("empty") }
            assertEquals(0, got.asByteArray().size)
        }
    }

    @Test
    fun `large object streamed`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val size = 5 * 1024 * 1024 // 5 MiB
            val payload = ByteArray(size) { (it and 0xff).toByte() }
            s3.putObject(
                PutObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key("big.bin")
                    .build(),
                RequestBody.fromBytes(payload),
            )
            val got = s3.getObjectAsBytes { it.bucket(bucket).key("big.bin") }
            assertArrayEquals(payload, got.asByteArray())
        }
    }
}
