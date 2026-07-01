package app.silofs.test

import app.silofs.blob.BlobConsistencyChecker
import app.silofs.blob.FsBlobStore
import app.silofs.metadata.Database
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Failpoint-based mid-commit crash tests (gap #8).
 *
 * These tests use an environment variable `S3_FAILPOINT` to inject a
 * `System.exit(1)` at a specific point in the PutObject / multipart
 * commit path. The server process is killed mid-commit, then we restart
 * and verify the object state is consistent with the durability contract.
 *
 * Supported failpoints:
 *   - `after-tmp-write`  : after temp file is written, before fsync
 *   - `after-fsync`      : after fsync, before rename
 *   - `after-rename`     : after atomic rename, before DB commit
 *   - `before-response`  : after DB commit, before HTTP response is sent
 *
 * The failpoint is read from the env var on every request, so we can set
 * it just before the triggering request and unset it after.
 *
 * Note: The failpoint hook lives in the server module (gated by the env var
 * so it's a no-op in production). These tests set the env var before
 * starting the child process.
 *
 * Tagged with "crash" so they can be excluded from CI fast paths.
 */
@Tag("crash")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ServerFailpointCrashTest {
    companion object {
        const val ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE"
        const val SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        const val SECRET_ENCRYPTION_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        const val REGION = "us-east-1"
    }

    private data class RunningServer(
        val process: Process,
        val endpoint: String,
        val port: Int,
        val dataDir: Path,
        val output: StringBuilder,
        val outputThread: Thread,
    )

    private fun startServer(
        pg: PostgreSQLContainer<*>,
        dataDir: Path,
        failpoint: String? = null,
    ): RunningServer {
        val port = freePort()
        val endpoint = "http://127.0.0.1:$port"

        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")
        val jvmArgs =
            listOf(
                "$javaHome/bin/java",
                "-Xmx512m",
                "-cp",
                classpath,
                "app.silofs.server.MainKt",
            )
        val env =
            mutableMapOf(
                "S3_BIND_HOST" to "127.0.0.1",
                "S3_BIND_PORT" to port.toString(),
                "S3_REGION" to REGION,
                "S3_DATA_DIR" to dataDir.toString(),
                "S3_DB_URL" to pg.jdbcUrl,
                "S3_DB_USER" to pg.username,
                "S3_DB_PASSWORD" to pg.password,
                "S3_ACCESS_KEY_ID" to ACCESS_KEY,
                "S3_SECRET_ACCESS_KEY" to SECRET_KEY,
                "S3_ACCESS_KEY_SECRET_ENCRYPTION_KEY" to SECRET_ENCRYPTION_KEY,
                "S3_RECOVERY_ENABLED" to "false",
            )
        if (failpoint != null) {
            env["S3_FAILPOINT"] = failpoint
        }

        val pb = ProcessBuilder(jvmArgs)
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        val process = pb.start()
        val output = StringBuilder()
        val outputThread = drainProcessOutput(process, output)

        // Wait for the server to be ready
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
            stopProcess(process, outputThread)
            error("Server failed to start within 30s. Output:\n${outputSnapshot(output)}")
        }

        return RunningServer(process, endpoint, port, dataDir, output, outputThread)
    }

    private fun freePort(): Int =
        ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            socket.localPort
        }

    private fun stopServer(server: RunningServer) {
        stopProcess(server.process, server.outputThread)
    }

    private fun stopProcess(
        process: Process,
        outputThread: Thread,
    ) {
        if (process.isAlive) {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
        runCatching { process.inputStream.close() }
        outputThread.join(1_000)
    }

    private fun drainProcessOutput(
        process: Process,
        output: StringBuilder,
    ): Thread =
        Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(output) {
                        if (output.length < 32_000) {
                            output.appendLine(line)
                        }
                    }
                }
            }
        }.also {
            it.isDaemon = true
            it.name = "silofs-failpoint-crash-output-drain"
            it.start()
        }

    private fun outputSnapshot(output: StringBuilder): String =
        synchronized(output) {
            output.toString()
        }

    private fun assertNoMissingBlobs(
        pg: PostgreSQLContainer<*>,
        dataDir: Path,
    ) {
        Database.fromUrl(pg.jdbcUrl, pg.username, pg.password).use { db ->
            val report = BlobConsistencyChecker(FsBlobStore(dataDir), db).check()
            assertEquals(0, report.missingBlobs.size, "missing blob references: ${report.missingBlobs}")
        }
    }

    private fun newS3Client(endpoint: String): S3Client =
        S3Client
            .builder()
            .region(Region.of(REGION))
            .credentialsProvider(
                software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                    software.amazon.awssdk.auth.credentials.AwsBasicCredentials
                        .create(ACCESS_KEY, SECRET_KEY),
                ),
            ).endpointOverride(URI.create(endpoint))
            .requestChecksumCalculation(software.amazon.awssdk.core.checksums.RequestChecksumCalculation.WHEN_REQUIRED)
            .serviceConfiguration(
                software.amazon.awssdk.services.s3.S3Configuration
                    .builder()
                    .pathStyleAccessEnabled(true)
                    .chunkedEncodingEnabled(false)
                    .build(),
            ).httpClientBuilder(
                software.amazon.awssdk.http.apache.ApacheHttpClient
                    .builder(),
            ).build()

    /**
     * Test: crash after the DB commit but before the HTTP response is sent.
     *
     * Expected behaviour: the object IS persisted (DB commit happened).
     * The client gets a connection error, but on retry the object is there.
     */
    @Test
    fun `crash after db commit before response`() {
        val pg =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("s3fp1")
                .withUsername("test")
                .withPassword("test")
        pg.start()
        val dataDir = Files.createTempDirectory("s3-fp-commit")

        try {
            // Phase 1: start server with failpoint, write an object.
            // The server will crash after the DB commit.
            val server1 = startServer(pg, dataDir, failpoint = "before-response")
            val bucket = "fp-commit-" + UUID.randomUUID().toString().take(8)
            val payload = "crash-after-commit".toByteArray()

            try {
                newS3Client(server1.endpoint).use { s3 ->
                    s3.createBucket { it.bucket(bucket) }
                    // This PUT will crash the server after committing.
                    try {
                        s3.putObject(
                            PutObjectRequest
                                .builder()
                                .bucket(bucket)
                                .key("k")
                                .build(),
                            RequestBody.fromBytes(payload),
                        )
                    } catch (e: Exception) {
                        // Expected — the server crashed before sending the response.
                    }
                }
            } finally {
                // Wait for the process to die.
                server1.process.waitFor(10, TimeUnit.SECONDS)
            }

            // Phase 2: restart without failpoint, verify the object is there.
            val server2 = startServer(pg, dataDir, failpoint = null)
            try {
                assertNoMissingBlobs(pg, dataDir)
                newS3Client(server2.endpoint).use { s3 ->
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

    /**
     * Test: crash after the temp file is written but before fsync.
     *
     * Expected behaviour: the object is NOT persisted (DB commit didn't happen).
     * The temp file is left on disk and the recovery sweep will clean it up.
     */
    @Test
    fun `crash after tmp write before fsync`() {
        val pg =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("s3fp2")
                .withUsername("test")
                .withPassword("test")
        pg.start()
        val dataDir = Files.createTempDirectory("s3-fp-tmp")

        try {
            val server1 = startServer(pg, dataDir, failpoint = "after-tmp-write")
            val bucket = "fp-tmp-" + UUID.randomUUID().toString().take(8)

            try {
                newS3Client(server1.endpoint).use { s3 ->
                    s3.createBucket { it.bucket(bucket) }
                    try {
                        s3.putObject(
                            PutObjectRequest
                                .builder()
                                .bucket(bucket)
                                .key("k")
                                .build(),
                            RequestBody.fromBytes("crash-before-fsync".toByteArray()),
                        )
                    } catch (e: Exception) {
                        // Expected — server crashed.
                    }
                }
            } finally {
                server1.process.waitFor(10, TimeUnit.SECONDS)
            }

            // Phase 2: restart, the object should NOT exist.
            val server2 = startServer(pg, dataDir, failpoint = null)
            try {
                assertNoMissingBlobs(pg, dataDir)
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

    /**
     * Test: crash after fsync but before rename.
     *
     * Expected behaviour: the object is NOT persisted. The temp file is on
     * disk (fsync'd) but never renamed to its content-addressed path.
     */
    @Test
    fun `crash after fsync before rename`() {
        val pg =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("s3fp3")
                .withUsername("test")
                .withPassword("test")
        pg.start()
        val dataDir = Files.createTempDirectory("s3-fp-fsync")

        try {
            val server1 = startServer(pg, dataDir, failpoint = "after-fsync")
            val bucket = "fp-fsync-" + UUID.randomUUID().toString().take(8)

            try {
                newS3Client(server1.endpoint).use { s3 ->
                    s3.createBucket { it.bucket(bucket) }
                    try {
                        s3.putObject(
                            PutObjectRequest
                                .builder()
                                .bucket(bucket)
                                .key("k")
                                .build(),
                            RequestBody.fromBytes("crash-after-fsync".toByteArray()),
                        )
                    } catch (e: Exception) {
                        // Expected
                    }
                }
            } finally {
                server1.process.waitFor(10, TimeUnit.SECONDS)
            }

            val server2 = startServer(pg, dataDir, failpoint = null)
            try {
                assertNoMissingBlobs(pg, dataDir)
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

    /**
     * Test: crash after rename but before DB commit.
     *
     * Expected behaviour: the object is NOT persisted (DB commit didn't happen).
     * The blob IS on disk under its content-addressed path, but no metadata
     * row references it. The recovery sweep will GC it.
     */
    @Test
    fun `crash after rename before db commit`() {
        val pg =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("s3fp4")
                .withUsername("test")
                .withPassword("test")
        pg.start()
        val dataDir = Files.createTempDirectory("s3-fp-rename")

        try {
            val server1 = startServer(pg, dataDir, failpoint = "after-rename")
            val bucket = "fp-rename-" + UUID.randomUUID().toString().take(8)

            try {
                newS3Client(server1.endpoint).use { s3 ->
                    s3.createBucket { it.bucket(bucket) }
                    try {
                        s3.putObject(
                            PutObjectRequest
                                .builder()
                                .bucket(bucket)
                                .key("k")
                                .build(),
                            RequestBody.fromBytes("crash-after-rename".toByteArray()),
                        )
                    } catch (e: Exception) {
                        // Expected
                    }
                }
            } finally {
                server1.process.waitFor(10, TimeUnit.SECONDS)
            }

            val server2 = startServer(pg, dataDir, failpoint = null)
            try {
                assertNoMissingBlobs(pg, dataDir)
                newS3Client(server2.endpoint).use { s3 ->
                    // Object should not exist (no metadata row).
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

    @Test
    fun `multipart completion crash windows do not create missing blob references`() {
        val failpoints =
            listOf(
                "mpu-before-final-publish",
                "mpu-after-final-publish",
                "mpu-after-object-put",
                "mpu-after-completed-state",
            )

        for (failpoint in failpoints) {
            val pg =
                PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("s3fpmpu")
                    .withUsername("test")
                    .withPassword("test")
            pg.start()
            val dataDir = Files.createTempDirectory("s3-fp-mpu")

            try {
                val server1 = startServer(pg, dataDir, failpoint = failpoint)
                val bucket = "fp-mpu-" + UUID.randomUUID().toString().take(8)
                val payload = ByteArray(6 * 1024 * 1024) { 0x5a }

                try {
                    newS3Client(server1.endpoint).use { s3 ->
                        s3.createBucket { it.bucket(bucket) }
                        val upload = s3.createMultipartUpload { it.bucket(bucket).key("k") }
                        val part =
                            s3.uploadPart(
                                {
                                    it
                                        .bucket(
                                            bucket,
                                        ).key("k")
                                        .uploadId(upload.uploadId())
                                        .partNumber(1)
                                        .contentLength(payload.size.toLong())
                                },
                                RequestBody.fromBytes(payload),
                            )
                        try {
                            s3.completeMultipartUpload {
                                it
                                    .bucket(bucket)
                                    .key("k")
                                    .uploadId(upload.uploadId())
                                    .multipartUpload(
                                        CompletedMultipartUpload
                                            .builder()
                                            .parts(
                                                CompletedPart
                                                    .builder()
                                                    .partNumber(1)
                                                    .eTag(part.eTag())
                                                    .build(),
                                            ).build(),
                                    )
                            }
                        } catch (e: Exception) {
                            // Expected: the server halts at the requested failpoint.
                        }
                    }
                } finally {
                    server1.process.waitFor(10, TimeUnit.SECONDS)
                }

                val server2 = startServer(pg, dataDir, failpoint = null)
                try {
                    assertNoMissingBlobs(pg, dataDir)
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
}
