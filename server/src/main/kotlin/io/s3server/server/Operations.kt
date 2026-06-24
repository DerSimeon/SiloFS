package app.silofs.server

import app.silofs.blob.FsBlobStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

internal data class ReadinessCheck(
    val name: String,
    val ok: Boolean,
    val message: String,
)

internal data class MetricsSnapshot(
    val activeMultipartUploads: Int,
    val orphanTempFiles: Int,
    val quarantinedBlobs: Int,
    val blobDiskBytes: Long,
)

internal fun collectReadiness(config: ServerConfig): List<ReadinessCheck> = listOf(checkDatabase(config), checkDataDirectory(config))

private fun checkDatabase(config: ServerConfig): ReadinessCheck =
    runCatching {
        config.database.withConnection { conn ->
            conn.prepareStatement("SELECT 1").use { ps ->
                ps.executeQuery().use { rs ->
                    if (!rs.next() || rs.getInt(1) != 1) error("unexpected probe result")
                }
            }
        }
        ReadinessCheck("database", ok = true, "ok")
    }.getOrElse { t ->
        ReadinessCheck("database", ok = false, t.message ?: t.javaClass.simpleName)
    }

private fun checkDataDirectory(config: ServerConfig): ReadinessCheck =
    runCatching {
        Files.createDirectories(config.dataDir)
        val probe = Files.createTempFile(config.dataDir, ".readyz-", ".tmp")
        Files.deleteIfExists(probe)
        ReadinessCheck("data_dir", ok = true, "ok")
    }.getOrElse { t ->
        ReadinessCheck("data_dir", ok = false, t.message ?: t.javaClass.simpleName)
    }

internal fun renderReadiness(checks: List<ReadinessCheck>): String =
    buildString {
        append(if (checks.all { it.ok }) "ready" else "not ready")
        append('\n')
        for (check in checks) {
            append(check.name)
            append('=')
            append(if (check.ok) "ok" else "fail")
            if (!check.ok) {
                append(' ')
                append(check.message.replace('\n', ' '))
            }
            append('\n')
        }
    }

internal fun collectMetrics(config: ServerConfig): MetricsSnapshot {
    val activeMultipartUploads =
        config.database.withConnection { conn ->
            config.repository.listMultipartUploads(conn, bucket = null).size
        }
    val orphanTempFiles = config.blobStore.listOrphanTempFiles(olderThanSeconds = 0).size
    val quarantinedBlobs =
        (config.blobStore as? FsBlobStore)
            ?.listQuarantinedBlobs(olderThanSeconds = 0)
            ?.size
            ?: 0
    return MetricsSnapshot(
        activeMultipartUploads = activeMultipartUploads,
        orphanTempFiles = orphanTempFiles,
        quarantinedBlobs = quarantinedBlobs,
        blobDiskBytes = diskUsageBytes(config.dataDir),
    )
}

internal fun renderMetrics(snapshot: MetricsSnapshot): String =
    buildString {
        append("# HELP silofs_active_multipart_uploads Active multipart uploads visible to clients.\n")
        append("# TYPE silofs_active_multipart_uploads gauge\n")
        append("silofs_active_multipart_uploads ").append(snapshot.activeMultipartUploads).append('\n')
        append("# HELP silofs_orphan_temp_files Temp files currently eligible for recovery cleanup.\n")
        append("# TYPE silofs_orphan_temp_files gauge\n")
        append("silofs_orphan_temp_files ").append(snapshot.orphanTempFiles).append('\n')
        append("# HELP silofs_quarantined_blobs Blobs waiting for final GC deletion or restore.\n")
        append("# TYPE silofs_quarantined_blobs gauge\n")
        append("silofs_quarantined_blobs ").append(snapshot.quarantinedBlobs).append('\n')
        append("# HELP silofs_blob_disk_bytes Bytes used under the configured blob data directory.\n")
        append("# TYPE silofs_blob_disk_bytes gauge\n")
        append("silofs_blob_disk_bytes ").append(snapshot.blobDiskBytes).append('\n')
    }

private fun diskUsageBytes(root: Path): Long {
    if (!Files.exists(root)) return 0L
    return Files.walk(root).use { stream ->
        stream
            .filter { it.isRegularFile() }
            .mapToLong { Files.size(it) }
            .sum()
    }
}
