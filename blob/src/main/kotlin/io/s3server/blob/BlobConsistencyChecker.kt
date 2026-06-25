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

data class BlobConsistencyReport(
    val referencedBlobCount: Int,
    val contentBlobCount: Int,
    val quarantinedBlobCount: Int,
    val missingBlobs: List<MissingBlob>,
    val orphanBlobs: List<OrphanBlob>,
) {
    val isConsistent: Boolean
        get() = missingBlobs.isEmpty()
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
        val orphans =
            contentBlobs
                .filter { blob -> blob.sha256Hex !in referencesBySha }

        return BlobConsistencyReport(
            referencedBlobCount = referencesBySha.size,
            contentBlobCount = contentBlobs.size,
            quarantinedBlobCount = listQuarantinedBlobs().size,
            missingBlobs = missing,
            orphanBlobs = orphans,
        )
    }

    private fun loadReferences(conn: Connection): List<BlobReference> {
        val out = ArrayList<BlobReference>()
        conn.prepareStatement(
            """
            SELECT bucket, object_key, blob_path, encode(blob_sha256, 'hex') AS sha
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
                    )
                }
            }
        }
        conn.prepareStatement(
            """
            SELECT upload_id, part_number, blob_path, encode(blob_sha256, 'hex') AS sha
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
