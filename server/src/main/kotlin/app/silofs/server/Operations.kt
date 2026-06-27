package app.silofs.server

import app.silofs.blob.BlobConsistencyReport
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
    val inFlightRequests: Int = 0,
    val inFlightUploads: Int = 0,
    val inFlightMultipartCompletions: Int = 0,
    val rejectedRequests: Long = 0,
    val rejectedRateLimitedRequests: Long = 0,
    val rejectedUploads: Long = 0,
    val rejectedMultipartCompletions: Long = 0,
    val dbPoolActiveConnections: Int? = null,
    val dbPoolIdleConnections: Int? = null,
    val dbPoolTotalConnections: Int? = null,
    val dbPoolThreadsAwaitingConnection: Int? = null,
    val recoverySweeps: Long = 0,
    val recoverySweepFailures: Long = 0,
    val blobStoreErrors: Long = 0,
    val requestMetrics: List<RequestMetricSample> = emptyList(),
)

internal fun collectReadiness(config: ServerConfig): List<ReadinessCheck> =
    listOf(checkDatabase(config), checkDataDirectory(config), checkDiskSpace(config))

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

private fun checkDiskSpace(config: ServerConfig): ReadinessCheck =
    runCatching {
        Files.createDirectories(config.dataDir)
        val usable = Files.getFileStore(config.dataDir).usableSpace
        val required = config.operationalConfig.minFreeDiskBytes
        if (usable < required) {
            ReadinessCheck("disk_space", ok = false, "usable=$usable required=$required")
        } else {
            ReadinessCheck("disk_space", ok = true, "usable=$usable required=$required")
        }
    }.getOrElse { t ->
        ReadinessCheck("disk_space", ok = false, t.message ?: t.javaClass.simpleName)
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
    val poolStats = config.database.poolStats()
    return MetricsSnapshot(
        activeMultipartUploads = activeMultipartUploads,
        orphanTempFiles = orphanTempFiles,
        quarantinedBlobs = quarantinedBlobs,
        blobDiskBytes = diskUsageBytes(config.dataDir),
        inFlightRequests = config.operationalState.inFlightRequests,
        inFlightUploads = config.operationalState.inFlightUploads,
        inFlightMultipartCompletions = config.operationalState.inFlightMultipartCompletions,
        rejectedRequests = config.operationalState.rejectedRequests,
        rejectedRateLimitedRequests = config.operationalState.rejectedRateLimitedRequests,
        rejectedUploads = config.operationalState.rejectedUploads,
        rejectedMultipartCompletions = config.operationalState.rejectedMultipartCompletions,
        dbPoolActiveConnections = poolStats?.activeConnections,
        dbPoolIdleConnections = poolStats?.idleConnections,
        dbPoolTotalConnections = poolStats?.totalConnections,
        dbPoolThreadsAwaitingConnection = poolStats?.threadsAwaitingConnection,
        recoverySweeps = config.operationalState.recoverySweeps,
        recoverySweepFailures = config.operationalState.recoverySweepFailures,
        blobStoreErrors = config.operationalState.blobStoreErrors,
        requestMetrics = config.requestMetrics.snapshot(),
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
        appendOperationalMetrics(snapshot)
        renderRequestMetrics(snapshot.requestMetrics)
    }

private fun StringBuilder.appendOperationalMetrics(snapshot: MetricsSnapshot) {
    append("# HELP silofs_inflight_requests Requests currently executing inside the server.\n")
    append("# TYPE silofs_inflight_requests gauge\n")
    append("silofs_inflight_requests ").append(snapshot.inFlightRequests).append('\n')
    append("# HELP silofs_inflight_uploads Object or part uploads currently publishing blob data.\n")
    append("# TYPE silofs_inflight_uploads gauge\n")
    append("silofs_inflight_uploads ").append(snapshot.inFlightUploads).append('\n')
    append("# HELP silofs_inflight_multipart_completions Multipart completions currently materialising final objects.\n")
    append("# TYPE silofs_inflight_multipart_completions gauge\n")
    append("silofs_inflight_multipart_completions ").append(snapshot.inFlightMultipartCompletions).append('\n')
    append("# HELP silofs_rejected_requests_total Requests rejected because the server is draining.\n")
    append("# TYPE silofs_rejected_requests_total counter\n")
    append("silofs_rejected_requests_total ").append(snapshot.rejectedRequests).append('\n')
    append("# HELP silofs_rejected_rate_limited_requests_total Requests rejected by the per-access-key rate limiter.\n")
    append("# TYPE silofs_rejected_rate_limited_requests_total counter\n")
    append("silofs_rejected_rate_limited_requests_total ").append(snapshot.rejectedRateLimitedRequests).append('\n')
    append("# HELP silofs_rejected_uploads_total Upload requests rejected by the upload concurrency limiter.\n")
    append("# TYPE silofs_rejected_uploads_total counter\n")
    append("silofs_rejected_uploads_total ").append(snapshot.rejectedUploads).append('\n')
    append("# HELP silofs_rejected_multipart_completions_total CompleteMultipartUpload requests rejected by the completion limiter.\n")
    append("# TYPE silofs_rejected_multipart_completions_total counter\n")
    append("silofs_rejected_multipart_completions_total ").append(snapshot.rejectedMultipartCompletions).append('\n')
    append("# HELP silofs_recovery_sweeps_total Recovery sweeps completed by the background/admin recovery runner.\n")
    append("# TYPE silofs_recovery_sweeps_total counter\n")
    append("silofs_recovery_sweeps_total ").append(snapshot.recoverySweeps).append('\n')
    append("# HELP silofs_recovery_sweep_failures_total Recovery sweeps that failed.\n")
    append("# TYPE silofs_recovery_sweep_failures_total counter\n")
    append("silofs_recovery_sweep_failures_total ").append(snapshot.recoverySweepFailures).append('\n')
    append("# HELP silofs_blob_store_errors_total Blob-store publish/read/delete failures observed by request handlers.\n")
    append("# TYPE silofs_blob_store_errors_total counter\n")
    append("silofs_blob_store_errors_total ").append(snapshot.blobStoreErrors).append('\n')
    appendDbGauge("silofs_db_pool_active_connections", "Active Hikari database connections.", snapshot.dbPoolActiveConnections)
    appendDbGauge("silofs_db_pool_idle_connections", "Idle Hikari database connections.", snapshot.dbPoolIdleConnections)
    appendDbGauge("silofs_db_pool_total_connections", "Total Hikari database connections.", snapshot.dbPoolTotalConnections)
    appendDbGauge(
        "silofs_db_pool_threads_awaiting_connection",
        "Threads currently waiting for a Hikari database connection.",
        snapshot.dbPoolThreadsAwaitingConnection,
    )
}

private fun StringBuilder.appendDbGauge(
    name: String,
    help: String,
    value: Int?,
) {
    if (value == null) return
    append("# HELP ")
        .append(name)
        .append(' ')
        .append(help)
        .append('\n')
    append("# TYPE ").append(name).append(" gauge\n")
    append(name).append(' ').append(value).append('\n')
}

internal fun renderBlobConsistencyReport(report: BlobConsistencyReport): String =
    buildString {
        append("consistent=").append(report.isConsistent).append('\n')
        append("referenced_blobs=").append(report.referencedBlobCount).append('\n')
        append("content_blobs=").append(report.contentBlobCount).append('\n')
        append("quarantined_blobs=").append(report.quarantinedBlobCount).append('\n')
        append("missing_blobs=").append(report.missingBlobs.size).append('\n')
        for (missing in report.missingBlobs) {
            append("missing ")
            append("kind=").append(missing.reference.kind)
            missing.reference.bucket?.let { append(" bucket=").append(it) }
            missing.reference.key?.let { append(" key=").append(it) }
            missing.reference.uploadId?.let { append(" uploadId=").append(it) }
            missing.reference.partNumber?.let { append(" partNumber=").append(it) }
            append(" sha256=").append(missing.reference.sha256Hex)
            append(" expectedPath=").append(missing.expectedPath)
            append('\n')
        }
        append("corrupt_blobs=").append(report.corruptBlobs.size).append('\n')
        for (corrupt in report.corruptBlobs) {
            append("corrupt ")
            append("kind=").append(corrupt.reference.kind)
            corrupt.reference.bucket?.let { append(" bucket=").append(it) }
            corrupt.reference.key?.let { append(" key=").append(it) }
            corrupt.reference.uploadId?.let { append(" uploadId=").append(it) }
            corrupt.reference.partNumber?.let { append(" partNumber=").append(it) }
            append(" sha256=").append(corrupt.reference.sha256Hex)
            append(" path=").append(corrupt.path)
            append(" reason=").append(corrupt.reason.replace('\n', ' '))
            append('\n')
        }
        append("orphan_blobs=").append(report.orphanBlobs.size).append('\n')
        for (orphan in report.orphanBlobs) {
            append("orphan sha256=").append(orphan.sha256Hex)
            append(" path=").append(orphan.path)
            append('\n')
        }
    }

private fun StringBuilder.renderRequestMetrics(samples: List<RequestMetricSample>) {
    append("# HELP silofs_http_requests_total HTTP requests by S3 operation and status.\n")
    append("# TYPE silofs_http_requests_total counter\n")
    append("# HELP silofs_http_request_bytes_total HTTP request bytes by S3 operation and status.\n")
    append("# TYPE silofs_http_request_bytes_total counter\n")
    append("# HELP silofs_http_response_bytes_total HTTP response bytes by S3 operation and status.\n")
    append("# TYPE silofs_http_response_bytes_total counter\n")
    append("# HELP silofs_http_request_duration_seconds HTTP request latency by S3 operation and status.\n")
    append("# TYPE silofs_http_request_duration_seconds histogram\n")

    for (sample in samples) {
        val labels = "operation=\"${sample.operation}\",status=\"${sample.status}\""
        append("silofs_http_requests_total{")
            .append(labels)
            .append("} ")
            .append(sample.count)
            .append('\n')
        append("silofs_http_request_bytes_total{")
            .append(labels)
            .append("} ")
            .append(sample.requestBytes)
            .append('\n')
        append("silofs_http_response_bytes_total{")
            .append(labels)
            .append("} ")
            .append(sample.responseBytes)
            .append('\n')
        REQUEST_LATENCY_BUCKET_SECONDS.forEachIndexed { index, upperBound ->
            append("silofs_http_request_duration_seconds_bucket{")
                .append(labels)
                .append(",le=\"")
                .append(formatBucket(upperBound))
                .append("\"} ")
                .append(sample.latencyBuckets.getOrElse(index) { 0L })
                .append('\n')
        }
        append("silofs_http_request_duration_seconds_bucket{")
            .append(labels)
            .append(",le=\"+Inf\"} ")
            .append(sample.latencyBuckets.getOrElse(REQUEST_LATENCY_BUCKET_SECONDS.size) { sample.count })
            .append('\n')
        append("silofs_http_request_duration_seconds_sum{")
            .append(labels)
            .append("} ")
            .append(sample.latencyNanos.toDouble() / 1_000_000_000.0)
            .append('\n')
        append("silofs_http_request_duration_seconds_count{")
            .append(labels)
            .append("} ")
            .append(sample.count)
            .append('\n')
    }
}

private fun formatBucket(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

private fun diskUsageBytes(root: Path): Long {
    if (!Files.exists(root)) return 0L
    return Files.walk(root).use { stream ->
        stream
            .filter { it.isRegularFile() }
            .mapToLong { Files.size(it) }
            .sum()
    }
}
