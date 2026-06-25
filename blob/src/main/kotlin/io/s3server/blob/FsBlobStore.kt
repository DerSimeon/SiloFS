package app.silofs.blob

import app.silofs.common.ByteRange
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Result of writing a blob to the store. [blobPath] is the *final* path under
 * the data directory after the atomic rename. [sha256Hex] and [md5] are computed
 * while streaming so callers don't need a second pass.
 */
data class StoredBlob(
    val blobPath: Path,
    val sha256Hex: String,
    val md5: ByteArray,
    val sizeBytes: Long,
    val encryptionMode: String? = null,
    val encryptionKeyId: String? = null,
    val encryptionNonce: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredBlob) return false
        return blobPath == other.blobPath &&
            sha256Hex == other.sha256Hex &&
            sizeBytes == other.sizeBytes &&
            md5.contentEquals(other.md5) &&
            encryptionMode == other.encryptionMode &&
            encryptionKeyId == other.encryptionKeyId &&
            encryptionNonce.contentEqualsNullable(other.encryptionNonce)
    }

    override fun hashCode(): Int {
        var result = blobPath.hashCode()
        result = 31 * result + sha256Hex.hashCode()
        result = 31 * result + md5.contentHashCode()
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + (encryptionMode?.hashCode() ?: 0)
        result = 31 * result + (encryptionKeyId?.hashCode() ?: 0)
        result = 31 * result + (encryptionNonce?.contentHashCode() ?: 0)
        return result
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    when {
        this == null && other == null -> true
        this == null || other == null -> false
        else -> contentEquals(other)
    }

/**
 * A streaming write into the blob store. Use [write] to push bytes, then [commit]
 * to atomically publish. If [abort] is called (or the process crashes) the temp
 * file is left in place and the recovery job will sweep it.
 *
 * The temp file lives on the same filesystem as the final blob path so that
 * [Files.move] is atomic.
 */
interface BlobWrite : AutoCloseable {
    val sizeBytes: Long
    val sha256Hex: String
    val md5: ByteArray

    /** Append bytes from the given [source]. Returns the number of bytes transferred. */
    fun write(source: InputStream): Long

    /** Append a single buffer. */
    fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    )

    /**
     * Fsync the temp file, then atomically move it to the content-addressed
     * location. Returns the final [StoredBlob].
     */
    fun commit(): StoredBlob

    /** Delete the temp file. Safe to call after commit (no-op). */
    fun abort()
}

interface BlobStore {
    /**
     * Open a new write. The [expectedSha256Hex] (if non-null) is verified after
     * the upload completes — a mismatch throws [app.silofs.common.S3Exception]
     * with [app.silofs.common.S3ErrorCode.XAmzContentSHA256Mismatch].
     */
    fun beginWrite(expectedSha256Hex: String? = null): BlobWrite

    /** Open a streaming read for the whole blob. */
    fun openRead(blobPath: Path): FileChannel

    /** Open a streaming read for a [range]. */
    fun openRead(
        blobPath: Path,
        range: ByteRange,
    ): FileChannel

    /** Return the size of the blob in bytes. */
    fun sizeOf(blobPath: Path): Long

    /** Delete a blob. Idempotent. */
    fun delete(blobPath: Path): Boolean

    /** Check whether the blob exists on disk. */
    fun exists(blobPath: Path): Boolean

    /** Resolve a content-addressed blob path for the given sha256 hex. */
    fun pathFor(sha256Hex: String): Path

    /** List orphan temp files older than [olderThanSeconds]. */
    fun listOrphanTempFiles(olderThanSeconds: Long): List<Path>

    /** Delete a temp file. Idempotent. */
    fun deleteTemp(path: Path): Boolean

    /**
     * Concatenate the given parts (in order) into a new content-addressed blob.
     * Used by `CompleteMultipartUpload` in M3.
     */
    fun concatenate(
        parts: List<Path>,
        expectedSha256Hex: String? = null,
    ): StoredBlob
}

/**
 * Filesystem-backed [BlobStore]. Layout under [dataDir]:
 *
 *   <dataDir>/
 *     .tmp/                     — temp upload files
 *     objects/
 *       ab/cd/abcdef...         — content-addressed blobs (sha256 sharded)
 */
class FsBlobStore(
    val dataDir: Path,
    private val encryption: ObjectEncryption? = null,
) : BlobStore {
    private val tmpDir: Path = dataDir.resolve(".tmp")
    private val objectsDir: Path = dataDir.resolve("objects")
    private val quarantineDir: Path = dataDir.resolve(".quarantine")

    init {
        Files.createDirectories(tmpDir)
        Files.createDirectories(objectsDir)
        Files.createDirectories(quarantineDir)
    }

    override fun beginWrite(expectedSha256Hex: String?): BlobWrite = FsBlobWrite(this, expectedSha256Hex)

    override fun openRead(blobPath: Path): FileChannel =
        if (ObjectEncryption.isEncryptedBlob(blobPath)) {
            requireNotNull(encryption) { "encrypted blob requires S3_OBJECT_ENCRYPTION_MASTER_KEY" }
                .openDecryptedChannel(blobPath)
        } else {
            FileChannel.open(blobPath, StandardOpenOption.READ)
        }

    override fun openRead(
        blobPath: Path,
        range: ByteRange,
    ): FileChannel {
        val ch = openRead(blobPath)
        ch.position(range.start)
        return ch
    }

    override fun sizeOf(blobPath: Path): Long =
        if (ObjectEncryption.isEncryptedBlob(blobPath)) {
            openRead(blobPath).use { it.size() }
        } else {
            Files.size(blobPath)
        }

    override fun delete(blobPath: Path): Boolean = runCatching { Files.deleteIfExists(blobPath) }.getOrDefault(false)

    override fun exists(blobPath: Path): Boolean = Files.exists(blobPath)

    override fun pathFor(sha256Hex: String): Path {
        require(sha256Hex.length >= 4) { "sha256 too short: $sha256Hex" }
        val first = sha256Hex.substring(0, 2)
        val second = sha256Hex.substring(2, 4)
        return objectsDir.resolve(first).resolve(second).resolve(sha256Hex)
    }

    override fun listOrphanTempFiles(olderThanSeconds: Long): List<Path> {
        val cutoff = System.currentTimeMillis() - olderThanSeconds * 1000L
        if (!Files.exists(tmpDir)) return emptyList()
        return Files
            .walk(tmpDir)
            .filter { Files.isRegularFile(it) }
            .filter { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(Long.MAX_VALUE) < cutoff }
            .toList()
    }

    override fun deleteTemp(path: Path): Boolean = runCatching { Files.deleteIfExists(path) }.getOrDefault(false)

    fun quarantine(
        blobPath: Path,
        sha256Hex: String,
    ): Path? =
        runCatching {
            if (!Files.exists(blobPath)) return null
            Files.createDirectories(quarantineDir)
            val target = quarantineDir.resolve(sha256Hex)
            Files.move(blobPath, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            fsyncDirectory(quarantineDir)
            target
        }.getOrThrow()

    fun listQuarantinedBlobs(olderThanSeconds: Long): List<Path> {
        val cutoff = System.currentTimeMillis() - olderThanSeconds * 1000L
        if (!Files.exists(quarantineDir)) return emptyList()
        return Files
            .walk(quarantineDir)
            .use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .filter { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(Long.MAX_VALUE) < cutoff }
                    .toList()
            }
    }

    fun deleteQuarantined(path: Path): Boolean = runCatching { Files.deleteIfExists(path) }.getOrDefault(false)

    fun restoreQuarantined(path: Path): Path? =
        runCatching {
            if (!Files.exists(path)) return null
            val sha = path.fileName.toString()
            val target = pathFor(sha)
            ensureParentsFor(sha)
            if (Files.exists(target)) {
                Files.deleteIfExists(path)
                return target
            }
            Files.move(path, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            fsyncDirectory(target.parent)
            target
        }.getOrThrow()

    override fun concatenate(
        parts: List<Path>,
        expectedSha256Hex: String?,
    ): StoredBlob {
        val write = beginWrite(expectedSha256Hex)
        try {
            parts.forEach { p ->
                Files.newInputStream(p).use { input ->
                    write.write(input)
                }
            }
            return write.commit()
        } catch (t: Throwable) {
            write.abort()
            throw t
        }
    }

    /** Make the parent directories of the final blob path. */
    internal fun ensureParentsFor(sha256Hex: String) {
        val target = pathFor(sha256Hex)
        Files.createDirectories(target.parent)
    }

    internal fun fsyncDirectory(dir: Path) {
        try {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        } catch (e: java.nio.file.AccessDeniedException) {
            if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                throw e
            }
        }
    }

    internal fun encryptTempForPublish(
        tmpPath: Path,
        plaintextSha256Hex: String,
        plaintextSize: Long,
    ): Pair<Path, BlobEncryptionMetadata?> {
        val enc = encryption ?: return tmpPath to null
        val encryptedTmp = dataDir.resolve(".tmp").resolve("${UUID.randomUUID()}.enc")
        val metadata = enc.encryptFile(tmpPath, encryptedTmp, plaintextSha256Hex, plaintextSize)
        return encryptedTmp to metadata
    }
}

/**
 * Streaming writer. Backed by a [FileChannel] so we can `force(true)` (fsync)
 * before the rename.
 */
class FsBlobWrite(
    private val store: FsBlobStore,
    private val expectedSha256Hex: String?,
) : BlobWrite {
    private val tmpPath: Path = store.dataDir.resolve(".tmp").resolve(UUID.randomUUID().toString())
    private val channel: FileChannel =
        FileChannel.open(
            tmpPath,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
    private val sha256Digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    private val md5Digest: MessageDigest = MessageDigest.getInstance("MD5")
    private var size: Long = 0L
    private var committed: Boolean = false
    private var fsynced: Boolean = false
    private var aborted: Boolean = false

    override val sizeBytes: Long
        get() = size

    override val sha256Hex: String
        get() = sha256Digest.digest().joinToString("") { "%02x".format(it) }

    override val md5: ByteArray
        get() = md5Digest.digest()

    override fun write(source: InputStream): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = source.read(buf)
            if (read <= 0) break
            write(buf, 0, read)
            total += read
        }
        return total
    }

    override fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        check(!committed && !aborted) { "write after commit/abort" }
        val bb = ByteBuffer.wrap(buffer, offset, length)
        while (bb.remaining() > 0) {
            channel.write(bb)
        }
        sha256Digest.update(buffer, offset, length)
        md5Digest.update(buffer, offset, length)
        size += length
    }

    /** Exposed for [FsBlobStore.concatenate] — bypass the digest since we copy via sendfile. */
    internal fun rawChannel(): FileChannel {
        check(!committed && !aborted) { "channel accessed after commit/abort" }
        return channel
    }

    /** Mark extra bytes as part of the digest (used by concatenate). */
    internal fun markDigest(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        sha256Digest.update(buffer, offset, length)
        md5Digest.update(buffer, offset, length)
        size += length
    }

    override fun commit(): StoredBlob {
        check(!committed && !aborted) { "commit after commit/abort" }
        val hex = fsyncPhase()
        return renamePhase(hex)
    }

    /**
     * Phase 1: fsync the temp file and compute the sha256. Does NOT rename.
     * Returns the sha256 hex for [renamePhase].
     */
    fun fsyncPhase(): String {
        check(!committed && !aborted) { "fsync after commit/abort" }
        channel.force(true)

        val computed = sha256Digest.digest()
        val hex = computed.joinToString("") { "%02x".format(it) }

        if (expectedSha256Hex != null && !expectedSha256Hex.equals(hex, ignoreCase = true)) {
            runCatching { channel.close() }
            runCatching { Files.deleteIfExists(tmpPath) }
            throw app.silofs.common.S3Errors
                .sha256Mismatch(expectedSha256Hex, hex)
        }

        fsynced = true
        return hex
    }

    /**
     * Phase 2: atomically rename the temp file to its content-addressed path
     * and fsync the parent directory.
     */
    fun renamePhase(hex: String): StoredBlob {
        check(!committed && !aborted) { "rename after commit/abort" }
        check(fsynced) { "rename before fsync" }

        store.ensureParentsFor(hex)
        val target = store.pathFor(hex)

        val (publishPath, encryptionMetadata) = store.encryptTempForPublish(tmpPath, hex, size)
        Files.move(publishPath, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        store.fsyncDirectory(target.parent)

        committed = true
        runCatching { channel.close() }
        if (publishPath != tmpPath) runCatching { Files.deleteIfExists(tmpPath) }
        return StoredBlob(
            blobPath = target,
            sha256Hex = hex,
            md5 = md5Digest.digest(),
            sizeBytes = size,
            encryptionMode = encryptionMetadata?.mode,
            encryptionKeyId = encryptionMetadata?.keyId,
            encryptionNonce = encryptionMetadata?.nonce,
        )
    }

    override fun abort() {
        if (committed || aborted) return
        aborted = true
        runCatching { channel.close() }
        runCatching { Files.deleteIfExists(tmpPath) }
    }

    override fun close() {
        if (!committed) abort()
    }
}

/**
 * Convenience for callers that already have the bytes in memory — used by tests
 * and small writes. Production paths should stream via [BlobWrite] directly.
 */
fun BlobStore.writeFromBytes(
    bytes: ByteArray,
    expectedSha256Hex: String? = null,
): StoredBlob {
    val w = beginWrite(expectedSha256Hex)
    return try {
        w.write(bytes, 0, bytes.size)
        w.commit()
    } catch (t: Throwable) {
        w.abort()
        throw t
    }
}

/**
 * Convenience for callers that want to fully materialise a blob into memory —
 * used by tests. Production paths should stream from [BlobStore.openRead].
 */
fun BlobStore.readAllBytes(blobPath: Path): ByteArray = Files.readAllBytes(blobPath)

/**
 * Stream a [range] of a blob into the given [sink]. Returns the number of bytes
 * actually written. Used by the server's GetObject handler.
 */
fun BlobStore.streamRange(
    blobPath: Path,
    range: ByteRange,
    sink: OutputStream,
): Long {
    openRead(blobPath, range).use { ch ->
        val buf = ByteBuffer.allocate(64 * 1024)
        var remaining = range.length
        while (remaining > 0) {
            buf.clear()
            val limit = minOf(buf.capacity().toLong(), remaining).toInt()
            buf.limit(limit)
            val read = ch.read(buf)
            if (read <= 0) break
            buf.flip()
            sink.write(buf.array(), 0, read)
            remaining -= read
        }
        return range.length - remaining
    }
}
