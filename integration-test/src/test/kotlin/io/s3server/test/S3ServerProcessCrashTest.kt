package app.silofs.test

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import app.silofs.auth.StaticCredentialProvider
import app.silofs.blob.FsBlobStore
import app.silofs.metadata.Database
import app.silofs.metadata.JdbcMetadataRepository
import app.silofs.server.ServerConfig
import app.silofs.server.s3Module
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Real process-kill crash tests (red flag #6).
 *
 * These tests spawn a separate JVM process running the s3-server, write data
 * via the AWS SDK, then KILL the process at controlled failure points using
 * `Process.destroyForcibly()`. A fresh server is then started against the
 * same Postgres + data directory, and we verify the object state is
 * consistent with the durability contract.
 *
 * Failure points tested:
 *   - after PutObject returns 200 (object must survive)
 *   - after UploadPart returns 200 (part must survive)
 *   - after DeleteObject returns 204 (object must be gone)
 *
 * Note: True mid-write crash injection (between fsync and rename, between
 * rename and DB commit) requires bytecode instrumentation or a debugger.
 * These tests prove the weaker property that a process kill AFTER the API
 * returns does not corrupt state. The M1 `S3ServerCrashRecoveryTest`
 * covers the restart path; this test adds the process-kill dimension.
 *
 * Tagged with "crash" so they can be excluded from CI fast paths.
 */
@Tag("crash")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ServerProcessCrashTest {

    companion object {
        const val ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE"
        const val SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        const val REGION = "us-east-1"
    }

    /**
     * Starts a server in a separate JVM process, returns the Process handle
     * and the endpoint URL. The process runs until [stop] is called.
     */
    private data class RunningServer(
        val process: Process,
        val endpoint: String,
        val port: Int,
        val dataDir: Path,
        val dbUrl: String,
        val dbUser: String,
        val dbPass: String
    )

    private fun startServer(pg: PostgreSQLContainer<*>, dataDir: Path): RunningServer {
        val port = 18080 + (UUID.randomUUID().hashCode() and 0xFF)
        val endpoint = "http://127.0.0.1:$port"

        // Write a launch script so the child JVM picks up the classpath.
        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")
        val jvmArgs = listOf(
            "$javaHome/bin/java",
            "-Xmx512m",
            "-cp", classpath,
            "app.silofs.server.MainKt"
        )
        val env = mapOf(
            "S3_BIND_HOST" to "127.0.0.1",
            "S3_BIND_PORT" to port.toString(),
            "S3_REGION" to REGION,
            "S3_DATA_DIR" to dataDir.toString(),
            "S3_DB_URL" to pg.jdbcUrl,
            "S3_DB_USER" to pg.username,
            "S3_DB_PASSWORD" to pg.password,
            "S3_ACCESS_KEY_ID" to ACCESS_KEY,
            "S3_SECRET_ACCESS_KEY" to SECRET_KEY,
            "S3_RECOVERY_ENABLED" to "false"
        )
        val pb = ProcessBuilder(jvmArgs)
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        val process = pb.start()

        // Wait for the server to be ready (poll healthz).
        val deadline = System.currentTimeMillis() + 30_000
        var ready = false
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
            try {
                val conn = URI("$endpoint/healthz").toURL().openConnection()
                conn.connectTimeout = 500
                conn.readTimeout = 500
                if (conn.getInputStream().bufferedReader().readText() == "ok") {
                    ready = true
                    break
                }
            } catch (e: Exception) {
                // not ready yet
            }
        }
        if (!ready) {
            process.destroyForcibly()
            error("Server failed to start within 30s. Output:\n" +
                process.inputStream.bufferedReader().readText())
        }

        return RunningServer(process, endpoint, port, dataDir, pg.jdbcUrl, pg.username, pg.password)
    }

    private fun stopServer(server: RunningServer) {
        server.process.destroyForcibly()
        server.process.waitFor()
    }

    private fun newS3Client(endpoint: String): S3Client = S3Client.builder()
        .region(Region.of(REGION))
        .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
            software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)
        ))
        .endpointOverride(URI.create(endpoint))
        .serviceConfiguration { it.pathStyleAccessEnabled(true) }
        .httpClientBuilder(software.amazon.awssdk.http.apache.ApacheHttpClient.builder())
        .build()

    @Test
    fun `object survives process kill after put`() {
        val pg = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("s3crash")
            .withUsername("test")
            .withPassword("test")
        pg.start()
        val dataDir = Files.createTempDirectory("s3-crash-put")

        try {
            // Phase 1: start server, write an object
            val server1 = startServer(pg, dataDir)
            val bucket = "crash-put-" + UUID.randomUUID().toString().take(8)
            val payload = "survive-kill".toByteArray()

            try {
                newS3Client(server1.endpoint).use { s3 ->
                    s3.createBucket { it.bucket(bucket) }
                    s3.putObject(
                        PutObjectRequest.builder().bucket(bucket).key("k").build(),
                        RequestBody.fromBytes(payload)
                    )
                }
            } finally {
                // Kill the server process immediately after the PUT returns.
                stopServer(server1)
            }

            // Phase 2: start a fresh server against the same Postgres + data dir
            val server2 = startServer(pg, dataDir)
            try {
                newS3Client(server2.endpoint).use { s3 ->
                    // The object must be readable.
                    val got = s3.getObjectAsBytes { it.bucket(bucket).key("k") }
                    assertArrayEquals(payload, got.asByteArray())
                }
            } finally {
                stopServer(server2)
            }
        } finally {
            pg.stop()
            dataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `multipart part survives process kill after upload part`() {
        val pg = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("s3crash2")
            .withUsername("test")
            .withPassword("test")
        pg.start()
        val dataDir = Files.createTempDirectory("s3-crash-part")

        try {
            val server1 = startServer(pg, dataDir)
            val bucket = "crash-part-" + UUID.randomUUID().toString().take(8)
            val partSize = 6 * 1024 * 1024
            val payload = ByteArray(partSize) { 0x42 }

            try {
                newS3Client(server1.endpoint).use { s3 ->
                    s3.createBucket { it.bucket(bucket) }
                    val init = s3.createMultipartUpload { it.bucket(bucket).key("k") }
                    s3.uploadPart(
                        software.amazon.awssdk.services.s3.model.UploadPartRequest.builder()
                            .bucket(bucket).key("k").uploadId(init.uploadId())
                            .partNumber(1).contentLength(payload.size.toLong()).build(),
                        RequestBody.fromBytes(payload)
                    )
                    // Don't complete — just kill the server.
                }
            } finally {
                stopServer(server1)
            }

            // Phase 2: restart, the part should still be listable
            val server2 = startServer(pg, dataDir)
            try {
                newS3Client(server2.endpoint).use { s3 ->
                    // List all in-progress multipart uploads — the one we
                    // started should still be there.
                    val resp = s3.listMultipartUploads { it.bucket(bucket) }
                    assertEquals(1, resp.uploads().size,
                        "multipart upload should survive process kill")
                    val uploadId = resp.uploads()[0].uploadId()
                    val parts = s3.listParts { it.bucket(bucket).key("k").uploadId(uploadId) }
                    assertEquals(1, parts.parts().size, "part should survive process kill")
                    assertEquals(partSize.toLong(), parts.parts()[0].size())
                }
            } finally {
                stopServer(server2)
            }
        } finally {
            pg.stop()
            dataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `delete survives process kill`() {
        val pg = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("s3crash3")
            .withUsername("test")
            .withPassword("test")
        pg.start()
        val dataDir = Files.createTempDirectory("s3-crash-del")

        try {
            val server1 = startServer(pg, dataDir)
            val bucket = "crash-del-" + UUID.randomUUID().toString().take(8)
            val payload = "delete-me".toByteArray()

            try {
                newS3Client(server1.endpoint).use { s3 ->
                    s3.createBucket { it.bucket(bucket) }
                    s3.putObject(
                        PutObjectRequest.builder().bucket(bucket).key("k").build(),
                        RequestBody.fromBytes(payload)
                    )
                    s3.deleteObject { it.bucket(bucket).key("k") }
                }
            } finally {
                stopServer(server1)
            }

            // Phase 2: restart, object should be gone
            val server2 = startServer(pg, dataDir)
            try {
                newS3Client(server2.endpoint).use { s3 ->
                    org.junit.jupiter.api.assertThrows<software.amazon.awssdk.services.s3.model.NoSuchKeyException> {
                        s3.headObject { it.bucket(bucket).key("k") }
                    }
                }
            } finally {
                stopServer(server2)
            }
        } finally {
            pg.stop()
            dataDir.toFile().deleteRecursively()
        }
    }
}
