package app.silofs.compat

import app.silofs.auth.StaticCredentialProvider
import app.silofs.blob.FsBlobStore
import app.silofs.metadata.Database
import app.silofs.metadata.JdbcMetadataRepository
import app.silofs.server.RecoveryConfig
import app.silofs.server.SecurityConfig
import app.silofs.server.ServerConfig
import app.silofs.server.accessKeyRecordForSecret
import app.silofs.server.s3Module
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.testcontainers.Testcontainers
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import org.testcontainers.images.builder.ImageFromDockerfile
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.UploadPartCopyRequest
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class M6CompatibilityTest {
    private val log = LoggerFactory.getLogger(M6CompatibilityTest::class.java)

    private val pg: PostgreSQLContainer<*> =
        PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("s3compat")
            .withUsername("test")
            .withPassword("test")

    private lateinit var dataDir: Path
    private lateinit var database: Database
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var endpoint: String
    private lateinit var containerEndpoint: String

    @BeforeAll
    fun startServer() {
        pg.start()
        dataDir = Files.createTempDirectory("silofs-compat-data")
        database = Database.fromUrl(pg.jdbcUrl, pg.username, pg.password)
        val repo = JdbcMetadataRepository()
        val securityConfig = testSecurityConfig()
        database.withConnection { conn ->
            repo.upsertAccessKeyRecord(conn, accessKeyRecordForSecret(ACCESS_KEY, SECRET_KEY, "compatibility test key", securityConfig))
            repo.grantBucketPermission(conn, ACCESS_KEY, "*", "ADMIN")
        }
        val config =
            ServerConfig(
                bindHost = "127.0.0.1",
                bindPort = 0,
                region = REGION,
                dataDir = dataDir,
                database = database,
                blobStore = FsBlobStore(dataDir),
                repository = repo,
                credentialProvider = StaticCredentialProvider.single(ACCESS_KEY, SECRET_KEY),
                securityConfig = securityConfig,
                recoveryConfig =
                    RecoveryConfig(
                        tempMaxAgeSeconds = 1,
                        multipartMaxAgeSeconds = 1,
                        sweepIntervalSeconds = 1,
                        blobSweepIntervalSeconds = 1,
                        enabled = false,
                    ),
            )

        server =
            embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                s3Module(config)
            }
        server.start(wait = false)
        val port =
            runBlocking {
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port
            }
        endpoint = "http://127.0.0.1:$port"
        Testcontainers.exposeHostPorts(port)
        containerEndpoint = "http://host.testcontainers.internal:$port"
        log.info("M6 compatibility server started at {}", endpoint)
    }

    @AfterAll
    fun stopServer() {
        runCatching { server.stop(1_000, 3_000) }
        runCatching { database.close() }
        runCatching { pg.stop() }
        runCatching { dataDir.toFile().deleteRecursively() }
    }

    @Test
    fun `aws sdk java v2 path-style compatibility contract`() {
        newJavaClient().use { s3 ->
            val bucket = newBucket("java")
            s3.createBucket { it.bucket(bucket) }
            s3.headBucket { it.bucket(bucket) }
            s3.listBuckets()
            s3.getBucketLocation { it.bucket(bucket) }

            val payload = "hello from java".toByteArray()
            val checksum = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(payload))
            s3.putObject(
                PutObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key("objects/hello.txt")
                    .contentType("text/plain")
                    .metadata(mapOf("client" to "java-v2"))
                    .checksumSHA256(checksum)
                    .build(),
                RequestBody.fromBytes(payload),
            )
            val head = s3.headObject { it.bucket(bucket).key("objects/hello.txt") }
            assertEquals(payload.size.toLong(), head.contentLength())
            assertEquals("text/plain", head.contentType())
            assertEquals("java-v2", head.metadata()["client"])
            assertArrayEquals(payload, s3.getObjectAsBytes { it.bucket(bucket).key("objects/hello.txt") }.asByteArray())

            s3.putObject(
                PutObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key("objects/range.bin")
                    .build(),
                RequestBody.fromBytes(ByteArray(256) { it.toByte() }),
            )
            assertArrayEquals(
                ByteArray(10) { (10 + it).toByte() },
                s3.getObjectAsBytes { it.bucket(bucket).key("objects/range.bin").range("bytes=10-19") }.asByteArray(),
            )

            listOf("prefix/a.txt", "prefix/b.txt", "prefix/nested/c.txt", "weird space 日本.txt").forEachIndexed { index, key ->
                s3.putObject(
                    PutObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(key)
                        .build(),
                    RequestBody.fromBytes("v$index".toByteArray()),
                )
            }
            val listed = s3.listObjectsV2 { it.bucket(bucket).prefix("prefix/").delimiter("/") }
            assertTrue(listed.contents().map { it.key() }.containsAll(listOf("prefix/a.txt", "prefix/b.txt")))
            assertTrue(listed.commonPrefixes().any { it.prefix() == "prefix/nested/" })

            s3.copyObject(
                CopyObjectRequest
                    .builder()
                    .sourceBucket(bucket)
                    .sourceKey("objects/hello.txt")
                    .destinationBucket(bucket)
                    .destinationKey("objects/copied.txt")
                    .build(),
            )
            assertArrayEquals(payload, s3.getObjectAsBytes { it.bucket(bucket).key("objects/copied.txt") }.asByteArray())

            val multipartPayload = ByteArray(PART_SIZE) { 0x41 }
            val init = s3.createMultipartUpload { it.bucket(bucket).key("multipart.bin") }
            val part =
                s3.uploadPart(
                    UploadPartRequest
                        .builder()
                        .bucket(bucket)
                        .key("multipart.bin")
                        .uploadId(init.uploadId())
                        .partNumber(1)
                        .contentLength(multipartPayload.size.toLong())
                        .build(),
                    RequestBody.fromBytes(multipartPayload),
                )
            assertEquals(1, s3.listParts { it.bucket(bucket).key("multipart.bin").uploadId(init.uploadId()) }.parts().size)
            assertEquals(1, s3.listMultipartUploads { it.bucket(bucket) }.uploads().size)
            s3.completeMultipartUpload {
                it
                    .bucket(bucket)
                    .key("multipart.bin")
                    .uploadId(init.uploadId())
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
            assertEquals(PART_SIZE.toLong(), s3.headObject { it.bucket(bucket).key("multipart.bin") }.contentLength())

            s3.putObject(
                PutObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key("copy-source.bin")
                    .build(),
                RequestBody.fromBytes(multipartPayload),
            )
            val copyInit = s3.createMultipartUpload { it.bucket(bucket).key("copy-dest.bin") }
            val copyPart =
                s3.uploadPartCopy(
                    UploadPartCopyRequest
                        .builder()
                        .bucket(bucket)
                        .key("copy-dest.bin")
                        .uploadId(copyInit.uploadId())
                        .partNumber(1)
                        .copySource("$bucket/copy-source.bin")
                        .build(),
                )
            s3.abortMultipartUpload { it.bucket(bucket).key("copy-dest.bin").uploadId(copyInit.uploadId()) }
            assertTrue(copyPart.copyPartResult().eTag().isNotBlank())

            val abortInit = s3.createMultipartUpload { it.bucket(bucket).key("abort.bin") }
            s3.abortMultipartUpload { it.bucket(bucket).key("abort.bin").uploadId(abortInit.uploadId()) }

            newJavaPresigner().use { presigner ->
                val getUrl =
                    presigner
                        .presignGetObject(
                            GetObjectPresignRequest
                                .builder()
                                .signatureDuration(Duration.ofMinutes(5))
                                .getObjectRequest { it.bucket(bucket).key("objects/hello.txt") }
                                .build(),
                        ).url()
                assertArrayEquals(payload, getUrl.openStream().readBytes())

                val putUrl =
                    presigner
                        .presignPutObject(
                            PutObjectPresignRequest
                                .builder()
                                .signatureDuration(Duration.ofMinutes(5))
                                .putObjectRequest { it.bucket(bucket).key("presigned-put.txt") }
                                .build(),
                        ).url()
                (putUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"
                    doOutput = true
                    outputStream.use { it.write("presigned".toByteArray()) }
                    getInputStream().close()
                }
                assertArrayEquals(
                    "presigned".toByteArray(),
                    s3.getObjectAsBytes { it.bucket(bucket).key("presigned-put.txt") }.asByteArray(),
                )
            }

            s3.deleteObject { it.bucket(bucket).key("objects/hello.txt") }
            val missing =
                assertThrows<NoSuchKeyException> {
                    s3.headObject { it.bucket(bucket).key("objects/hello.txt") }
                }
            assertEquals(404, missing.statusCode())
        }
    }

    @Test
    fun `aws cli path-style compatibility contract`() {
        runClient("aws-cli", imageFor("awscli"))
    }

    @Test
    fun `boto3 path-style compatibility contract`() {
        runClient("boto3", imageFor("python"))
    }

    @Test
    fun `javascript v3 path-style compatibility contract`() {
        runClient("javascript-v3", imageFor("node"))
    }

    @Test
    fun `go v2 path-style compatibility contract`() {
        runClient("go-v2", imageFor("go"))
    }

    @Test
    fun `virtual-host and streaming sigv4 detection is recorded`() {
        runClient("boto3-detect", imageFor("python"), mode = "detect")
        runClient("javascript-v3-detect", imageFor("node"), mode = "detect")
        runClient("go-v2-detect", imageFor("go"), mode = "detect")
    }

    private fun runClient(
        name: String,
        image: ImageFromDockerfile,
        mode: String = "contract",
    ) {
        val container =
            CompatibilityContainer(image)
                .withEnv("S3_ENDPOINT", containerEndpoint)
                .withEnv("AWS_ACCESS_KEY_ID", ACCESS_KEY)
                .withEnv("AWS_SECRET_ACCESS_KEY", SECRET_KEY)
                .withEnv("AWS_DEFAULT_REGION", REGION)
                .withEnv("COMPAT_MODE", mode)
                .withEnv("COMPAT_CLIENT", name)
                .withStartupCheckStrategy(OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(10)))
        container.use {
            try {
                container.start()
            } catch (t: Throwable) {
                throw AssertionError("client=$name mode=$mode failed to start or exited non-zero; logs:\n${container.logs}", t)
            }
            val exitCode = container.currentContainerInfo.state.exitCodeLong
            assertEquals(0L, exitCode, "client=$name mode=$mode logs:\n${container.logs}")
            assertTrue(container.logs.contains("COMPAT PASS"), "client=$name did not report success:\n${container.logs}")
        }
    }

    private fun imageFor(name: String): ImageFromDockerfile =
        ImageFromDockerfile("silofs-m6-$name:local", false)
            .withFileFromPath(".", Path.of("src/test/docker/$name").toAbsolutePath())

    private fun newJavaClient(): S3Client =
        S3Client
            .builder()
            .region(Region.of(REGION))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
            .endpointOverride(URI.create(endpoint))
            .serviceConfiguration(
                S3Configuration
                    .builder()
                    .pathStyleAccessEnabled(true)
                    .chunkedEncodingEnabled(false)
                    .build(),
            ).httpClient(ApacheHttpClient.builder().build())
            .build()

    private fun newJavaPresigner(): S3Presigner =
        S3Presigner
            .builder()
            .region(Region.of(REGION))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
            .endpointOverride(URI.create(endpoint))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()

    private fun newBucket(client: String): String = "m6-$client-${UUID.randomUUID().toString().take(8).lowercase()}"

    companion object {
        private const val ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE"
        private const val SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        private const val REGION = "us-east-1"
        private const val PART_SIZE = 6 * 1024 * 1024

        fun testSecurityConfig(): SecurityConfig =
            SecurityConfig(
                secretEncryptionKey = ByteArray(32) { 7 },
                requireEncryptedSecrets = true,
                corsAllowedOrigins = emptyList(),
                rateLimitPerAccessKeyRps = 0,
                rateLimitPerAccessKeyBurst = 64,
            )
    }
}
