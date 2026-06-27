package app.silofs.server

import app.silofs.auth.StaticCredentialProvider
import app.silofs.blob.FsBlobStore
import app.silofs.common.ObjectMetadata
import app.silofs.metadata.Database
import app.silofs.metadata.JdbcMetadataRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.Instant

class S3ServerAdminTest {
    private val pg =
        PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("admintest")
            .withUsername("test")
            .withPassword("test")

    private val closeables = mutableListOf<AutoCloseable>()

    @AfterEach
    fun cleanup() {
        closeables.reversed().forEach { runCatching { it.close() } }
        runCatching { pg.stop() }
    }

    @Test
    fun `admin inspect storage verify repair and gc dry-run commands succeed`() {
        val config = newConfig()
        seedObject(config)
        val manifest = Files.createTempFile("silofs-manifest", ".json")
        Files.writeString(manifest, """{"format":"silofs-offline-backup-v1"}""")

        assertEquals(0, runAdminCommand(arrayOf("admin", "inspect", "buckets"), config.copyForCommand()))
        assertEquals(0, runAdminCommand(arrayOf("admin", "inspect", "objects", "--bucket", "b"), config.copyForCommand()))
        assertEquals(0, runAdminCommand(arrayOf("admin", "inspect", "multipart"), config.copyForCommand()))
        assertEquals(0, runAdminCommand(arrayOf("admin", "storage", "usage"), config.copyForCommand()))
        assertEquals(0, runAdminCommand(arrayOf("admin", "backup", "verify", "--manifest", manifest.toString()), config.copyForCommand()))
        assertEquals(0, runAdminCommand(arrayOf("admin", "repair", "--dry-run"), config.copyForCommand()))
        assertEquals(0, runAdminCommand(arrayOf("admin", "gc", "--dry-run"), config.copyForCommand()))
        assertEquals(0, runAdminCommand(arrayOf("admin", "migrate"), config.copyForCommand()))
    }

    @Test
    fun `admin access-key lifecycle commands succeed`() {
        val config = newConfig()
        assertEquals(
            0,
            runAdminCommand(
                arrayOf("admin", "access-key", "create", "--access-key-id", "AKIAADMINTEST0001", "--description", "admin"),
                config.copyForCommand(),
            ),
        )
        assertEquals(0, runAdminCommand(arrayOf("admin", "access-key", "list"), config.copyForCommand()))
        assertEquals(0, runAdminCommand(arrayOf("admin", "access-key", "disable", "AKIAADMINTEST0001"), config.copyForCommand()))
        assertEquals(0, runAdminCommand(arrayOf("admin", "access-key", "enable", "AKIAADMINTEST0001"), config.copyForCommand()))
        assertEquals(0, runAdminCommand(arrayOf("admin", "access-key", "rotate", "AKIAADMINTEST0001"), config.copyForCommand()))
        assertEquals(0, runAdminCommand(arrayOf("admin", "access-key", "delete", "AKIAADMINTEST0001"), config.copyForCommand()))
    }

    @Test
    fun `admin grant commands succeed`() {
        val config = newConfig()
        config.database.withConnection { conn ->
            config.repository.upsertAccessKey(conn, "AKIAGRANTTEST0001", "secret", "grant test")
        }

        assertEquals(
            0,
            runAdminCommand(
                arrayOf(
                    "admin",
                    "grant",
                    "add",
                    "--access-key-id",
                    "AKIAGRANTTEST0001",
                    "--bucket",
                    "photos",
                    "--permission",
                    "READ",
                ),
                config.copyForCommand(),
            ),
        )
        assertEquals(0, runAdminCommand(arrayOf("admin", "grant", "list", "--access-key-id", "AKIAGRANTTEST0001"), config.copyForCommand()))
        assertEquals(
            0,
            runAdminCommand(
                arrayOf(
                    "admin",
                    "grant",
                    "remove",
                    "--access-key-id",
                    "AKIAGRANTTEST0001",
                    "--bucket",
                    "photos",
                    "--permission",
                    "READ",
                ),
                config.copyForCommand(),
            ),
        )
    }

    private fun newConfig(): ServerConfig {
        pg.start()
        val dataDir = Files.createTempDirectory("silofs-admin-test")
        val db = Database.fromUrl(pg.jdbcUrl, pg.username, pg.password)
        closeables += db
        closeables += AutoCloseable { dataDir.toFile().deleteRecursively() }
        val repo = JdbcMetadataRepository()
        val blobStore = FsBlobStore(dataDir)
        return ServerConfig(
            bindHost = "127.0.0.1",
            bindPort = 0,
            region = "us-east-1",
            dataDir = dataDir,
            database = db,
            blobStore = blobStore,
            repository = repo,
            credentialProvider = StaticCredentialProvider.single("AKID", "secret"),
            recoveryConfig = RecoveryConfig(1, 1, 1, 1, enabled = false),
            securityConfig = SecurityConfig(null, false, emptyList(), 0, 64),
        )
    }

    private fun ServerConfig.copyForCommand(): ServerConfig = copy(database = Database.fromUrl(pg.jdbcUrl, pg.username, pg.password))

    private fun seedObject(config: ServerConfig) {
        val stored =
            config.blobStore.beginWrite().use { write ->
                write.write(ByteArrayInputStream("hello".toByteArray()))
                write.commit()
            }
        config.database.withConnection { conn ->
            config.repository.createBucket(conn, "b", "us-east-1", "owner")
            config.repository.putObject(
                conn,
                ObjectMetadata(
                    bucket = "b",
                    key = "k",
                    blobPath = stored.blobPath.toString(),
                    blobSha256Hex = stored.sha256Hex,
                    etag = "\"etag\"",
                    sizeBytes = stored.sizeBytes,
                    contentType = "text/plain",
                    contentEncoding = null,
                    contentLanguage = null,
                    cacheControl = null,
                    contentDisposition = null,
                    expires = null,
                    userMetadata = emptyMap(),
                    versionId = "null",
                    storageClass = "STANDARD",
                    createdAt = Instant.now(),
                ),
            )
        }
        assertTrue(config.blobStore.exists(stored.blobPath))
    }
}
