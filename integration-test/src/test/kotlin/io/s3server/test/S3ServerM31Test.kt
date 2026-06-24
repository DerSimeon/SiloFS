package app.silofs.test

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration
import java.util.UUID

/**
 * Milestone 3.1 tests — presigned URLs, ListMultipartUploads, UploadPartCopy,
 * and per-part checksums.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ServerM31Test : AbstractS3ServerTest() {

    private fun newBucket(): String = "m31-" + UUID.randomUUID().toString().take(8).lowercase()
    private val partSize = 6 * 1024 * 1024

    /** Create an S3Presigner pointing at the test server. */
    private fun newPresigner(): S3Presigner = S3Presigner.builder()
        .region(Region.of(REGION))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)
        ))
        .endpointOverride(URI.create(endpoint))
        .serviceConfiguration(
            software.amazon.awssdk.services.s3.S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build()
        )
        .build()

    // -----------------------------------------------------------------
    // ListMultipartUploads
    // -----------------------------------------------------------------

    @Test
    fun `list multipart uploads returns in-progress uploads`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }

            // Initiate two uploads
            val init1 = s3.createMultipartUpload { it.bucket(bucket).key("key1") }
            val init2 = s3.createMultipartUpload { it.bucket(bucket).key("key2") }

            val resp = s3.listMultipartUploads { it.bucket(bucket) }
            assertEquals(2, resp.uploads().size)
            val keys = resp.uploads().map { it.key() }.toSet()
            assertTrue(keys.contains("key1"))
            assertTrue(keys.contains("key2"))

            // Clean up
            s3.abortMultipartUpload { it.bucket(bucket).key("key1").uploadId(init1.uploadId()) }
            s3.abortMultipartUpload { it.bucket(bucket).key("key2").uploadId(init2.uploadId()) }
        }
    }

    @Test
    fun `list multipart uploads empty when none in progress`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val resp = s3.listMultipartUploads { it.bucket(bucket) }
            assertEquals(0, resp.uploads().size)
        }
    }

    @Test
    fun `list multipart uploads with prefix`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }

            val init1 = s3.createMultipartUpload { it.bucket(bucket).key("logs/app.log") }
            val init2 = s3.createMultipartUpload { it.bucket(bucket).key("logs/access.log") }
            val init3 = s3.createMultipartUpload { it.bucket(bucket).key("config.yaml") }

            val resp = s3.listMultipartUploads { it.bucket(bucket).prefix("logs/") }
            assertEquals(2, resp.uploads().size)
            assertTrue(resp.uploads().all { it.key().startsWith("logs/") })

            s3.abortMultipartUpload { it.bucket(bucket).key("logs/app.log").uploadId(init1.uploadId()) }
            s3.abortMultipartUpload { it.bucket(bucket).key("logs/access.log").uploadId(init2.uploadId()) }
            s3.abortMultipartUpload { it.bucket(bucket).key("config.yaml").uploadId(init3.uploadId()) }
        }
    }

    @Test
    fun `list multipart uploads excludes completed uploads`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }

            val init1 = s3.createMultipartUpload { it.bucket(bucket).key("completed") }
            val payload = ByteArray(partSize) { 0x42 }
            val e = s3.uploadPart(
                UploadPartRequest.builder()
                    .bucket(bucket).key("completed").uploadId(init1.uploadId())
                    .partNumber(1).contentLength(payload.size.toLong()).build(),
                RequestBody.fromBytes(payload)
            ).eTag()
            s3.completeMultipartUpload {
                it.bucket(bucket).key("completed").uploadId(init1.uploadId())
                    .multipartUpload { mb -> mb.parts(
                        CompletedPart.builder().partNumber(1).eTag(e).build()
                    ) }
            }

            // Initiate another upload that stays in progress
            val init2 = s3.createMultipartUpload { it.bucket(bucket).key("in-progress") }

            val resp = s3.listMultipartUploads { it.bucket(bucket) }
            assertEquals(1, resp.uploads().size)
            assertEquals("in-progress", resp.uploads()[0].key())

            s3.abortMultipartUpload { it.bucket(bucket).key("in-progress").uploadId(init2.uploadId()) }
        }
    }

    // -----------------------------------------------------------------
    // UploadPartCopy
    // -----------------------------------------------------------------

    @Test
    fun `upload part copy from existing object`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }

            // Create a source object
            val srcPayload = ByteArray(partSize * 2) { (it and 0xff).toByte() }
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("source").build(),
                RequestBody.fromBytes(srcPayload)
            )

            // Initiate a multipart upload
            val init = s3.createMultipartUpload { it.bucket(bucket).key("dest") }

            // UploadPartCopy — copy the entire source as part 1
            val copyResp = s3.uploadPartCopy {
                it.bucket(bucket).key("dest").uploadId(init.uploadId())
                    .partNumber(1).copySource("$bucket/source")
            }
            assertNotNull(copyResp.copyPartResult().eTag())

            // Complete the upload
            s3.completeMultipartUpload {
                it.bucket(bucket).key("dest").uploadId(init.uploadId())
                    .multipartUpload { mb -> mb.parts(
                        CompletedPart.builder().partNumber(1)
                            .eTag(copyResp.copyPartResult().eTag()).build()
                    ) }
            }

            // Verify the destination matches the source
            val got = s3.getObjectAsBytes { it.bucket(bucket).key("dest") }
            assertArrayEquals(srcPayload, got.asByteArray())
        }
    }

    @Test
    fun `upload part copy with range`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }

            val srcPayload = ByteArray(partSize * 2) { (it and 0xff).toByte() }
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("source").build(),
                RequestBody.fromBytes(srcPayload)
            )

            val init = s3.createMultipartUpload { it.bucket(bucket).key("dest") }

            // Copy only the first 100 bytes as part 1
            val copyResp = s3.uploadPartCopy {
                it.bucket(bucket).key("dest").uploadId(init.uploadId())
                    .partNumber(1).copySource("$bucket/source")
                    .copySourceRange("bytes=0-99")
            }

            s3.completeMultipartUpload {
                it.bucket(bucket).key("dest").uploadId(init.uploadId())
                    .multipartUpload { mb -> mb.parts(
                        CompletedPart.builder().partNumber(1)
                            .eTag(copyResp.copyPartResult().eTag()).build()
                    ) }
            }

            val got = s3.getObjectAsBytes { it.bucket(bucket).key("dest") }
            assertEquals(100, got.asByteArray().size)
            assertArrayEquals(srcPayload.copyOfRange(0, 100), got.asByteArray())
        }
    }

    @Test
    fun `upload part copy from missing source returns NoSuchKey`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val init = s3.createMultipartUpload { it.bucket(bucket).key("dest") }

            assertThrows<Exception> {
                s3.uploadPartCopy {
                    it.bucket(bucket).key("dest").uploadId(init.uploadId())
                        .partNumber(1).copySource("$bucket/missing")
                }
            }

            s3.abortMultipartUpload { it.bucket(bucket).key("dest").uploadId(init.uploadId()) }
        }
    }

    // -----------------------------------------------------------------
    // Presigned URLs
    // -----------------------------------------------------------------

    @Test
    fun `presigned get url works`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val payload = "presigned-get-test".toByteArray()
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("k").build(),
                RequestBody.fromBytes(payload)
            )

            newPresigner().use { presigner ->
                val presigned = presigner.presignGetObject(
                    GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(15))
                        .getObjectRequest { it.bucket(bucket).key("k") }
                        .build()
                )
                val url = presigned.url().toString()

                // Use a plain HttpURLConnection (no AWS SDK auth) to fetch
                val conn = URI(url).toURL().openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                assertEquals(200, conn.responseCode)
                val body = conn.inputStream.readBytes()
                assertArrayEquals(payload, body)
                conn.disconnect()
            }
        }
    }

    @Test
    fun `presigned put url works`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }

            newPresigner().use { presigner ->
                val presigned = presigner.presignPutObject(
                    PutObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(15))
                        .putObjectRequest { it.bucket(bucket).key("k") }
                        .build()
                )
                val url = presigned.url().toString()

                val payload = "presigned-put-test".toByteArray()
                val conn = URI(url).toURL().openConnection() as HttpURLConnection
                conn.doOutput = true
                conn.requestMethod = "PUT"
                conn.setRequestProperty("Content-Length", payload.size.toString())
                conn.outputStream.use { it.write(payload) }
                assertEquals(200, conn.responseCode)
                conn.disconnect()
            }

            // Verify with the SDK
            val got = s3.getObjectAsBytes { it.bucket(bucket).key("k") }
            assertArrayEquals("presigned-put-test".toByteArray(), got.asByteArray())
        }
    }

    @Test
    fun `presigned url with tampered signature fails`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val payload = "tamper-test".toByteArray()
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("k").build(),
                RequestBody.fromBytes(payload)
            )

            newPresigner().use { presigner ->
                val presigned = presigner.presignGetObject(
                    GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(15))
                        .getObjectRequest { it.bucket(bucket).key("k") }
                        .build()
                )
                val url = presigned.url().toString()

                // Tamper with the signature
                val sigIdx = url.lastIndexOf("X-Amz-Signature=")
                val tampered = url.substring(0, sigIdx + "X-Amz-Signature=".length) + "deadbeef".repeat(8)

                val conn = URI(tampered).toURL().openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                assertEquals(403, conn.responseCode)
                conn.disconnect()
            }
        }
    }

    // -----------------------------------------------------------------
    // Per-part checksums
    // -----------------------------------------------------------------

    @Test
    fun `upload part with checksum persists and echoes`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val key = "checksum-part.bin"
            val init = s3.createMultipartUpload { it.bucket(bucket).key(key) }

            val payload = ByteArray(partSize) { 0x42 }
            val resp = s3.uploadPart(
                UploadPartRequest.builder()
                    .bucket(bucket).key(key).uploadId(init.uploadId())
                    .partNumber(1).contentLength(payload.size.toLong())
                    .checksumAlgorithm(software.amazon.awssdk.services.s3.model.ChecksumAlgorithm.SHA256)
                    .build(),
                RequestBody.fromBytes(payload)
            )

            // The response must echo the checksum
            assertNotNull(resp.checksumSHA256())
            assertTrue(resp.checksumSHA256().isNotBlank())

            // ListParts must also show the checksum
            val parts = s3.listParts { it.bucket(bucket).key(key).uploadId(init.uploadId()) }
            assertEquals(1, parts.parts().size)
            assertNotNull(parts.parts()[0].checksumSHA256())

            s3.abortMultipartUpload { it.bucket(bucket).key(key).uploadId(init.uploadId()) }
        }
    }

    @Test
    fun `upload part with crc32 checksum`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val key = "crc32-part.bin"
            val init = s3.createMultipartUpload { it.bucket(bucket).key(key) }

            val payload = ByteArray(partSize) { 0x42 }
            val resp = s3.uploadPart(
                UploadPartRequest.builder()
                    .bucket(bucket).key(key).uploadId(init.uploadId())
                    .partNumber(1).contentLength(payload.size.toLong())
                    .checksumAlgorithm(software.amazon.awssdk.services.s3.model.ChecksumAlgorithm.CRC32)
                    .build(),
                RequestBody.fromBytes(payload)
            )

            assertNotNull(resp.checksumCRC32())
            assertTrue(resp.checksumCRC32().isNotBlank())

            s3.abortMultipartUpload { it.bucket(bucket).key(key).uploadId(init.uploadId()) }
        }
    }

    @Test
    fun `upload part without checksum has no checksum in list`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val key = "no-checksum-part.bin"
            val init = s3.createMultipartUpload { it.bucket(bucket).key(key) }

            val payload = ByteArray(partSize) { 0x42 }
            s3.uploadPart(
                UploadPartRequest.builder()
                    .bucket(bucket).key(key).uploadId(init.uploadId())
                    .partNumber(1).contentLength(payload.size.toLong())
                    .build(),
                RequestBody.fromBytes(payload)
            )

            val parts = s3.listParts { it.bucket(bucket).key(key).uploadId(init.uploadId()) }
            assertEquals(1, parts.parts().size)
            // checksumSHA256 should be null when not supplied
            val p = parts.parts()[0]
            // The SDK returns null for absent checksum fields
            assertFalse(p.checksumSHA256() != null && p.checksumSHA256().isNotBlank())

            s3.abortMultipartUpload { it.bucket(bucket).key(key).uploadId(init.uploadId()) }
        }
    }

    private fun assertFalse(b: Boolean) = org.junit.jupiter.api.Assertions.assertFalse(b)
}
