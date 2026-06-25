package app.silofs.blob

import app.silofs.common.ObjectMetadata
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
import java.time.Instant

@Testcontainers
class BlobConsistencyCheckerTest {
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
        return Triple(FsBlobStore(tmp), JdbcMetadataRepository(), db)
    }

    @Test
    fun `reports missing live object blob`() {
        val (store, repo, db) = newSystem()
        val sha = "ab".repeat(32)
        db.withTransaction { c ->
            repo.createBucket(c, "b", "us-east-1", "o")
            repo.putObject(
                c,
                ObjectMetadata(
                    bucket = "b",
                    key = "missing.txt",
                    blobPath = store.pathFor(sha).toString(),
                    blobSha256Hex = sha,
                    etag = "\"deadbeef\"",
                    sizeBytes = 7L,
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

        val report = BlobConsistencyChecker(store, db).check()

        assertFalse(report.isConsistent)
        assertEquals(1, report.missingBlobs.size)
        assertEquals("object", report.missingBlobs.single().reference.kind)
        assertEquals("missing.txt", report.missingBlobs.single().reference.key)
        assertEquals(0, report.orphanBlobs.size)
    }

    @Test
    fun `reports orphan content blob without deleting it`() {
        val (store, _, db) = newSystem()
        val stored = store.writeFromBytes("orphan".toByteArray())

        val report = BlobConsistencyChecker(store, db).check()

        assertTrue(report.isConsistent)
        assertEquals(0, report.missingBlobs.size)
        assertEquals(listOf(stored.sha256Hex), report.orphanBlobs.map { it.sha256Hex })
        assertTrue(Files.exists(stored.blobPath))
    }

    @Test
    fun `active write intent counts as a reference`() {
        val (store, repo, db) = newSystem()
        val stored = store.writeFromBytes("in-flight".toByteArray())
        db.withTransaction { c ->
            repo.createBlobWriteIntent(c, stored.sha256Hex)
        }

        val report = BlobConsistencyChecker(store, db).check()

        assertTrue(report.isConsistent)
        assertEquals(1, report.referencedBlobCount)
        assertEquals(0, report.missingBlobs.size)
        assertEquals(0, report.orphanBlobs.size)
    }
}
