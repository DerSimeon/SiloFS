package app.silofs.blob

import app.silofs.metadata.Database
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.io.path.isRegularFile

data class BlobReference(
    val kind: String,
    val sha256Hex: String,
    val blobPath: String,
    val sizeBytes: Long? = null,
    val encryptionMode: String? = null,
    val bucket: String? = null,
    val key: String? = null,
    val uploadId: String? = null,
    val partNumber: Int? = null,
)

data class MissingBlob(
    val reference: BlobReference,
    val expectedPath: Path,
)

data class OrphanBlob(
    val sha256Hex: String,
    val path: Path,
)

data class CorruptBlob(
    val reference: BlobReference,
    val path: Path,
    val reason: String,
)

data class BlobConsistencyReport(
    val referencedBlobCount: Int,
    val contentBlobCount: Int,
    val quarantinedBlobCount: Int,
    val missingBlobs: List<MissingBlob>,
    val orphanBlobs: List<OrphanBlob>,
    val corruptBlobs: List<CorruptBlob> = emptyList(),
) {
    val isConsistent: Boolean
        get() = missingBlobs.isEmpty() && corruptBlobs.isEmpty()
}

/**
 * Read-only DB/blob consistency checker for M4/M8 admin workflows.
 *
 * The checker never deletes or repairs data. It compares live DB references
 * with the content-addressed filesystem and reports:
 *
 * - missing referenced blobs, which are production-blocking corruption signals
 * - unreferenced content blobs, which are GC candidates after normal safety gates
 * - quarantined blobs waiting for final deletion or restore
 */
class BlobConsistencyChecker(
    private val blobStore: FsBlobStore,
    private val database: Database,
) {
    fun check(): BlobConsistencyReport {
        val references = database.withConnection { conn -> loadReferences(conn) }
        val referencesBySha = references.groupBy { it.sha256Hex }
        val contentBlobs = listContentBlobs()
        val missing =
            references
                .filter { ref -> !Files.exists(blobStore.pathFor(ref.sha256Hex)) }
                .map { ref -> MissingBlob(ref, blobStore.pathFor(ref.sha256Hex)) }
        val corrupt =
            references
                .filter { ref -> Files.exists(blobStore.pathFor(ref.sha256Hex)) && ref.sizeBytes != null }
                .mapNotNull { ref -> validateReference(ref, blobStore.pathFor(ref.sha256Hex)) }
        val orphans =
            contentBlobs
                .filter { blob -> blob.sha256Hex !in referencesBySha }

        return BlobConsistencyReport(
            referencedBlobCount = referencesBySha.size,
            contentBlobCount = contentBlobs.size,
            quarantinedBlobCount = listQuarantinedBlobs().size,
            missingBlobs = missing,
            orphanBlobs = orphans,
            corruptBlobs = corrupt,
        )
    }

    private fun loadReferences(conn: Connection): List<BlobReference> {
        val out = ArrayList<BlobReference>()
        conn.prepareStatement(
            """
            SELECT bucket, object_key, blob_path, encode(blob_sha256, 'hex') AS sha,
                   size_bytes, encryption_mode
            FROM objects
            WHERE deleted_at IS NULL
            """.trimIndent(),
        ).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out += BlobReference(
                        kind = "object",
                        bucket = rs.getString("bucket"),
                        key = rs.getString("object_key"),
                        blobPath = rs.getString("blob_path"),
                        sha256Hex = rs.getString("sha"),
                        sizeBytes = rs.getLong("size_bytes"),
                        encryptionMode = rs.getString("encryption_mode"),
                    )
                }
            }
        }
        conn.prepareStatement(
            """
            SELECT upload_id, part_number, blob_path, encode(blob_sha256, 'hex') AS sha,
                   size_bytes, encryption_mode
            FROM multipart_parts
            """.trimIndent(),
        ).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out += BlobReference(
                        kind = "multipart_part",
                        uploadId = rs.getString("upload_id"),
                        partNumber = rs.getInt("part_number"),
                        blobPath = rs.getString("blob_path"),
                        sha256Hex = rs.getString("sha"),
                        sizeBytes = rs.getLong("size_bytes"),
                        encryptionMode = rs.getString("encryption_mode"),
                    )
                }
            }
        }
        conn.prepareStatement(
            """
            SELECT intent_id, blob_sha256_hex
            FROM blob_write_intents
            """.trimIndent(),
        ).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val sha = rs.getString("blob_sha256_hex")
                    out += BlobReference(
                        kind = "blob_write_intent",
                        uploadId = rs.getString("intent_id"),
                        blobPath = blobStore.pathFor(sha).toString(),
                        sha256Hex = sha,
                    )
                }
            }
        }
        return out
    }

    private fun validateReference(ref: BlobReference, path: Path): CorruptBlob? =
        runCatching {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var size = 0L
            blobStore.openRead(path).use { ch ->
                val buf = java.nio.ByteBuffer.allocate(64 * 1024)
                while (true) {
                    buf.clear()
                    val n = ch.read(buf)
                    if (n <= 0) break
                    size += n.toLong()
                    digest.update(buf.array(), 0, n)
                }
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            when {
                ref.sizeBytes != null && size != ref.sizeBytes ->
                    CorruptBlob(ref, path, "plaintext size mismatch expected=${ref.sizeBytes} actual=$size")
                !actualSha.equals(ref.sha256Hex, ignoreCase = true) ->
                    CorruptBlob(ref, path, "plaintext sha256 mismatch expected=${ref.sha256Hex} actual=$actualSha")
                ref.encryptionMode == ObjectEncryption.SSE_S3_MODE && !ObjectEncryption.isEncryptedBlob(path) ->
                    CorruptBlob(ref, path, "metadata requires SSE-S3 but blob is plaintext")
                else -> null
            }
        }.getOrElse { t -> CorruptBlob(ref, path, t.message ?: t.javaClass.simpleName) }

    private fun listContentBlobs(): List<OrphanBlob> {
        val objectsDir = blobStore.dataDir.resolve("objects")
        if (!Files.exists(objectsDir)) return emptyList()
        return Files.walk(objectsDir).use { stream ->
            stream
                .filter { it.isRegularFile() }
                .map { path -> OrphanBlob(path.fileName.toString(), path) }
                .toList()
        }
    }

    private fun listQuarantinedBlobs(): List<Path> {
        val quarantineDir = blobStore.dataDir.resolve(".quarantine")
        if (!Files.exists(quarantineDir)) return emptyList()
        return Files.walk(quarantineDir).use { stream ->
            stream
                .filter { it.isRegularFile() }
                .toList()
        }
    }
}
