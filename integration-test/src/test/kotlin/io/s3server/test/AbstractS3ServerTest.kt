package app.silofs.test

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import app.silofs.auth.StaticCredentialProvider
import app.silofs.blob.FsBlobStore
import app.silofs.blob.RecoveryJob
import app.silofs.metadata.Database
import app.silofs.metadata.JdbcMetadataRepository
import app.silofs.server.ServerConfig
import app.silofs.server.s3Module
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.testcontainers.containers.PostgreSQLContainer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.nio.file.Files
import java.nio.file.Path

/**
 * Boots a real Ktor server on a random port, against a real Postgres via
 * testcontainers, and exposes a pre-configured AWS SDK v2 [S3Client] pointing
 * at it. Each test class reuses the same server instance via
 * [@TestInstance(PER_CLASS)][TestInstance.Lifecycle.PER_CLASS].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractS3ServerTest {

    private val log = LoggerFactory.getLogger(AbstractS3ServerTest::class.java)

    companion object {
        const val ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE"
        const val SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        const val REGION = "us-east-1"
    }

    @JvmField
    protected val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("s3test")
        .withUsername("test")
        .withPassword("test")

    protected lateinit var dataDir: Path
    protected lateinit var database: Database
    protected lateinit var server: io.ktor.server.engine.EmbeddedServer<*, *>
    protected lateinit var endpoint: String
    protected var recoveryJob: RecoveryJob? = null

    @BeforeAll
    fun startServer() {
        pg.start()
        dataDir = Files.createTempDirectory("s3server-test-data")
        val db = Database.fromUrl(pg.jdbcUrl, pg.username, pg.password)
        database = db
        val repo = JdbcMetadataRepository()
        db.withConnection { c -> repo.upsertAccessKey(c, ACCESS_KEY, SECRET_KEY, "test key") }
        val blobStore = FsBlobStore(dataDir)
        val credentialProvider = StaticCredentialProvider.single(ACCESS_KEY, SECRET_KEY)
        val config = ServerConfig(
            bindHost = "127.0.0.1",
            bindPort = 0,
            region = REGION,
            dataDir = dataDir,
            database = db,
            blobStore = blobStore,
            repository = repo,
            credentialProvider = credentialProvider,
            recoveryConfig = app.silofs.server.RecoveryConfig(
                tempMaxAgeSeconds = 1,
                multipartMaxAgeSeconds = 1,
                sweepIntervalSeconds = 1,
                blobSweepIntervalSeconds = 1,
                enabled = false
            )
        )

        server = embeddedServer(
            factory = Netty,
            host = "127.0.0.1",
            port = 0
        ) {
            s3Module(config)
        }
        server.start(wait = false)
        val actualPort = runBlocking {
            server.engine.resolvedConnectors().first().port
        }
        endpoint = "http://127.0.0.1:$actualPort"
        log.info("Test server started at {}", endpoint)
    }

    @AfterAll
    fun stopServer() {
        runCatching { server.stop(1000, 3000) }
        runCatching { recoveryJob?.close() }
        runCatching { database.close() }
        runCatching { pg.stop() }
        runCatching { dataDir.toFile().deleteRecursively() }
    }

    protected fun newS3Client(): S3Client {
        val httpClient = ApacheHttpClient.builder()
            .maxConnections(50)
            .connectionTimeout(java.time.Duration.ofSeconds(5))
            .socketTimeout(java.time.Duration.ofSeconds(30))
            .build()
        return S3Client.builder()
            .region(Region.of(REGION))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
            .endpointOverride(java.net.URI.create(endpoint))
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .chunkedEncodingEnabled(false)
                    .build()
            )
            .httpClient(httpClient)
            .build()
    }
}
