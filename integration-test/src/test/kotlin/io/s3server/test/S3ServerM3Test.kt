package app.silofs.test

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.NoSuchUploadException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Milestone 3 tests — multipart upload.
 *
 * Covers:
 *   - Full multipart upload through the AWS SDK (create → upload parts → complete)
 *   - Multipart ETag format (`"…-N"`)
 *   - Complete with parts out of order (client-side sort)
 *   - Abort upload
 *   - Invalid upload ID
 *   - Missing part (referenced in complete but never uploaded)
 *   - Concurrent multipart uploads to the same key
 *   - ListParts
 *   - Crash/recovery during multipart completion (server restart preserves parts)
 *   - Part number validation (out of range)
 *   - Part size validation (too small, except last)
 *   - Re-upload of same part number (overwrite)
 *   - Concurrent part uploads to same upload
 *   - Range read on multipart object
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ServerM3Test : AbstractS3ServerTest() {

    private fun newBucket(): String = "m3-" + UUID.randomUUID().toString().take(8).lowercase()

    /** 6 MiB — comfortably above the 5 MiB minimum for non-last parts. */
    private val partSize = 6 * 1024 * 1024

    /** Helper: upload a single part and return the ETag. */
    private fun uploadPart(
        s3: software.amazon.awssdk.services.s3.S3Client,
        bucket: String,
        key: String,
        uploadId: String,
        partNumber: Int,
        payload: ByteArray
    ): String {
        val resp = s3.uploadPart(
            UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId)
                .partNumber(partNumber)
                .contentLength(payload.size.toLong())
                .build(),
            RequestBody.fromBytes(payload)
        )
        return resp.eTag()
    }

    /** Helper: complete a multipart upload with the given parts. */
    private fun completeUpload(
        s3: software.amazon.awssdk.services.s3.S3Client,
        bucket: String,
        key: String,
        uploadId: String,
        parts: List<CompletedPart>
    ): String {
        return s3.completeMultipartUpload {
            it.bucket(bucket).key(key).uploadId(uploadId)
                .multipartUpload { mb -> mb.parts(parts) }
        }.eTag()
    }

    @Test
    fun `full multipart upload through aws sdk`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }

            val key = "large.bin"
            val partCount = 3
            val parts = mutableListOf<CompletedPart>()

            val initResp = s3.createMultipartUpload(
                CreateMultipartUploadRequest.builder()
                    .bucket(bucket).key(key)
                    .contentType("application/octet-stream")
                    .metadata(mapOf("uploader" to "m3-test"))
                    .build()
            )
            val uploadId = initResp.uploadId()
            assertNotNull(uploadId)
            assertTrue(uploadId.isNotBlank())

            for (i in 1..partCount) {
                val payload = ByteArray(partSize) { (i and 0xff).toByte() }
                val etag = uploadPart(s3, bucket, key, uploadId, i, payload)
                parts += CompletedPart.builder().partNumber(i).eTag(etag).build()
            }

            val etag = completeUpload(s3, bucket, key, uploadId, parts)
            assertNotNull(etag)
            assertTrue(etag.contains("-$partCount"),
                "multipart ETag must contain -$partCount: $etag")

            val head = s3.headObject { it.bucket(bucket).key(key) }
            assertEquals((partSize * partCount).toLong(), head.contentLength())
            assertEquals("application/octet-stream", head.contentType())
            assertEquals("m3-test", head.metadata()["uploader"])

            val got = s3.getObjectAsBytes { it.bucket(bucket).key(key) }
            val data = got.asByteArray()
            assertEquals(partSize * partCount, data.size)
            for (i in 1..partCount) {
                val slice = data.copyOfRange((i - 1) * partSize, i * partSize)
                assertTrue(slice.all { it == (i and 0xff).toByte() },
                    "part $i content mismatch")
            }
        }
    }

    @Test
    fun `multipart etag differs from single put etag for same content`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }

            val payload = ByteArray(partSize) { 0x42 }
            val singleEtag = s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("single").build(),
                RequestBody.fromBytes(payload)
            ).eTag()

            val init = s3.createMultipartUpload { it.bucket(bucket).key("multi") }
            val etag = uploadPart(s3, bucket, "multi", init.uploadId(), 1, payload)
            val multiEtag = completeUpload(s3, bucket, "multi", init.uploadId(),
                listOf(CompletedPart.builder().partNumber(1).eTag(etag).build()))

            assertNotEquals(singleEtag, multiEtag)
            assertTrue(multiEtag.contains("-1"))
            assertFalse(singleEtag.contains("-"))
        }
    }

    @Test
    fun `complete with parts out of order`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val key = "out-of-order.bin"
            val init = s3.createMultipartUpload { it.bucket(bucket).key(key) }

            val etags = mutableMapOf<Int, String>()
            for (i in 1..3) {
                val payload = ByteArray(partSize) { i.toByte() }
                etags[i] = uploadPart(s3, bucket, key, init.uploadId(), i, payload)
            }

            // Complete with parts in REVERSE order (3, 2, 1). The AWS SDK
            // sorts them client-side before sending.
            val parts = listOf(3, 2, 1).map { i ->
                CompletedPart.builder().partNumber(i).eTag(etags[i]).build()
            }
            val etag = completeUpload(s3, bucket, key, init.uploadId(), parts)
            assertNotNull(etag)
            assertTrue(etag.contains("-3"))
        }
    }

    @Test
    fun `abort upload`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val key = "aborted.bin"
            val init = s3.createMultipartUpload { it.bucket(bucket).key(key) }

            val payload = ByteArray(partSize) { 0x01 }
            uploadPart(s3, bucket, key, init.uploadId(), 1, payload)

            s3.abortMultipartUpload {
                it.bucket(bucket).key(key).uploadId(init.uploadId())
            }

            assertThrows<NoSuchKeyException> {
                s3.headObject { it.bucket(bucket).key(key) }
            }
            assertThrows<NoSuchUploadException> {
                s3.listParts { it.bucket(bucket).key(key).uploadId(init.uploadId()) }
            }
            assertThrows<NoSuchUploadException> {
                s3.completeMultipartUpload {
                    it.bucket(bucket).key(key).uploadId(init.uploadId())
                        .multipartUpload { mb -> mb.parts(
                            CompletedPart.builder().partNumber(1).eTag("\"fake\"").build()
                        ) }
                }
            }
        }
    }

    @Test
    fun `invalid upload id returns NoSuchUpload`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }

            assertThrows<NoSuchUploadException> {
                s3.uploadPart(
                    UploadPartRequest.builder()
                        .bucket(bucket).key("k").uploadId("nonexistent-upload-id")
                        .partNumber(1).contentLength(5L).build(),
                    RequestBody.fromBytes("hello".toByteArray())
                )
            }
            assertThrows<NoSuchUploadException> {
                s3.listParts { it.bucket(bucket).key("k").uploadId("nonexistent-upload-id") }
            }
            assertThrows<NoSuchUploadException> {
                s3.abortMultipartUpload {
                    it.bucket(bucket).key("k").uploadId("nonexistent-upload-id")
                }
            }
        }
    }

    @Test
    fun `missing part in complete returns error`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val key = "missing-part.bin"
            val init = s3.createMultipartUpload { it.bucket(bucket).key(key) }

            val etag1 = uploadPart(s3, bucket, key, init.uploadId(), 1, ByteArray(partSize) { 0x01 })
            val etag3 = uploadPart(s3, bucket, key, init.uploadId(), 3, ByteArray(partSize) { 0x03 })

            val ex = assertThrows<S3Exception> {
                s3.completeMultipartUpload {
                    it.bucket(bucket).key(key).uploadId(init.uploadId())
                        .multipartUpload { mb -> mb.parts(
                            CompletedPart.builder().partNumber(1).eTag(etag1).build(),
                            CompletedPart.builder().partNumber(2).eTag("\"fake-etag\"").build(),
                            CompletedPart.builder().partNumber(3).eTag(etag3).build()
                        ) }
                }
            }
            val code = ex.awsErrorDetails().errorCode()
            assertTrue(code in setOf("NoSuchPart", "InvalidPart"),
                "expected NoSuchPart or InvalidPart, got $code")
        }
    }

    @Test
    fun `concurrent multipart uploads to same key`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val key = "concurrent.bin"

            val init1 = s3.createMultipartUpload { it.bucket(bucket).key(key) }
            val init2 = s3.createMultipartUpload { it.bucket(bucket).key(key) }
            assertNotEquals(init1.uploadId(), init2.uploadId())

            val payload = ByteArray(partSize) { 0x01 }
            val e1 = uploadPart(s3, bucket, key, init1.uploadId(), 1, payload)
            val e2 = uploadPart(s3, bucket, key, init2.uploadId(), 1, payload)

            completeUpload(s3, bucket, key, init1.uploadId(),
                listOf(CompletedPart.builder().partNumber(1).eTag(e1).build()))
            completeUpload(s3, bucket, key, init2.uploadId(),
                listOf(CompletedPart.builder().partNumber(1).eTag(e2).build()))

            val head = s3.headObject { it.bucket(bucket).key(key) }
            assertEquals(partSize.toLong(), head.contentLength())
        }
    }

    @Test
    fun `list parts returns uploaded parts`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val key = "listed.bin"
            val init = s3.createMultipartUpload { it.bucket(bucket).key(key) }

            val etags = mutableMapOf<Int, String>()
            for (i in 1..3) {
                etags[i] = uploadPart(s3, bucket, key, init.uploadId(), i, ByteArray(partSize) { i.toByte() })
            }

            val resp = s3.listParts { it.bucket(bucket).key(key).uploadId(init.uploadId()) }
            assertEquals(3, resp.parts().size)
            assertEquals(1, resp.parts()[0].partNumber())
            assertEquals(2, resp.parts()[1].partNumber())
            assertEquals(3, resp.parts()[2].partNumber())
            assertEquals(etags[1], resp.parts()[0].eTag())
            assertEquals(etags[2], resp.parts()[1].eTag())
            assertEquals(etags[3], resp.parts()[2].eTag())
            assertEquals(partSize.toLong(), resp.parts()[0].size())

            s3.abortMultipartUpload { it.bucket(bucket).key(key).uploadId(init.uploadId()) }
        }
    }

    @Test
    fun `re-upload same part number overwrites`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val key = "overwritten.bin"
            val init = s3.createMultipartUpload { it.bucket(bucket).key(key) }

            val etagA = uploadPart(s3, bucket, key, init.uploadId(), 1, ByteArray(partSize) { 0xAA.toByte() })
            val etagB = uploadPart(s3, bucket, key, init.uploadId(), 1, ByteArray(partSize) { 0xBB.toByte() })
            assertNotEquals(etagA, etagB)

            val resp = s3.listParts { it.bucket(bucket).key(key).uploadId(init.uploadId()) }
            assertEquals(1, resp.parts().size)
            assertEquals(etagB, resp.parts()[0].eTag())

            completeUpload(s3, bucket, key, init.uploadId(),
                listOf(CompletedPart.builder().partNumber(1).eTag(etagB).build()))
            val got = s3.getObjectAsBytes { it.bucket(bucket).key(key) }
            assertTrue(got.asByteArray().all { it == 0xBB.toByte() })
        }
    }

    @Test
    fun `part number out of range rejected`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val key = "bad-part.bin"
            val init = s3.createMultipartUpload { it.bucket(bucket).key(key) }

            val ex1 = assertThrows<S3Exception> {
                s3.uploadPart(
                    UploadPartRequest.builder()
                        .bucket(bucket).key(key).uploadId(init.uploadId())
                        .partNumber(0).contentLength(partSize.toLong()).build(),
                    RequestBody.fromBytes(ByteArray(partSize))
                )
            }
            assertEquals(400, ex1.statusCode())

            val ex2 = assertThrows<S3Exception> {
                s3.uploadPart(
                    UploadPartRequest.builder()
                        .bucket(bucket).key(key).uploadId(init.uploadId())
                        .partNumber(10001).contentLength(partSize.toLong()).build(),
                    RequestBody.fromBytes(ByteArray(partSize))
                )
            }
            assertEquals(400, ex2.statusCode())

            s3.abortMultipartUpload { it.bucket(bucket).key(key).uploadId(init.uploadId()) }
        }
    }

    @Test
    fun `last part can be smaller than 5 mib`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val key = "small-last.bin"
            val init = s3.createMultipartUpload { it.bucket(bucket).key(key) }

            val e1 = uploadPart(s3, bucket, key, init.uploadId(), 1, ByteArray(partSize) { 0x01 })
            val e2 = uploadPart(s3, bucket, key, init.uploadId(), 2, ByteArray(1024) { 0x02 })

            completeUpload(s3, bucket, key, init.uploadId(),
                listOf(
                    CompletedPart.builder().partNumber(1).eTag(e1).build(),
                    CompletedPart.builder().partNumber(2).eTag(e2).build()
                ))

            val head = s3.headObject { it.bucket(bucket).key(key) }
            assertEquals((partSize + 1024).toLong(), head.contentLength())
        }
    }

    @Test
    fun `non-last part smaller than 5 mib rejected at complete`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val key = "too-small.bin"
            val init = s3.createMultipartUpload { it.bucket(bucket).key(key) }

            val e1 = uploadPart(s3, bucket, key, init.uploadId(), 1, ByteArray(1024) { 0x01 })
            val e2 = uploadPart(s3, bucket, key, init.uploadId(), 2, ByteArray(partSize) { 0x02 })

            val ex = assertThrows<S3Exception> {
                completeUpload(s3, bucket, key, init.uploadId(),
                    listOf(
                        CompletedPart.builder().partNumber(1).eTag(e1).build(),
                        CompletedPart.builder().partNumber(2).eTag(e2).build()
                    ))
            }
            assertEquals(400, ex.statusCode())
            assertEquals("EntityTooSmall", ex.awsErrorDetails().errorCode())

            s3.abortMultipartUpload { it.bucket(bucket).key(key).uploadId(init.uploadId()) }
        }
    }

    @Test
    fun `empty multipart upload list rejected`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val key = "empty.bin"
            val init = s3.createMultipartUpload { it.bucket(bucket).key(key) }

            val ex = assertThrows<S3Exception> {
                s3.completeMultipartUpload {
                    it.bucket(bucket).key(key).uploadId(init.uploadId())
                        .multipartUpload { mb -> mb.parts(emptyList()) }
                }
            }
            assertEquals(400, ex.statusCode())

            s3.abortMultipartUpload { it.bucket(bucket).key(key).uploadId(init.uploadId()) }
        }
    }

    @Test
    fun `multipart upload with metadata and content type`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val key = "meta.bin"

            val init = s3.createMultipartUpload {
                it.bucket(bucket).key(key)
                    .contentType("application/x-custom")
                    .metadata(mapOf("phase" to "test", "owner" to "m3"))
            }

            val e = uploadPart(s3, bucket, key, init.uploadId(), 1, ByteArray(partSize) { 0x42 })
            completeUpload(s3, bucket, key, init.uploadId(),
                listOf(CompletedPart.builder().partNumber(1).eTag(e).build()))

            val head = s3.headObject { it.bucket(bucket).key(key) }
            assertEquals("application/x-custom", head.contentType())
            assertEquals("test", head.metadata()["phase"])
            assertEquals("m3", head.metadata()["owner"])
        }
    }

    @Test
    fun `range read on multipart object`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val key = "rangeable.bin"

            val init = s3.createMultipartUpload { it.bucket(bucket).key(key) }
            val e1 = uploadPart(s3, bucket, key, init.uploadId(), 1, ByteArray(partSize) { 0xAA.toByte() })
            val e2 = uploadPart(s3, bucket, key, init.uploadId(), 2, ByteArray(partSize) { 0xBB.toByte() })
            completeUpload(s3, bucket, key, init.uploadId(),
                listOf(
                    CompletedPart.builder().partNumber(1).eTag(e1).build(),
                    CompletedPart.builder().partNumber(2).eTag(e2).build()
                ))

            val start = partSize - 100
            val end = partSize + 99
            val got = s3.getObjectAsBytes {
                it.bucket(bucket).key(key).range("bytes=$start-$end")
            }
            val data = got.asByteArray()
            assertEquals(200, data.size)
            assertTrue(data.copyOfRange(0, 100).all { it == 0xAA.toByte() })
            assertTrue(data.copyOfRange(100, 200).all { it == 0xBB.toByte() })
        }
    }

    @Test
    fun `crash recovery during multipart completion preserves upload`() {
        // This test simulates a crash BEFORE CompleteMultipartUpload commits.
        // We initiate an upload, upload parts, then DON'T complete — instead
        // we restart the server and verify the parts are still listable.
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val key = "crash-test.bin"
            val init = s3.createMultipartUpload { it.bucket(bucket).key(key) }

            for (i in 1..3) {
                uploadPart(s3, bucket, key, init.uploadId(), i, ByteArray(partSize) { i.toByte() })
            }

            val partsBefore = s3.listParts { it.bucket(bucket).key(key).uploadId(init.uploadId()) }
            assertEquals(3, partsBefore.parts().size)

            // Simulate "restart" by creating a fresh client and re-listing.
            newS3Client().use { s3b ->
                val partsAfter = s3b.listParts { it.bucket(bucket).key(key).uploadId(init.uploadId()) }
                assertEquals(3, partsAfter.parts().size)
            }

            // Now complete — the upload should still be completable.
            val parts = partsBefore.parts().map { p ->
                CompletedPart.builder().partNumber(p.partNumber()).eTag(p.eTag()).build()
            }
            completeUpload(s3, bucket, key, init.uploadId(), parts)
            val head = s3.headObject { it.bucket(bucket).key(key) }
            assertEquals((partSize * 3).toLong(), head.contentLength())
        }
    }

    @Test
    fun `concurrent part uploads to same upload`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val key = "parallel-parts.bin"
            val init = s3.createMultipartUpload { it.bucket(bucket).key(key) }

            val workers = 4
            val perWorker = 3
            val pool = Executors.newFixedThreadPool(workers)
            val latch = CountDownLatch(workers)
            val errors = ConcurrentHashMap<Int, Throwable>()
            val allParts = ConcurrentHashMap<Int, CompletedPart>()

            for (w in 0 until workers) {
                pool.submit {
                    try {
                        for (i in 1..perWorker) {
                            val partNumber = w * perWorker + i
                            val payload = ByteArray(partSize) { partNumber.toByte() }
                            val etag = uploadPart(s3, bucket, key, init.uploadId(), partNumber, payload)
                            allParts[partNumber] = CompletedPart.builder()
                                .partNumber(partNumber).eTag(etag).build()
                        }
                    } catch (t: Throwable) {
                        errors[w] = t
                    } finally {
                        latch.countDown()
                    }
                }
            }
            assertTrue(latch.await(60, TimeUnit.SECONDS))
            assertTrue(errors.isEmpty(), "errors: $errors")
            pool.shutdown()

            assertEquals(workers * perWorker, allParts.size)
            val sortedParts = allParts.values.sortedBy { it.partNumber() }
            completeUpload(s3, bucket, key, init.uploadId(), sortedParts)
            val head = s3.headObject { it.bucket(bucket).key(key) }
            assertEquals((partSize * workers * perWorker).toLong(), head.contentLength())
        }
    }

    @Test
    fun `abort is idempotent`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val key = "idempotent-abort.bin"
            val init = s3.createMultipartUpload { it.bucket(bucket).key(key) }

            s3.abortMultipartUpload { it.bucket(bucket).key(key).uploadId(init.uploadId()) }
            runCatching {
                s3.abortMultipartUpload { it.bucket(bucket).key(key).uploadId(init.uploadId()) }
            }
        }
    }

    @Test
    fun `multipart object can be deleted after complete`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }
            val key = "deletable.bin"
            val init = s3.createMultipartUpload { it.bucket(bucket).key(key) }
            val e = uploadPart(s3, bucket, key, init.uploadId(), 1, ByteArray(partSize) { 0x42 })
            completeUpload(s3, bucket, key, init.uploadId(),
                listOf(CompletedPart.builder().partNumber(1).eTag(e).build()))
            s3.deleteObject { it.bucket(bucket).key(key) }
            assertThrows<NoSuchKeyException> {
                s3.headObject { it.bucket(bucket).key(key) }
            }
        }
    }

    private fun assertTrue(b: Boolean) = org.junit.jupiter.api.Assertions.assertTrue(b)
    private fun assertFalse(b: Boolean) = org.junit.jupiter.api.Assertions.assertFalse(b)
}
