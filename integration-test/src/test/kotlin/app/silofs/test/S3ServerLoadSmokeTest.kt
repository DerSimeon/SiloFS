package app.silofs.test

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.sync.RequestBody
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

class S3ServerLoadSmokeTest : AbstractS3ServerTest() {
    @Test
    fun `small object load smoke stays correct under concurrent clients`() {
        val bucket = "load-${UUID.randomUUID().toString().take(8)}"
        val objectCount = 200
        val payload = ByteArray(4096) { (it % 251).toByte() }
        newS3Client().use { s3 ->
            s3.createBucket { it.bucket(bucket) }
            Executors.newFixedThreadPool(16).use { pool ->
                val puts =
                    (0 until objectCount).map { i ->
                        pool.submit {
                            s3.putObject(
                                { it.bucket(bucket).key("obj-$i") },
                                RequestBody.fromBytes(payload),
                            )
                        }
                    }
                puts.forEach { it.get() }
                val gets: List<Future<ByteArray>> =
                    (0 until objectCount).map { i ->
                        pool.submit(
                            Callable {
                                s3.getObjectAsBytes { it.bucket(bucket).key("obj-$i") }.asByteArray()
                            },
                        )
                    }
                gets.forEach { assertArrayEquals(payload, it.get()) }
            }
            val listed = s3.listObjectsV2 { it.bucket(bucket).maxKeys(objectCount) }
            assertEquals(objectCount, listed.keyCount())
        }
    }
}
