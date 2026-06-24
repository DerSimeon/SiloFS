package app.silofs.test

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.core.sync.RequestBody
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
        }
    }
}
