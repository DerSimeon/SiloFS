package app.silofs.test

import io.kotest.matchers.shouldBe
import app.silofs.blob.BlobConsistencyChecker
import app.silofs.blob.FsBlobStore
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ServerConcurrencyTest : AbstractS3ServerTest() {

    private fun newBucket(): String = "conc-" + UUID.randomUUID().toString().take(8).lowercase()

    private fun assertNoMissingBlobs() {
        val report = BlobConsistencyChecker(FsBlobStore(dataDir), database).check()
        assertEquals(0, report.missingBlobs.size, "missing blob references: ${report.missingBlobs}")
    }

    @Test
    fun `parallel puts of different keys`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }

            val workers = 8
            val perWorker = 25
            val pool = Executors.newFixedThreadPool(workers)
            val latch = CountDownLatch(workers)
            val errors = ConcurrentHashMap<Int, Throwable>()

            for (w in 0 until workers) {
                pool.submit {
                    try {
                        for (i in 0 until perWorker) {
                            val key = "w${w}-k${i}"
                            val payload = "$w-$i".toByteArray()
                            s3.putObject(
                                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                                RequestBody.fromBytes(payload)
                            )
                            val got = s3.getObjectAsBytes { it.bucket(bucket).key(key) }
                            assertArrayEquals(payload, got.asByteArray())
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

            val listed = s3.listObjectsV2 { it.bucket(bucket).maxKeys(1000) }
            assertEquals(workers * perWorker, listed.contents().size)
            assertNoMissingBlobs()
        }
    }

    @Test
    fun `parallel reads and writes on the same key`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }

            // Seed
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key("hot").build(),
                RequestBody.fromBytes("v0".toByteArray())
            )

            val readers = 4
            val writers = 4
            val iterations = 25
            val pool = Executors.newFixedThreadPool(readers + writers)
            val latch = CountDownLatch(readers + writers)
            val errors = ConcurrentHashMap<String, Throwable>()

            for (i in 0 until readers) {
                pool.submit {
                    try {
                        repeat(iterations) {
                            // Just verify the GET succeeds — the value can be anything.
                            s3.getObjectAsBytes { it.bucket(bucket).key("hot") }
                        }
                    } catch (t: Throwable) {
                        errors["reader-$i"] = t
                    } finally {
                        latch.countDown()
                    }
                }
            }
            for (i in 0 until writers) {
                pool.submit {
                    try {
                        repeat(iterations) { n ->
                            val payload = "w$i-$n".toByteArray()
                            s3.putObject(
                                PutObjectRequest.builder().bucket(bucket).key("hot").build(),
                                RequestBody.fromBytes(payload)
                            )
                        }
                    } catch (t: Throwable) {
                        errors["writer-$i"] = t
                    } finally {
                        latch.countDown()
                    }
                }
            }
            assertTrue(latch.await(60, TimeUnit.SECONDS))
            assertTrue(errors.isEmpty(), "errors: $errors")
            pool.shutdown()
            assertNoMissingBlobs()
        }
    }

    @Test
    fun `parallel deletes and reads`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            s3.createBucket { it.bucket(bucket) }

            // Seed 50 keys
            (1..50).forEach { i ->
                s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key("k$i").build(),
                    RequestBody.fromBytes("v$i".toByteArray())
                )
            }

            val pool = Executors.newFixedThreadPool(2)
            val latch = CountDownLatch(2)
            val errors = ConcurrentHashMap<String, Throwable>()
            val deleted = AtomicInteger()

            // Deleter
            pool.submit {
                try {
                    (1..50).forEach { i ->
                        s3.deleteObject { it.bucket(bucket).key("k$i") }
                        deleted.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    errors["deleter"] = t
                } finally {
                    latch.countDown()
                }
            }
            // Reader
            pool.submit {
                try {
                    (1..50).forEach { i ->
                        runCatching {
                            s3.getObjectAsBytes { it.bucket(bucket).key("k$i") }
                        }
                    }
                } catch (t: Throwable) {
                    errors["reader"] = t
                } finally {
                    latch.countDown()
                }
            }
            assertTrue(latch.await(30, TimeUnit.SECONDS))
            assertEquals(50, deleted.get())
            assertTrue(errors.isEmpty(), "errors: $errors")
            pool.shutdown()
            assertNoMissingBlobs()
        }
    }

    @Test
    fun `duplicate complete multipart upload has one winner and leaves consistent blobs`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            val payload = ByteArray(6 * 1024 * 1024) { 0x33 }
            s3.createBucket { it.bucket(bucket) }
            val upload = s3.createMultipartUpload { it.bucket(bucket).key("dup-complete.bin") }
            val part =
                s3.uploadPart(
                    { it.bucket(bucket).key("dup-complete.bin").uploadId(upload.uploadId()).partNumber(1).contentLength(payload.size.toLong()) },
                    RequestBody.fromBytes(payload),
                )
            val completeRequest =
                CompletedMultipartUpload.builder()
                    .parts(CompletedPart.builder().partNumber(1).eTag(part.eTag()).build())
                    .build()

            val pool = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val successes = AtomicInteger()
            val errors = ConcurrentHashMap<Int, Throwable>()
            repeat(2) { index ->
                pool.submit {
                    try {
                        start.await()
                        s3.completeMultipartUpload {
                            it.bucket(bucket)
                                .key("dup-complete.bin")
                                .uploadId(upload.uploadId())
                                .multipartUpload(completeRequest)
                        }
                        successes.incrementAndGet()
                    } catch (t: Throwable) {
                        errors[index] = t
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(done.await(60, TimeUnit.SECONDS))
            pool.shutdown()

            assertEquals(1, successes.get(), "exactly one completion should win; errors=$errors")
            val head = s3.headObject { it.bucket(bucket).key("dup-complete.bin") }
            assertEquals(payload.size.toLong(), head.contentLength())
            assertNoMissingBlobs()
        }
    }

    @Test
    fun `abort racing complete multipart upload has one terminal outcome`() {
        newS3Client().use { s3 ->
            val bucket = newBucket()
            val payload = ByteArray(6 * 1024 * 1024) { 0x44 }
            s3.createBucket { it.bucket(bucket) }
            val upload = s3.createMultipartUpload { it.bucket(bucket).key("abort-race.bin") }
            val part =
                s3.uploadPart(
                    { it.bucket(bucket).key("abort-race.bin").uploadId(upload.uploadId()).partNumber(1).contentLength(payload.size.toLong()) },
                    RequestBody.fromBytes(payload),
                )
            val completeRequest =
                CompletedMultipartUpload.builder()
                    .parts(CompletedPart.builder().partNumber(1).eTag(part.eTag()).build())
                    .build()

            val pool = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val completeSuccess = AtomicInteger()
            val abortSuccess = AtomicInteger()
            val errors = ConcurrentHashMap<String, Throwable>()

            pool.submit {
                try {
                    start.await()
                    s3.completeMultipartUpload {
                        it.bucket(bucket)
                            .key("abort-race.bin")
                            .uploadId(upload.uploadId())
                            .multipartUpload(completeRequest)
                    }
                    completeSuccess.incrementAndGet()
                } catch (t: Throwable) {
                    errors["complete"] = t
                } finally {
                    done.countDown()
                }
            }
            pool.submit {
                try {
                    start.await()
                    s3.abortMultipartUpload { it.bucket(bucket).key("abort-race.bin").uploadId(upload.uploadId()) }
                    abortSuccess.incrementAndGet()
                } catch (t: Throwable) {
                    errors["abort"] = t
                } finally {
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(60, TimeUnit.SECONDS))
            pool.shutdown()

            assertEquals(1, completeSuccess.get() + abortSuccess.get(), "exactly one terminal operation should win; errors=$errors")
            if (completeSuccess.get() == 1) {
                val head = s3.headObject { it.bucket(bucket).key("abort-race.bin") }
                assertEquals(payload.size.toLong(), head.contentLength())
            } else {
                org.junit.jupiter.api.assertThrows<software.amazon.awssdk.services.s3.model.NoSuchKeyException> {
                    s3.headObject { it.bucket(bucket).key("abort-race.bin") }
                }
            }
            assertNoMissingBlobs()
        }
    }
}
