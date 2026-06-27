package app.silofs.compat

import app.silofs.auth.StaticCredentialProvider
import app.silofs.blob.FsBlobStore
import app.silofs.metadata.Database
import app.silofs.metadata.JdbcMetadataRepository
import app.silofs.server.RecoveryConfig
import app.silofs.server.ServerConfig
import app.silofs.server.s3Module
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.AbortMultipartUploadRequest
import aws.sdk.kotlin.services.s3.model.CompleteMultipartUploadRequest
import aws.sdk.kotlin.services.s3.model.CompletedMultipartUpload
import aws.sdk.kotlin.services.s3.model.CompletedPart
import aws.sdk.kotlin.services.s3.model.CopyObjectRequest
import aws.sdk.kotlin.services.s3.model.CreateBucketRequest
import aws.sdk.kotlin.services.s3.model.CreateMultipartUploadRequest
import aws.sdk.kotlin.services.s3.model.DeleteObjectRequest
import aws.sdk.kotlin.services.s3.model.GetBucketLocationRequest
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.HeadBucketRequest
import aws.sdk.kotlin.services.s3.model.HeadObjectRequest
import aws.sdk.kotlin.services.s3.model.ListBucketsRequest
import aws.sdk.kotlin.services.s3.model.ListMultipartUploadsRequest
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Request
import aws.sdk.kotlin.services.s3.model.ListPartsRequest
import aws.sdk.kotlin.services.s3.model.NoSuchKey
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.model.UploadPartRequest
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.sdk.kotlin.services.s3.presigners.presignPutObject
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.toByteArray
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import aws.smithy.kotlin.runtime.net.url.Url
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
import org.testcontainers.containers.output.OutputFrame
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import org.testcontainers.images.builder.ImageFromDockerfile
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExtendedCompatibilityTest {
    private val log = LoggerFactory.getLogger(ExtendedCompatibilityTest::class.java)

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
        dataDir = Files.createTempDirectory("silofs-extended-compat-data")
        database = Database.fromUrl(pg.jdbcUrl, pg.username, pg.password)
        val repo = JdbcMetadataRepository()
        database.withConnection { conn ->
            repo.upsertAccessKey(conn, ACCESS_KEY, SECRET_KEY, "extended compatibility test key")
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
        log.info("Extended compatibility server started at {}", endpoint)
    }

    @AfterAll
    fun stopServer() {
        runCatching { server.stop(1_000, 3_000) }
        runCatching { database.close() }
        runCatching { pg.stop() }
        runCatching { dataDir.toFile().deleteRecursively() }
    }

    @Test
    fun `minio mc path-style compatibility contract`() {
        runClient("mc", imageFor("mc"))
    }

    @Test
    fun `rclone path-style compatibility contract`() {
        runClient("rclone", imageFor("rclone"))
    }

    @Test
    fun `s5cmd path-style compatibility contract`() {
        runClient("s5cmd", imageFor("s5cmd"))
    }

    @Test
    fun `aws sdk kotlin path-style compatibility contract`() =
        runBlocking {
            newKotlinClient().use { s3 ->
                val bucket = newBucket("kotlin")
                s3.createBucket(CreateBucketRequest { this.bucket = bucket })
                s3.headBucket(HeadBucketRequest { this.bucket = bucket })
                val buckets = s3.listBuckets(ListBucketsRequest {})
                assertTrue(buckets.buckets?.any { it.name == bucket } == true)
                s3.getBucketLocation(GetBucketLocationRequest { this.bucket = bucket })

                val payload = "hello from kotlin".toByteArray()
                val checksum = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(payload))
                s3.putObject(
                    PutObjectRequest {
                        this.bucket = bucket
                        key = "objects/hello.txt"
                        contentType = "text/plain"
                        metadata = mapOf("client" to "kotlin")
                        checksumSha256 = checksum
                        body = ByteStream.fromBytes(payload)
                    },
                )
                val head =
                    s3.headObject(
                        HeadObjectRequest {
                            this.bucket = bucket
                            key = "objects/hello.txt"
                        },
                    )
                assertEquals(payload.size.toLong(), head.contentLength)
                assertEquals("text/plain", head.contentType)
                assertEquals("kotlin", head.metadata?.get("client"))
                val got =
                    s3.getObject(
                        GetObjectRequest {
                            this.bucket = bucket
                            key = "objects/hello.txt"
                        },
                    ) { response ->
                        response.body?.toByteArray() ?: ByteArray(0)
                    }
                assertArrayEquals(payload, got)

                val missing =
                    assertThrows<NoSuchKey> {
                        runBlocking {
                            s3.headObject(
                                HeadObjectRequest {
                                    this.bucket = bucket
                                    key = "missing.txt"
                                },
                            )
                        }
                    }
                assertEquals("NoSuchKey", missing.sdkErrorMetadata.errorCode)

                val rangeBody = ByteArray(256) { it.toByte() }
                s3.putObject(
                    PutObjectRequest {
                        this.bucket = bucket
                        key = "objects/range.bin"
                        body = ByteStream.fromBytes(rangeBody)
                    },
                )
                val range =
                    s3.getObject(
                        GetObjectRequest {
                            this.bucket = bucket
                            key = "objects/range.bin"
                            range = "bytes=10-19"
                        },
                    ) { response ->
                        response.body?.toByteArray() ?: ByteArray(0)
                    }
                assertArrayEquals(ByteArray(10) { (10 + it).toByte() }, range)

                listOf("prefix/a.txt", "prefix/b.txt", "prefix/nested/c.txt", "weird space 日本.txt").forEachIndexed { index, key ->
                    s3.putObject(
                        PutObjectRequest {
                            this.bucket = bucket
                            this.key = key
                            body = ByteStream.fromString("v$index")
                        },
                    )
                }
                val listed =
                    s3.listObjectsV2(
                        ListObjectsV2Request {
                            this.bucket = bucket
                            prefix = "prefix/"
                            delimiter = "/"
                        },
                    )
                assertTrue(listed.contents?.mapNotNull { it.key }?.containsAll(listOf("prefix/a.txt", "prefix/b.txt")) == true)
                assertTrue(listed.commonPrefixes?.any { it.prefix == "prefix/nested/" } == true)

                s3.copyObject(
                    CopyObjectRequest {
                        this.bucket = bucket
                        key = "objects/copied.txt"
                        copySource = "$bucket/objects/hello.txt"
                    },
                )
                val copied =
                    s3.getObject(
                        GetObjectRequest {
                            this.bucket = bucket
                            key = "objects/copied.txt"
                        },
                    ) { response ->
                        response.body?.toByteArray() ?: ByteArray(0)
                    }
                assertArrayEquals(payload, copied)

                val partPayload = ByteArray(PART_SIZE) { 0x41 }
                val init =
                    s3.createMultipartUpload(
                        CreateMultipartUploadRequest {
                            this.bucket = bucket
                            key = "multipart.bin"
                        },
                    )
                val part =
                    s3.uploadPart(
                        UploadPartRequest {
                            this.bucket = bucket
                            key = "multipart.bin"
                            uploadId = init.uploadId
                            partNumber = 1
                            contentLength = partPayload.size.toLong()
                            body = ByteStream.fromBytes(partPayload)
                        },
                    )
                assertEquals(
                    1,
                    s3
                        .listParts(
                            ListPartsRequest {
                                this.bucket = bucket
                                key = "multipart.bin"
                                uploadId = init.uploadId
                            },
                        ).parts
                        ?.size,
                )
                assertEquals(1, s3.listMultipartUploads(ListMultipartUploadsRequest { this.bucket = bucket }).uploads?.size)
                s3.completeMultipartUpload(
                    CompleteMultipartUploadRequest {
                        this.bucket = bucket
                        key = "multipart.bin"
                        uploadId = init.uploadId
                        multipartUpload =
                            CompletedMultipartUpload {
                                parts =
                                    listOf(
                                        CompletedPart {
                                            partNumber = 1
                                            eTag = part.eTag
                                        },
                                    )
                            }
                    },
                )
                assertEquals(
                    PART_SIZE.toLong(),
                    s3
                        .headObject(
                            HeadObjectRequest {
                                this.bucket = bucket
                                key = "multipart.bin"
                            },
                        ).contentLength,
                )

                val abort =
                    s3.createMultipartUpload(
                        CreateMultipartUploadRequest {
                            this.bucket = bucket
                            key = "abort.bin"
                        },
                    )
                s3.abortMultipartUpload(
                    AbortMultipartUploadRequest {
                        this.bucket = bucket
                        key = "abort.bin"
                        uploadId = abort.uploadId
                    },
                )

                val getRequest =
                    GetObjectRequest {
                        this.bucket = bucket
                        key = "objects/hello.txt"
                    }
                val presignedGet: HttpRequest = s3.presignGetObject(getRequest, 5.minutes)
                assertArrayEquals(payload, presignedGet.url.toString().toUrlBytes())

                val putRequest =
                    PutObjectRequest {
                        this.bucket = bucket
                        key = "presigned-put.txt"
                    }
                val presignedPut: HttpRequest = s3.presignPutObject(putRequest, 5.minutes)
                (
                    java.net.URI
                        .create(presignedPut.url.toString())
                        .toURL()
                        .openConnection() as HttpURLConnection
                ).apply {
                    requestMethod = "PUT"
                    doOutput = true
                    outputStream.use { it.write("presigned".toByteArray()) }
                    getInputStream().close()
                }
                val presigned =
                    s3.getObject(
                        GetObjectRequest {
                            this.bucket = bucket
                            key = "presigned-put.txt"
                        },
                    ) { response ->
                        response.body?.toByteArray() ?: ByteArray(0)
                    }
                assertArrayEquals("presigned".toByteArray(), presigned)

                s3.deleteObject(
                    DeleteObjectRequest {
                        this.bucket = bucket
                        key = "objects/hello.txt"
                    },
                )
                assertThrows<NoSuchKey> {
                    runBlocking {
                        s3.headObject(
                            HeadObjectRequest {
                                this.bucket = bucket
                                key = "objects/hello.txt"
                            },
                        )
                    }
                }
            }
        }

    private fun runClient(
        name: String,
        image: ImageFromDockerfile,
        mode: String = "contract",
    ) {
        val logs = StringBuilder()
        val container =
            CompatibilityContainer(image)
                .withEnv("S3_ENDPOINT", containerEndpoint)
                .withEnv("AWS_ACCESS_KEY_ID", ACCESS_KEY)
                .withEnv("AWS_SECRET_ACCESS_KEY", SECRET_KEY)
                .withEnv("AWS_DEFAULT_REGION", REGION)
                .withEnv("COMPAT_MODE", mode)
                .withEnv("COMPAT_CLIENT", name)
                .withLogConsumer { frame: OutputFrame -> logs.append(frame.utf8String) }
                .withStartupCheckStrategy(OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(10)))
        container.use {
            try {
                container.start()
            } catch (t: Throwable) {
                throw AssertionError("client=$name mode=$mode failed to start or exited non-zero; logs:\n$logs", t)
            }
            val exitCode = container.currentContainerInfo.state.exitCodeLong
            assertEquals(0L, exitCode, "client=$name mode=$mode logs:\n$logs")
            assertTrue(logs.contains("COMPAT PASS"), "client=$name did not report success:\n$logs")
            assertTrue(logs.contains("DETECTION"), "client=$name did not record detection evidence:\n$logs")
        }
    }

    private fun imageFor(name: String): ImageFromDockerfile =
        ImageFromDockerfile("silofs-m10-$name:local", false)
            .withFileFromPath(".", Path.of("src/test/docker/$name").toAbsolutePath())

    private fun newKotlinClient(): S3Client =
        S3Client {
            region = REGION
            endpointUrl = Url.parse(endpoint)
            forcePathStyle = true
            credentialsProvider = StaticCredentialsProvider(Credentials(ACCESS_KEY, SECRET_KEY))
        }

    private fun newBucket(client: String): String = "m10-$client-${UUID.randomUUID().toString().take(8).lowercase()}"

    private fun String.toUrlBytes(): ByteArray =
        java.net.URI
            .create(this)
            .toURL()
            .openStream()
            .readBytes()

    companion object {
        private const val ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE"
        private const val SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        private const val REGION = "us-east-1"
        private const val PART_SIZE = 6 * 1024 * 1024
    }
}
