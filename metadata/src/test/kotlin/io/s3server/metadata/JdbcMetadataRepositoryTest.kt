package app.silofs.metadata

import app.silofs.common.ObjectMetadata
import app.silofs.common.S3ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.time.Instant

@Testcontainers
class JdbcMetadataRepositoryTest {

    @Container
    val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("s3test")
        .withUsername("test")
        .withPassword("test")

    private fun newRepo(): Pair<Database, JdbcMetadataRepository> {
        val db = Database.fromUrl(pg.jdbcUrl, pg.username, pg.password)
        return db to JdbcMetadataRepository()
    }

    @Test
    fun `bucket lifecycle`() {
        val (db, repo) = newRepo()
        db.use {
            it.withTransaction { c ->
                repo.createBucket(c, "my-bucket", "us-east-1", "owner-1")
                assertTrue(repo.bucketExists(c, "my-bucket"))
                val bucket = repo.getBucket(c, "my-bucket")
                assertNotNull(bucket)
                assertEquals("us-east-1", bucket!!.region)
                assertEquals("owner-1", bucket.ownerId)

                val listed = repo.listBuckets(c)
                assertEquals(1, listed.size)
                assertEquals("my-bucket", listed[0].name)

                assertTrue(repo.deleteBucket(c, "my-bucket"))
                assertFalse(repo.bucketExists(c, "my-bucket"))
                assertNull(repo.getBucket(c, "my-bucket"))
            }
        }
    }

    @Test
    fun `createBucket is idempotent`() {
        val (db, repo) = newRepo()
        db.use {
            it.withTransaction { c ->
                repo.createBucket(c, "dup", "us-east-1", "o")
                repo.createBucket(c, "dup", "us-east-1", "o")
                assertTrue(repo.bucketExists(c, "dup"))
            }
        }
    }

    @Test
    fun `object lifecycle`() {
        val (db, repo) = newRepo()
        db.use {
            it.withTransaction { c ->
                repo.createBucket(c, "b", "us-east-1", "o")
                val meta = ObjectMetadata(
                    bucket = "b",
                    key = "k.txt",
                    blobPath = "objects/ab/cd/abcdef",
                    blobSha256Hex = "ab".repeat(32),
                    etag = "\"deadbeef\"",
                    sizeBytes = 42L,
                    contentType = "text/plain",
                    contentEncoding = null,
                    contentLanguage = null,
                    cacheControl = null,
                    contentDisposition = null,
                    expires = null,
                    userMetadata = mapOf("foo" to "bar", "x" to "y"),
                    versionId = "null",
                    storageClass = "STANDARD",
                    createdAt = Instant.now()
                )
                val saved = repo.putObject(c, meta)
                assertEquals("k.txt", saved.key)

                val fetched = repo.getObject(c, "b", "k.txt")
                assertNotNull(fetched)
                assertEquals(42L, fetched!!.sizeBytes)
                assertEquals("\"deadbeef\"", fetched.etag)
                assertEquals("bar", fetched.userMetadata["foo"])
                assertEquals("y", fetched.userMetadata["x"])

                assertTrue(repo.deleteObject(c, "b", "k.txt"))
                assertNull(repo.getObject(c, "b", "k.txt"))
                assertFalse(repo.deleteObject(c, "b", "k.txt"))
            }
        }
    }

    @Test
    fun `putObject overwrites existing key`() {
        val (db, repo) = newRepo()
        db.use {
            it.withTransaction { c ->
                repo.createBucket(c, "b", "us-east-1", "o")
                val m1 = sampleMeta("b", "k", sizeBytes = 1)
                val m2 = sampleMeta("b", "k", sizeBytes = 2)
                repo.putObject(c, m1)
                repo.putObject(c, m2)
                val fetched = repo.getObject(c, "b", "k")!!
                assertEquals(2L, fetched.sizeBytes)
            }
        }
    }

    @Test
    fun `listObjects paginates`() {
        val (db, repo) = newRepo()
        db.use {
            it.withTransaction { c ->
                repo.createBucket(c, "b", "us-east-1", "o")
                (1..25).forEach { i ->
                    repo.putObject(c, sampleMeta("b", "key-%02d".format(i), sizeBytes = i.toLong()))
                }
                val first = repo.listObjects(c, "b", maxKeys = 10, continuationToken = null, startAfter = null)
                assertEquals(10, first.contents.size)
                assertTrue(first.isTruncated)
                assertEquals("key-10", first.nextContinuationToken)

                val second = repo.listObjects(c, "b", maxKeys = 10, continuationToken = first.nextContinuationToken, startAfter = null)
                assertEquals(10, second.contents.size)
                assertEquals("key-20", second.nextContinuationToken)
                assertTrue(second.isTruncated)

                val third = repo.listObjects(c, "b", maxKeys = 10, continuationToken = second.nextContinuationToken, startAfter = null)
                assertEquals(5, third.contents.size)
                assertFalse(third.isTruncated)
                assertNull(third.nextContinuationToken)
            }
        }
    }

    @Test
    fun `listObjects respects startAfter`() {
        val (db, repo) = newRepo()
        db.use {
            it.withTransaction { c ->
                repo.createBucket(c, "b", "us-east-1", "o")
                listOf("a", "b", "c", "d").forEach { k ->
                    repo.putObject(c, sampleMeta("b", k, sizeBytes = 1))
                }
                val r = repo.listObjects(c, "b", maxKeys = 10, continuationToken = null, startAfter = "b")
                assertEquals(listOf("c", "d"), r.contents.map { it.key })
            }
        }
    }

    @Test
    fun `listObjects clamps maxKeys`() {
        val (db, repo) = newRepo()
        db.use {
            it.withTransaction { c ->
                repo.createBucket(c, "b", "us-east-1", "o")
                listOf("a", "b", "c").forEach { k ->
                    repo.putObject(c, sampleMeta("b", k, sizeBytes = 1))
                }
                val r = repo.listObjects(c, "b", maxKeys = 0, continuationToken = null, startAfter = null)
                assertEquals(1, r.maxKeys)
                assertEquals(1, r.contents.size)
            }
        }
    }

    @Test
    fun `countObjectsInBucket returns active count only`() {
        val (db, repo) = newRepo()
        db.use {
            it.withTransaction { c ->
                repo.createBucket(c, "b", "us-east-1", "o")
                repo.putObject(c, sampleMeta("b", "a", sizeBytes = 1))
                repo.putObject(c, sampleMeta("b", "b", sizeBytes = 1))
                repo.deleteObject(c, "b", "a")
                assertEquals(1L, repo.countObjectsInBucket(c, "b"))
            }
        }
    }

    @Test
    fun `access keys round trip`() {
        val (db, repo) = newRepo()
        db.use {
            it.withConnection { c ->
                repo.upsertAccessKey(c, "AKID", "secret", "test key")
                assertEquals("secret", repo.lookupSecret(c, "AKID"))
                repo.upsertAccessKey(c, "AKID", "new-secret", "test key")
                assertEquals("new-secret", repo.lookupSecret(c, "AKID"))
                assertNull(repo.lookupSecret(c, "nope"))
            }
        }
    }

    @Test
    fun `multipart upload state transitions`() {
        val (db, repo) = newRepo()
        db.use {
            it.withTransaction { c ->
                repo.createBucket(c, "b", "us-east-1", "o")
                val uploadId = newUploadId()
                createTestMultipart(repo, c, uploadId)
                val stale = repo.listStaleMultipartUploads(c, olderThanSeconds = 0)
                assertEquals(1, stale.size)
                assertEquals(uploadId, stale[0].uploadId)
                assertTrue(repo.abortMultipartUpload(c, uploadId))
                assertFalse(repo.abortMultipartUpload(c, uploadId))
            }
        }
    }

    @Test
    fun `completion failure is terminal and does not reopen upload`() {
        val (db, repo) = newRepo()
        db.use {
            it.withTransaction { c ->
                repo.createBucket(c, "b", "us-east-1", "o")
                val uploadId = newUploadId()
                createTestMultipart(repo, c, uploadId)

                assertTrue(repo.markMultipartCompleting(c, uploadId))
                assertTrue(repo.failMultipartCompletion(c, uploadId))
                assertFalse(repo.abortMultipartUpload(c, uploadId))
                assertNull(repo.getMultipartUpload(c, uploadId))
            }
        }
    }

    @Test
    fun `abort only wins from initiated state`() {
        val (db, repo) = newRepo()
        db.use {
            it.withTransaction { c ->
                repo.createBucket(c, "b", "us-east-1", "o")
                val uploadId = newUploadId()
                createTestMultipart(repo, c, uploadId)

                assertTrue(repo.markMultipartCompleting(c, uploadId))
                assertFalse(repo.abortMultipartUpload(c, uploadId))
                val current = repo.getMultipartUpload(c, uploadId)
                assertNotNull(current)
                assertEquals("COMPLETING", current!!.state)
            }
        }
    }

    @Test
    fun `abort losing to completing leaves parts intact`() {
        val (db, repo) = newRepo()
        db.use {
            it.withTransaction { c ->
                repo.createBucket(c, "b", "us-east-1", "o")
                val uploadId = newUploadId()
                createTestMultipart(repo, c, uploadId)
                repo.uploadPart(
                    c,
                    uploadId,
                    1,
                    "objects/ab/cd/${"ab".repeat(32)}",
                    "ab".repeat(32),
                    "\"etag\"",
                    10L,
                )

                assertTrue(repo.markMultipartCompleting(c, uploadId))
                assertFalse(repo.abortMultipartUpload(c, uploadId))
                assertEquals(1, repo.listParts(c, uploadId).size)
            }
        }
    }

    @Test
    fun `complete only succeeds from completing state`() {
        val (db, repo) = newRepo()
        db.use {
            it.withTransaction { c ->
                repo.createBucket(c, "b", "us-east-1", "o")
                val uploadId = newUploadId()
                createTestMultipart(repo, c, uploadId)

                assertFalse(repo.completeMultipartUpload(c, uploadId))
                assertTrue(repo.markMultipartCompleting(c, uploadId))
                assertTrue(repo.completeMultipartUpload(c, uploadId))
                assertFalse(repo.completeMultipartUpload(c, uploadId))
                assertNull(repo.getMultipartUpload(c, uploadId))
            }
        }
    }

    @Test
    fun `blob write intents create clear and expire`() {
        val (db, repo) = newRepo()
        db.use {
            it.withTransaction { c ->
                val intentId = repo.createBlobWriteIntent(c, "ab".repeat(32))
                assertTrue(repo.clearBlobWriteIntent(c, intentId))
                assertFalse(repo.clearBlobWriteIntent(c, intentId))

                repo.createBlobWriteIntent(c, "cd".repeat(32))
                assertEquals(1, repo.deleteStaleBlobWriteIntents(c, olderThanSeconds = -1))
                assertEquals(0, repo.deleteStaleBlobWriteIntents(c, olderThanSeconds = -1))
            }
        }
    }

    @Test
    fun `json map round trips unicode`() {
        val (db, repo) = newRepo()
        db.use {
            it.withTransaction { c ->
                repo.createBucket(c, "b", "us-east-1", "o")
                val meta = sampleMeta("b", "k", sizeBytes = 1).copy(
                    userMetadata = mapOf("unicode" to "日本語", "quote" to "a\"b", "newline" to "a\nb")
                )
                repo.putObject(c, meta)
                val fetched = repo.getObject(c, "b", "k")!!
                assertEquals("日本語", fetched.userMetadata["unicode"])
                assertEquals("a\"b", fetched.userMetadata["quote"])
                assertEquals("a\nb", fetched.userMetadata["newline"])
            }
        }
    }

    @Test
    fun `toS3 wraps non-s3 throwable`() {
        val ex = RuntimeException("boom")
        val wrapped = ex.toS3()
        assertEquals(S3ErrorCode.InternalError, wrapped.errorCode)
    }

    @Test
    fun `toS3 passes s3 exceptions through`() {
        val ex = app.silofs.common.S3Errors.noSuchBucket("b")
        assert(ex === ex.toS3())
    }

    private fun createTestMultipart(
        repo: JdbcMetadataRepository,
        conn: Connection,
        uploadId: String,
    ) {
        repo.createMultipartUpload(
            conn,
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

    private fun sampleMeta(bucket: String, key: String, sizeBytes: Long): ObjectMetadata =
        ObjectMetadata(
            bucket = bucket,
            key = key,
            blobPath = "objects/ab/cd/abcdef",
            blobSha256Hex = "ab".repeat(32),
            etag = "\"deadbeef\"",
            sizeBytes = sizeBytes,
            contentType = "application/octet-stream",
            contentEncoding = null,
            contentLanguage = null,
            cacheControl = null,
            contentDisposition = null,
            expires = null,
            userMetadata = emptyMap(),
            versionId = "null",
            storageClass = "STANDARD",
            createdAt = Instant.now()
        )
}

class JsonHelpersTest {
    @Test
    fun `empty map serialises to braces`() {
        assertEquals("{}", emptyMap<String, String>().toJson())
    }

    @Test
    fun `empty braces parse to empty map`() {
        assertEquals(emptyMap<String, String>(), "{}".fromJson())
    }

    @Test
    fun `empty string parses to empty map`() {
        assertEquals(emptyMap<String, String>(), "".fromJson())
    }

    @Test
    fun `json parser accepts database formatted whitespace`() {
        assertEquals(
            linkedMapOf("author" to "test", "purpose" to "smoke"),
            """{"author": "test", "purpose": "smoke" }""".fromJson()
        )
    }

    @Test
    fun `round trip preserves order`() {
        val m = linkedMapOf("c" to "1", "a" to "2", "b" to "3")
        val parsed = m.toJson().fromJson()
        assertEquals(m, parsed)
    }

    @Test
    fun `hex round trips`() {
        val bytes = byteArrayOf(0x00, 0x01, 0x7f, 0x80.toByte(), 0xff.toByte())
        val hex = bytesToHex(bytes)
        assertEquals(bytes.toList(), hexToBytes(hex).toList())
    }

    @Test
    fun `odd length hex rejected`() {
        assertThrows<IllegalArgumentException> { hexToBytes("abc") }
    }

    @Test
    fun `new upload id is 32 hex chars`() {
        val id = newUploadId()
        assertEquals(32, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
    }

    private fun assertTrue(b: Boolean) = org.junit.jupiter.api.Assertions.assertTrue(b)
}
