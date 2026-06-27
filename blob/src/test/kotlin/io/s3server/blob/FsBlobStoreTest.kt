package app.silofs.blob

import app.silofs.common.ByteRange
import app.silofs.common.S3ErrorCode
import app.silofs.common.S3Exception
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executors

class FsBlobStoreTest {
    @TempDir
    lateinit var tmp: Path

    private fun newStore(): FsBlobStore = FsBlobStore(tmp)

    @Test
    fun `beginWrite and commit produces content-addressed blob`() {
        val store = newStore()
        val payload = "hello world".toByteArray()
        val w = store.beginWrite()
        w.write(payload, 0, payload.size)
        val stored = w.commit()

        val expectedSha =
            MessageDigest
                .getInstance("SHA-256")
                .digest(payload)
                .joinToString("") { "%02x".format(it) }
        assertEquals(expectedSha, stored.sha256Hex)
        assertEquals(payload.size.toLong(), stored.sizeBytes)
        assertTrue(Files.exists(stored.blobPath))
        assertArrayEquals(payload, Files.readAllBytes(stored.blobPath))
    }

    @Test
    fun `write from InputStream`() {
        val store = newStore()
        val payload = ByteArray(100_000) { (it and 0xff).toByte() }
        val w = store.beginWrite()
        val n = w.write(ByteArrayInputStream(payload))
        assertEquals(payload.size.toLong(), n)
        val stored = w.commit()
        assertArrayEquals(payload, Files.readAllBytes(stored.blobPath))
    }

    @Test
    fun `concurrent commits of identical content are idempotent`() {
        val store = newStore()
        val payload = ByteArray(4096) { (it % 251).toByte() }

        Executors.newFixedThreadPool(16).use { pool ->
            val writes = (0 until 128).map {
                pool.submit<StoredBlob> {
                    store.writeFromBytes(payload)
                }
            }
            val stored = writes.map { it.get() }
            assertEquals(1, stored.map { it.blobPath }.distinct().size)
            stored.forEach {
                assertEquals(payload.size.toLong(), it.sizeBytes)
                assertArrayEquals(payload, Files.readAllBytes(it.blobPath))
            }
        }
    }

    @Test
    fun `encrypted store publishes ciphertext and reads plaintext`() {
        val key = ByteArray(32) { 7 }
        val store = FsBlobStore(tmp, ObjectEncryption(key))
        val payload = "secret object bytes".toByteArray()

        val stored = store.writeFromBytes(payload)

        assertEquals(ObjectEncryption.SSE_S3_MODE, stored.encryptionMode)
        assertEquals(ObjectEncryption.DEFAULT_KEY_ID, stored.encryptionKeyId)
        assertTrue(ObjectEncryption.isEncryptedBlob(stored.blobPath))
        assertFalse(Files.readAllBytes(stored.blobPath).contentEquals(payload))
        assertEquals(payload.size.toLong(), store.sizeOf(stored.blobPath))

        val out = ByteArrayOutputStream()
        store.openRead(stored.blobPath).use { ch ->
            val buf = java.nio.ByteBuffer.allocate(64)
            while (ch.read(buf) > 0) {
                buf.flip()
                out.write(buf.array(), 0, buf.remaining())
                buf.clear()
            }
        }
        assertArrayEquals(payload, out.toByteArray())
    }

    @Test
    fun `encrypted store supports plaintext range reads`() {
        val store = FsBlobStore(tmp, ObjectEncryption(ByteArray(32) { 9 }))
        val payload = ByteArray(512) { it.toByte() }
        val stored = store.writeFromBytes(payload)
        val out = ByteArrayOutputStream()

        store.streamRange(stored.blobPath, ByteRange(100, 149), out)

        assertArrayEquals(payload.copyOfRange(100, 150), out.toByteArray())
    }

    @Test
    fun `encrypted blob fails with wrong key`() {
        val encryptedStore = FsBlobStore(tmp, ObjectEncryption(ByteArray(32) { 1 }))
        val stored = encryptedStore.writeFromBytes("secret".toByteArray())
        val wrongKeyStore = FsBlobStore(tmp, ObjectEncryption(ByteArray(32) { 2 }))

        assertThrows<Exception> {
            wrongKeyStore.openRead(stored.blobPath).use { it.size() }
        }
    }

    @Test
    fun `commit rejects mismatched expected sha256`() {
        val store = newStore()
        val payload = "hello".toByteArray()
        val w = store.beginWrite(expectedSha256Hex = "00".repeat(32))
        w.write(payload, 0, payload.size)
        val ex = assertThrows<S3Exception> { w.commit() }
        assertEquals(S3ErrorCode.XAmzContentSHA256Mismatch, ex.errorCode)
        // Temp file cleaned up
        assertEquals(0, Files.list(tmp.resolve(".tmp")).count())
    }

    @Test
    fun `commit accepts matching expected sha256`() {
        val store = newStore()
        val payload = "hello".toByteArray()
        val expected =
            MessageDigest
                .getInstance("SHA-256")
                .digest(payload)
                .joinToString("") { "%02x".format(it) }
        val w = store.beginWrite(expectedSha256Hex = expected)
        w.write(payload, 0, payload.size)
        val stored = w.commit()
        assertEquals(expected, stored.sha256Hex)
    }

    @Test
    fun `fsyncPhase does not publish content-addressed blob before renamePhase`() {
        val store = newStore()
        val payload = "split publish".toByteArray()
        val write = store.beginWrite() as FsBlobWrite
        write.write(payload, 0, payload.size)

        val sha = write.fsyncPhase()
        val target = store.pathFor(sha)

        assertFalse(Files.exists(target))
        assertEquals(1, Files.list(tmp.resolve(".tmp")).use { it.count() })

        val stored = write.renamePhase(sha)

        assertEquals(target, stored.blobPath)
        assertTrue(Files.exists(target))
        assertEquals(0, Files.list(tmp.resolve(".tmp")).use { it.count() })
        assertArrayEquals(payload, Files.readAllBytes(target))
    }

    @Test
    fun `renamePhase rejects publish before fsyncPhase`() {
        val store = newStore()
        val write = store.beginWrite() as FsBlobWrite
        write.write("not fsynced".toByteArray(), 0, "not fsynced".length)

        assertThrows<IllegalStateException> { write.renamePhase("ab".repeat(32)) }
        write.abort()
        assertEquals(0, Files.list(tmp.resolve(".tmp")).use { it.count() })
    }

    @Test
    fun `duplicate content uploads are deduplicated on disk`() {
        val store = newStore()
        val payload = "same".toByteArray()
        val s1 = store.writeFromBytes(payload)
        val s2 = store.writeFromBytes(payload)
        assertEquals(s1.blobPath, s2.blobPath)
    }

    @Test
    fun `abort deletes temp file`() {
        val store = newStore()
        val w = store.beginWrite()
        w.write(byteArrayOf(1, 2, 3), 0, 3)
        w.abort()
        assertEquals(0, Files.list(tmp.resolve(".tmp")).count())
    }

    @Test
    fun `abort after commit is a no-op`() {
        val store = newStore()
        val w = store.beginWrite()
        w.write(byteArrayOf(1), 0, 1)
        val stored = w.commit()
        w.abort()
        assertTrue(Files.exists(stored.blobPath))
    }

    @Test
    fun `write after commit throws`() {
        val store = newStore()
        val w = store.beginWrite()
        w.write(byteArrayOf(1), 0, 1)
        w.commit()
        assertThrows<IllegalStateException> { w.write(byteArrayOf(2), 0, 1) }
    }

    @Test
    fun `close without commit aborts`() {
        val store = newStore()
        val w = store.beginWrite()
        w.write(byteArrayOf(1), 0, 1)
        w.close()
        assertEquals(0, Files.list(tmp.resolve(".tmp")).count())
    }

    @Test
    fun `openRead streams whole blob`() {
        val store = newStore()
        val payload = "stream me".toByteArray()
        val stored = store.writeFromBytes(payload)
        val out = ByteArrayOutputStream()
        store.openRead(stored.blobPath).use { ch ->
            val buf = java.nio.ByteBuffer.allocate(8)
            while (ch.read(buf) > 0) {
                buf.flip()
                out.write(buf.array(), 0, buf.remaining())
                buf.clear()
            }
        }
        assertArrayEquals(payload, out.toByteArray())
    }

    @Test
    fun `streamRange returns subset`() {
        val store = newStore()
        val payload = ByteArray(1000) { (it and 0xff).toByte() }
        val stored = store.writeFromBytes(payload)
        val range = ByteRange(100, 199)
        val out = ByteArrayOutputStream()
        val n = store.streamRange(stored.blobPath, range, out)
        assertEquals(100L, n)
        assertArrayEquals(payload.copyOfRange(100, 200), out.toByteArray())
    }

    @Test
    fun `pathFor shards by sha256`() {
        val store = newStore()
        val p = store.pathFor("abcd".repeat(16))
        assertTrue(p.endsWith("objects/ab/cd/${"abcd".repeat(16)}"))
    }

    @Test
    fun `exists returns true for stored blob`() {
        val store = newStore()
        val stored = store.writeFromBytes("x".toByteArray())
        assertTrue(store.exists(stored.blobPath))
        assertFalse(store.exists(stored.blobPath.resolveSibling("nope")))
    }

    @Test
    fun `delete is idempotent`() {
        val store = newStore()
        val stored = store.writeFromBytes("x".toByteArray())
        assertTrue(store.delete(stored.blobPath))
        assertFalse(store.delete(stored.blobPath))
    }

    @Test
    fun `listOrphanTempFiles finds old temps`() {
        val store = newStore()
        val orphan = tmp.resolve(".tmp").resolve("orphan")
        Files.write(orphan, byteArrayOf(1, 2, 3))
        // Backdate the file
        val oldTime =
            java.nio.file.attribute.FileTime
                .fromMillis(System.currentTimeMillis() - 10 * 60 * 1000)
        Files.setLastModifiedTime(orphan, oldTime)
        val orphans = store.listOrphanTempFiles(olderThanSeconds = 60)
        assertEquals(1, orphans.size)
        assertEquals(orphan, orphans[0])
    }

    @Test
    fun `listOrphanTempFiles ignores fresh temps`() {
        val store = newStore()
        val fresh = tmp.resolve(".tmp").resolve("fresh")
        Files.write(fresh, byteArrayOf(1))
        val orphans = store.listOrphanTempFiles(olderThanSeconds = 3600)
        assertEquals(0, orphans.size)
    }

    @Test
    fun `quarantine delete and restore move content safely`() {
        val store = newStore()
        val stored = store.writeFromBytes("quarantine me".toByteArray())

        val quarantined = store.quarantine(stored.blobPath, stored.sha256Hex)!!

        assertFalse(Files.exists(stored.blobPath))
        assertTrue(Files.exists(quarantined))
        assertEquals(listOf(quarantined), store.listQuarantinedBlobs(olderThanSeconds = -1))

        val restored = store.restoreQuarantined(quarantined)

        assertEquals(stored.blobPath, restored)
        assertTrue(Files.exists(stored.blobPath))
        assertFalse(Files.exists(quarantined))

        val quarantinedAgain = store.quarantine(stored.blobPath, stored.sha256Hex)!!
        assertTrue(store.deleteQuarantined(quarantinedAgain))
        assertFalse(store.deleteQuarantined(quarantinedAgain))
    }

    @Test
    fun `concatenate joins parts and recomputes hash`() {
        val store = newStore()
        val p1 = store.writeFromBytes("hello ".toByteArray())
        val p2 = store.writeFromBytes("world".toByteArray())
        val combined = store.concatenate(listOf(p1.blobPath, p2.blobPath))
        assertArrayEquals("hello world".toByteArray(), Files.readAllBytes(combined.blobPath))

        val expectedHash =
            MessageDigest
                .getInstance("SHA-256")
                .digest("hello world".toByteArray())
                .joinToString("") { "%02x".format(it) }
        assertEquals(expectedHash, combined.sha256Hex)
    }

    @Test
    fun `StoredBlob equals and hashCode`() {
        val store = newStore()
        val s1 = store.writeFromBytes("x".toByteArray())
        val s2 = s1.copy()
        assertEquals(s1, s2)
        assertEquals(s1.hashCode(), s2.hashCode())
        assertNotEquals(s1, s1.copy(sizeBytes = s1.sizeBytes + 1))
    }

    @Test
    fun `writeFromBytes propagates abort on failure`() {
        val store = newStore()
        assertThrows<S3Exception> {
            store.writeFromBytes("x".toByteArray(), expectedSha256Hex = "00".repeat(32))
        }
        assertEquals(0, Files.list(tmp.resolve(".tmp")).count())
    }
}
