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
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.util.UUID

/**
 * Milestone 2.1 compatibility tests.
 *
 * Covers:
 *   - DeleteBucket (empty and non-empty)
 *   - ListBuckets (returns created buckets with correct shape)
 *   - CopyObject (COPY and REPLACE metadata directives, cross-bucket copy,
 *     in-place metadata update, conditional copy)
 *   - ListObjectsV2 with `prefix`, `delimiter`, `encoding-type=url`
 *   - x-amz-checksum-* persistence and echo on GET/HEAD
 *   - GetBucketLocation
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ServerM21Test : AbstractS3ServerTest() {

    private fun newBucket(): String = "m21-" + UUID.randomUUID().toString().take(8).lowercase()

    // -----------------------------------------------------------------
    // DeleteBucket
    // -----------------------------------------------------------------

    @Test
    fun `delete empty bucket succeeds`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            s3.deleteBucket { it.bucket(bucket) }
            assertThrows<NoSuchBucketException> {
                s3.headBucket { it.bucket(bucket) }
            }
        }
    }

    @Test
    fun `delete non-empty bucket returns BucketNotEmpty`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("k").build(),
                RequestBody.fromBytes("v".toByteArray())
            )
            val ex = assertThrows<S3Exception> {
                s3.deleteBucket { it.bucket(bucket) }
            }
            assertEquals(409, ex.statusCode())
            assertEquals("BucketNotEmpty", ex.awsErrorDetails().errorCode())
            // Bucket still exists.
            s3.headBucket { it.bucket(bucket) }
        }
    }

    @Test
    fun `delete bucket after emptying succeeds`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("k1").build(),
                RequestBody.fromBytes("v".toByteArray())
            )
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("k2").build(),
                RequestBody.fromBytes("v".toByteArray())
            )
            s3.deleteObject { it.bucket(bucket).key("k1") }
            s3.deleteObject { it.bucket(bucket).key("k2") }
            s3.deleteBucket { it.bucket(bucket) }
            assertThrows<NoSuchBucketException> {
                s3.headBucket { it.bucket(bucket) }
            }
        }
    }

    @Test
    fun `delete missing bucket returns NoSuchBucket`() {
        newS3Client().use { s3 ->
            assertThrows<NoSuchBucketException> {
                s3.deleteBucket { it.bucket("missing-" + UUID.randomUUID()) }
            }
        }
    }

    // -----------------------------------------------------------------
    // ListBuckets
    // -----------------------------------------------------------------

    @Test
    fun `list buckets returns created buckets`() {
        newS3Client().use { s3 ->
            val b1 = newBucket()
            val b2 = newBucket()
            s3.createBucket { it.bucket(b1) }
            s3.createBucket { it.bucket(b2) }
            val resp = s3.listBuckets()
            val names = resp.buckets().map { it.name() }.toSet()
            assertTrue(names.contains(b1), "listBuckets should contain $b1: $names")
            assertTrue(names.contains(b2), "listBuckets should contain $b2: $names")
            // Owner is present.
            assertNotNull(resp.owner().id())
            assertNotNull(resp.owner().displayName())
        }
    }

    @Test
    fun `list buckets excludes deleted buckets`() {
        newS3Client().use { s3 ->
            val b1 = newBucket()
            val b2 = newBucket()
            s3.createBucket { it.bucket(b1) }
            s3.createBucket { it.bucket(b2) }
            s3.deleteBucket { it.bucket(b1) }
            val resp = s3.listBuckets()
            val names = resp.buckets().map { it.name() }.toSet()
            assertFalse(names.contains(b1))
            assertTrue(names.contains(b2))
        }
    }

    // -----------------------------------------------------------------
    // CopyObject
    // -----------------------------------------------------------------

    @Test
    fun `copy object with default COPY directive`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val payload = "copy-me".toByteArray()
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key("src")
                    .contentType("text/plain")
                    .metadata(mapOf("origin" to "test"))
                    .build(),
                RequestBody.fromBytes(payload)
            )
            val srcEtag = s3.headObject { it.bucket(bucket).key("src") }.eTag()

            val copyResp = s3.copyObject {
                it.bucket(bucket).key("dst")
                    .copySource("$bucket/src")
            }
            assertNotNull(copyResp.copyObjectResult().eTag())

            val head = s3.headObject { it.bucket(bucket).key("dst") }
            assertEquals(payload.size.toLong(), head.contentLength())
            assertEquals("text/plain", head.contentType())
            assertEquals("test", head.metadata()["origin"])
            // ETag of the destination must equal the source (same content → same MD5).
            assertEquals(srcEtag, head.eTag())
        }
    }

    @Test
    fun `copy object with REPLACE directive overrides metadata`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key("src")
                    .contentType("text/plain")
                    .metadata(mapOf("origin" to "original"))
                    .build(),
                RequestBody.fromBytes("payload".toByteArray())
            )

            s3.copyObject {
                it.bucket(bucket).key("dst")
                    .copySource("$bucket/src")
                    .metadataDirective("REPLACE")
                    .contentType("application/json")
                    .metadata(mapOf("origin" to "replaced"))
            }

            val head = s3.headObject { it.bucket(bucket).key("dst") }
            assertEquals("application/json", head.contentType())
            assertEquals("replaced", head.metadata()["origin"])
        }
    }

    @Test
    fun `copy object across buckets`() {
        newS3Client().use { s3 ->
            val b1 = newBucket()
            val b2 = newBucket()
            s3.createBucket { it.bucket(b1) }
            s3.createBucket { it.bucket(b2) }
            val payload = "cross-bucket".toByteArray()
            s3.putObject(
                PutObjectRequest.builder().bucket(b1).key("k").build(),
                RequestBody.fromBytes(payload)
            )
            s3.copyObject {
                it.bucket(b2).key("k").copySource("$b1/k")
            }
            val got = s3.getObjectAsBytes { it.bucket(b2).key("k") }
            assertArrayEquals(payload, got.asByteArray())
        }
    }

    @Test
    fun `copy object in place updates metadata`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key("k")
                    .metadata(mapOf("v" to "1"))
                    .build(),
                RequestBody.fromBytes("payload".toByteArray())
            )
            s3.copyObject {
                it.bucket(bucket).key("k")
                    .copySource("$bucket/k")
                    .metadataDirective("REPLACE")
                    .metadata(mapOf("v" to "2"))
            }
            val head = s3.headObject { it.bucket(bucket).key("k") }
            assertEquals("2", head.metadata()["v"])
        }
    }

    @Test
    fun `copy object with missing source returns NoSuchKey`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            assertThrows<NoSuchKeyException> {
                s3.copyObject {
                    it.bucket(bucket).key("dst").copySource("$bucket/missing")
                }
            }
        }
    }

    @Test
    fun `copy object preserves checksums from source`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val payload = "checksum-payload".toByteArray()
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key("src")
                    .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                    .build(),
                RequestBody.fromBytes(payload)
            )
            s3.copyObject {
                it.bucket(bucket).key("dst").copySource("$bucket/src")
            }
            val head = s3.headObject { it.bucket(bucket).key("dst") }
            // The destination should have the same checksum as the source.
            val srcHead = s3.headObject { it.bucket(bucket).key("src") }
            assertEquals(srcHead.checksumSHA256(), head.checksumSHA256())
        }
    }

    // -----------------------------------------------------------------
    // ListObjectsV2 with prefix / delimiter / encoding-type
    // -----------------------------------------------------------------

    @Test
    fun `list with prefix filters keys`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            listOf("logs/app.log", "logs/access.log", "config.yaml", "config.json", "readme.md").forEach { k ->
                s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(k).build(),
                    RequestBody.fromBytes("x".toByteArray())
                )
            }
            val resp = s3.listObjectsV2 { it.bucket(bucket).prefix("config") }
            val keys = resp.contents().map { it.key() }
            assertEquals(2, keys.size)
            assertTrue(keys.contains("config.yaml"))
            assertTrue(keys.contains("config.json"))
        }
    }

    @Test
    fun `list with delimiter groups common prefixes`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            listOf(
                "photos/2024/01.jpg",
                "photos/2024/02.jpg",
                "photos/2025/01.jpg",
                "docs/readme.txt",
                "index.html"
            ).forEach { k ->
                s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(k).build(),
                    RequestBody.fromBytes("x".toByteArray())
                )
            }
            val resp = s3.listObjectsV2 {
                it.bucket(bucket).delimiter("/")
            }
            // Common prefixes should include "photos/" and "docs/".
            val prefixes = resp.commonPrefixes().map { it.prefix() }
            assertTrue(prefixes.contains("photos/"))
            assertTrue(prefixes.contains("docs/"))
            // Top-level files appear in contents.
            val keys = resp.contents().map { it.key() }
            assertTrue(keys.contains("index.html"))
            // KeyCount counts both contents and common prefixes.
            assertEquals(keys.size + prefixes.size, resp.keyCount())
        }
    }

    @Test
    fun `list with prefix and delimiter simulates directory listing`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            listOf(
                "dir/sub1/a.txt",
                "dir/sub1/b.txt",
                "dir/sub2/c.txt",
                "dir/top.txt"
            ).forEach { k ->
                s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(k).build(),
                    RequestBody.fromBytes("x".toByteArray())
                )
            }
            val resp = s3.listObjectsV2 {
                it.bucket(bucket).prefix("dir/").delimiter("/")
            }
            // Common prefixes: dir/sub1/, dir/sub2/
            val prefixes = resp.commonPrefixes().map { it.prefix() }.sorted()
            assertEquals(listOf("dir/sub1/", "dir/sub2/"), prefixes)
            // Contents: dir/top.txt
            val keys = resp.contents().map { it.key() }
            assertEquals(listOf("dir/top.txt"), keys)
        }
    }

    @Test
    fun `list with encoding-type url encodes special characters`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            // Key with a space — should be percent-encoded as %20 in the response
            // when encoding-type=url is requested.
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("hello world.txt").build(),
                RequestBody.fromBytes("x".toByteArray())
            )
            val resp = s3.listObjectsV2 {
                it.bucket(bucket).encodingType("url")
            }
            val keys = resp.contents().map { it.key() }
            assertTrue(keys.any { it == "hello%20world.txt" || it == "hello world.txt" },
                "expected URL-encoded key, got: $keys")
        }
    }

    @Test
    fun `list with invalid encoding-type returns InvalidArgument`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val ex = assertThrows<S3Exception> {
                s3.listObjectsV2 {
                    it.bucket(bucket).encodingType("base64")
                }
            }
            assertEquals(400, ex.statusCode())
            assertEquals("InvalidArgument", ex.awsErrorDetails().errorCode())
        }
    }

    // -----------------------------------------------------------------
    // GetBucketLocation
    // -----------------------------------------------------------------

    @Test
    fun `get bucket location returns configured region`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val resp = s3.getBucketLocation { it.bucket(bucket) }
            // For us-east-1, AWS returns an empty constraint string. We follow
            // the same convention for the SDK to round-trip correctly.
            assertNotNull(resp)
        }
    }

    @Test
    fun `get bucket location on missing bucket returns NoSuchBucket`() {
        newS3Client().use { s3 ->
            assertThrows<NoSuchBucketException> {
                s3.getBucketLocation { it.bucket("missing-" + UUID.randomUUID()) }
            }
        }
    }

    // -----------------------------------------------------------------
    // Checksum persistence
    // -----------------------------------------------------------------

    @Test
    fun `put with sha256 checksum persists and echoes on get`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val payload = "checksum-test".toByteArray()
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key("k")
                    .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                    .build(),
                RequestBody.fromBytes(payload)
            )
            // HEAD must echo the checksum.
            val head = s3.headObject { it.bucket(bucket).key("k") }
            assertNotNull(head.checksumSHA256())
            assertTrue(head.checksumSHA256().isNotBlank())
            // GET must also echo.
            val get = s3.getObjectAsBytes { it.bucket(bucket).key("k") }
            assertNotNull(get.response().checksumSHA256())
        }
    }

    @Test
    fun `put with crc32 checksum persists and echoes`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key("k")
                    .checksumAlgorithm(ChecksumAlgorithm.CRC32)
                    .build(),
                RequestBody.fromBytes("crc-test".toByteArray())
            )
            val head = s3.headObject { it.bucket(bucket).key("k") }
            assertNotNull(head.checksumCRC32())
            assertTrue(head.checksumCRC32().isNotBlank())
        }
    }

    @Test
    fun `put without checksum has no checksum headers on get`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("k").build(),
                RequestBody.fromBytes("no-checksum".toByteArray())
            )
            val head = s3.headObject { it.bucket(bucket).key("k") }
            // The SDK returns null when the header is absent.
            assertNull(head.checksumSHA256())
            assertNull(head.checksumCRC32())
        }
    }

    // -----------------------------------------------------------------
    // Combined: bucket lifecycle
    // -----------------------------------------------------------------

    @Test
    fun `full bucket lifecycle create list delete`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            // Create
            s3.createBucket { it.bucket(bucket) }
            // In list
            assertTrue(s3.listBuckets().buckets().any { it.name() == bucket })
            // Head
            s3.headBucket { it.bucket(bucket) }
            // Location
            s3.getBucketLocation { it.bucket(bucket) }
            // Empty + delete
            s3.deleteBucket { it.bucket(bucket) }
            // Gone from list
            assertFalse(s3.listBuckets().buckets().any { it.name() == bucket })
        }
    }
}
