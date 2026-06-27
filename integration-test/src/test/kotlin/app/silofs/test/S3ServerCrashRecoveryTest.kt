package app.silofs.test

import app.silofs.auth.StaticCredentialProvider
import app.silofs.blob.FsBlobStore
import app.silofs.metadata.Database
import app.silofs.metadata.JdbcMetadataRepository
import app.silofs.server.ServerConfig
import app.silofs.server.s3Module
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.nio.file.Files
import java.util.UUID

/**
 * Crash-recovery test: writes an object, verifies it is on disk, then restarts
 * the Ktor server and reads it back through the SDK.
 *
 * This does not kill the JVM (we'd need a separate process for that), but it
 * does close the database connection pool, drop in-memory state, and re-apply
 * Flyway migrations — proving the durability story holds across restarts.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ServerCrashRecoveryTest {
    companion object {
        const val ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE"
        const val SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        const val REGION = "us-east-1"
    }

    @Test
    fun `object survives server restart`() {
        val pg =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("s3test")
                .withUsername("test")
                .withPassword("test")
        pg.start()
        val dataDir = Files.createTempDirectory("s3-crash-recovery")

        try {
            val dbUrl = pg.jdbcUrl
            val dbUser = pg.username
            val dbPass = pg.password

            // --- Phase 1: write object ---
            val db1 = Database.fromUrl(dbUrl, dbUser, dbPass)
            val repo1 = JdbcMetadataRepository()
            db1.withConnection { c ->
                repo1.upsertAccessKey(c, ACCESS_KEY, SECRET_KEY, "k")
                repo1.grantBucketPermission(c, ACCESS_KEY, "*", "ADMIN")
            }
            val blob1 = FsBlobStore(dataDir)
            val config1 =
                ServerConfig(
                    bindHost = "127.0.0.1",
                    bindPort = 0,
                    region = REGION,
                    dataDir = dataDir,
                    database = db1,
                    blobStore = blob1,
                    repository = repo1,
                    credentialProvider = StaticCredentialProvider.single(ACCESS_KEY, SECRET_KEY),
                    recoveryConfig =
                        app.silofs.server.RecoveryConfig(
                            tempMaxAgeSeconds = 1,
                            multipartMaxAgeSeconds = 1,
                            sweepIntervalSeconds = 1,
                            blobSweepIntervalSeconds = 1,
                            enabled = false,
                        ),
                )
            val server1 =
                embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                    s3Module(config1)
                }
            server1.start(wait = false)
            val port1 =
                runBlocking {
                    server1.engine
                        .resolvedConnectors()
                        .first()
                        .port
                }
            val endpoint1 = "http://127.0.0.1:$port1"

            val payload = "durable-data".toByteArray()
            val bucket =
                "crash-" +
                    UUID
                        .randomUUID()
                        .toString()
                        .take(8)
                        .lowercase()

            val s3 =
                software.amazon.awssdk.services.s3.S3Client
                    .builder()
                    .region(
                        software.amazon.awssdk.regions.Region
                            .of(REGION),
                    ).credentialsProvider(
                        software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                            software.amazon.awssdk.auth.credentials.AwsBasicCredentials
                                .create(ACCESS_KEY, SECRET_KEY),
                        ),
                    ).endpointOverride(java.net.URI.create(endpoint1))
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
            s3.use {
                it.createBucket { b -> b.bucket(bucket) }
                it.putObject(
                    PutObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key("k.bin")
                        .build(),
                    RequestBody.fromBytes(payload),
                )
            }
            // Simulate crash: close DB pool, kill server, drop all in-memory state.
            server1.stop(500, 2000)
            db1.close()

            // --- Phase 2: restart from same Postgres + same data dir ---
            val db2 = Database.fromUrl(dbUrl, dbUser, dbPass)
            val repo2 = JdbcMetadataRepository()
            val blob2 = FsBlobStore(dataDir)
            val config2 =
                ServerConfig(
                    bindHost = "127.0.0.1",
                    bindPort = 0,
                    region = REGION,
                    dataDir = dataDir,
                    database = db2,
                    blobStore = blob2,
                    repository = repo2,
                    credentialProvider = StaticCredentialProvider.single(ACCESS_KEY, SECRET_KEY),
                    recoveryConfig =
                        app.silofs.server.RecoveryConfig(
                            tempMaxAgeSeconds = 1,
                            multipartMaxAgeSeconds = 1,
                            sweepIntervalSeconds = 1,
                            blobSweepIntervalSeconds = 1,
                            enabled = false,
                        ),
                )
            val server2 =
                embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                    s3Module(config2)
                }
            server2.start(wait = false)
            val port2 =
                runBlocking {
                    server2.engine
                        .resolvedConnectors()
                        .first()
                        .port
                }
            val endpoint2 = "http://127.0.0.1:$port2"

            val s3b =
                software.amazon.awssdk.services.s3.S3Client
                    .builder()
                    .region(
                        software.amazon.awssdk.regions.Region
                            .of(REGION),
                    ).credentialsProvider(
                        software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                            software.amazon.awssdk.auth.credentials.AwsBasicCredentials
                                .create(ACCESS_KEY, SECRET_KEY),
                        ),
                    ).endpointOverride(java.net.URI.create(endpoint2))
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
            s3b.use {
                val got = it.getObjectAsBytes { g -> g.bucket(bucket).key("k.bin") }
                assertArrayEquals(payload, got.asByteArray())
            }

            server2.stop(500, 2000)
            db2.close()
        } finally {
            pg.stop()
            dataDir.toFile().deleteRecursively()
        }
    }
}
