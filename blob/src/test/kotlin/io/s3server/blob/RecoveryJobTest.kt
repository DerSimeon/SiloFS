package app.silofs.blob

import app.silofs.metadata.Database
import app.silofs.metadata.JdbcMetadataRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

@Testcontainers
class RecoveryJobTest {
    @Container
    val pg: PostgreSQLContainer<*> =
        PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("s3test")
            .withUsername("test")
            .withPassword("test")

    @TempDir
    lateinit var tmp: Path

    private fun newSystem(): Triple<FsBlobStore, JdbcMetadataRepository, Database> {
        val db = Database.fromUrl(pg.jdbcUrl, pg.username, pg.password)
        return FsBlobStore(tmp) to JdbcMetadataRepository() to db
    }

    private infix fun <A, B> A.to(that: B): Pair<A, B> = Pair(this, that)

    private infix fun <A, B, C> Pair<A, B>.to(that: C): Triple<A, B, C> = Triple(first, second, that)

    @Test
    fun `sweepTempFiles removes old temps`() {
        val (store, _, _) = newSystem()
        val orphan = tmp.resolve(".tmp").resolve("orphan")
        Files.write(orphan, byteArrayOf(1, 2, 3))
        Files.setLastModifiedTime(orphan, FileTime.fromMillis(System.currentTimeMillis() - 2 * 3600 * 1000L))
        val job =
            RecoveryJob(store, JdbcMetadataRepository(), Database.fromUrl(pg.jdbcUrl, pg.username, pg.password), tempMaxAgeSeconds = 3600)
        job.sweepTempFiles()
        assertFalse(Files.exists(orphan))
    }

    @Test
    fun `sweepTempFiles ignores fresh temps`() {
        val (store, _, _) = newSystem()
        val fresh = tmp.resolve(".tmp").resolve("fresh")
        Files.write(fresh, byteArrayOf(1))
        val job =
            RecoveryJob(store, JdbcMetadataRepository(), Database.fromUrl(pg.jdbcUrl, pg.username, pg.password), tempMaxAgeSeconds = 3600)
        job.sweepTempFiles()
        assertTrue(Files.exists(fresh))
    }

    @Test
    fun `sweepStaleMultipartUploads aborts stale uploads`() {
        val (store, repo, db) = newSystem()
        val uploadId = app.silofs.metadata.newUploadId()
        db.withTransaction { c ->
            repo.createBucket(c, "b", "us-east-1", "o")
            repo.createMultipartUpload(
                c,
                uploadId,
                "b",
                "k",
                "application/octet-stream",
                emptyMap(),
                "STANDARD",
                null,
                null,
                null,
                null,
                null,
                null,
            )
        }
        val job = RecoveryJob(store, repo, db, multipartMaxAgeSeconds = 0)
        job.sweepStaleMultipartUploads()
        val remaining = db.withConnection { c -> repo.listStaleMultipartUploads(c, 0) }
        assertEquals(0, remaining.size)
    }

    @Test
    fun `sweepUnreferencedBlobs removes only unreferenced content`() {
        val (store, repo, db) = newSystem()
        val stored = store.writeFromBytes("hello".toByteArray())
        val unreferenced = store.writeFromBytes("orphan".toByteArray())

        // Write metadata for stored, but not for unreferenced.
        db.withTransaction { c ->
            repo.createBucket(c, "b", "us-east-1", "o")
            repo.putObject(
                c,
                app.silofs.common.ObjectMetadata(
                    bucket = "b",
                    key = "k",
                    blobPath = stored.blobPath.toString(),
                    blobSha256Hex = stored.sha256Hex,
                    etag = "\"deadbeef\"",
                    sizeBytes = 5L,
                    contentType = "text/plain",
                    contentEncoding = null,
                    contentLanguage = null,
                    cacheControl = null,
                    contentDisposition = null,
                    expires = null,
                    userMetadata = emptyMap(),
                    versionId = "null",
                    storageClass = "STANDARD",
                    createdAt = java.time.Instant.now(),
                ),
            )
        }
        assertTrue(Files.exists(stored.blobPath))
        assertTrue(Files.exists(unreferenced.blobPath))

        val job = RecoveryJob(store, repo, db)
        job.sweepUnreferencedBlobs()

        assertTrue(Files.exists(stored.blobPath))
        assertFalse(Files.exists(unreferenced.blobPath))
    }

    @Test
    fun `sweepUnreferencedBlobs keeps blobs with active write intents`() {
        val (store, repo, db) = newSystem()
        val stored = store.writeFromBytes("in-flight".toByteArray())
        db.withTransaction { c ->
            repo.createBlobWriteIntent(c, stored.sha256Hex)
        }

        val job = RecoveryJob(store, repo, db)
        job.sweepUnreferencedBlobs()

        assertTrue(Files.exists(stored.blobPath))
    }

    @Test
    fun `sweepStaleMultipartUploads marks stuck completing as failed completion`() {
        val (store, repo, db) = newSystem()
        val uploadId = app.silofs.metadata.newUploadId()
        db.withTransaction { c ->
            repo.createBucket(c, "b", "us-east-1", "o")
            repo.createMultipartUpload(
                c,
                uploadId,
                "b",
                "k",
                "application/octet-stream",
                emptyMap(),
                "STANDARD",
                null,
                null,
                null,
                null,
                null,
                null,
            )
            assertTrue(repo.markMultipartCompleting(c, uploadId))
        }

        val job = RecoveryJob(store, repo, db, multipartMaxAgeSeconds = Long.MAX_VALUE)
        job.sweepStaleMultipartUploads()

        val state =
            db.withConnection { c ->
                c.prepareStatement("SELECT state FROM multipart_uploads WHERE upload_id = ?").use { ps ->
                    ps.setString(1, uploadId)
                    ps.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        rs.getString(1)
                    }
                }
            }
        assertEquals("FAILED_COMPLETION", state)
    }

    @Test
    fun `expired blob write intents are removed before orphan blob sweep`() {
        val (store, repo, db) = newSystem()
        val stored = store.writeFromBytes("abandoned intent".toByteArray())
        db.withTransaction { c ->
            repo.createBlobWriteIntent(c, stored.sha256Hex)
        }

        val job = RecoveryJob(store, repo, db, blobWriteIntentMaxAgeSeconds = -1)
        job.sweepStaleBlobWriteIntents()
        job.sweepUnreferencedBlobs()

        assertFalse(Files.exists(stored.blobPath))
    }

    @Test
    fun `runOnce executes all sweeps without throwing`() {
        val (store, repo, db) = newSystem()
        val job = RecoveryJob(store, repo, db, multipartMaxAgeSeconds = 0)
        job.runOnce() // should not throw even on empty DB
    }
}
