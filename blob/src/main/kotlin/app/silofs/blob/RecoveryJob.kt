package app.silofs.blob

import app.silofs.metadata.MetadataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/**
 * Crash recovery job. Runs on a [Dispatchers.IO] coroutine and sweeps:
 *
 * 1. Orphan temp files in `<blob-dir>/.tmp/` older than [tempMaxAgeSeconds].
 * 2. Stale multipart uploads in Postgres older than [multipartMaxAgeSeconds]
 *    (their parts on disk are also removed).
 * 3. Unreferenced content-addressed blobs (every [blobSweepIntervalSeconds]).
 *
 * Every sweep is idempotent. A crash mid-sweep leaves the system in a state
 * where the next sweep will finish the work.
 */
class RecoveryJob(
    private val blobStore: BlobStore,
    private val repo: MetadataRepository,
    private val database: app.silofs.metadata.Database,
    private val tempMaxAgeSeconds: Long = 3600L,
    private val multipartMaxAgeSeconds: Long = 24 * 3600L,
    private val sweepIntervalSeconds: Long = 60L,
    private val blobSweepIntervalSeconds: Long = 600L,
    private val blobWriteIntentMaxAgeSeconds: Long = 24 * 3600L,
    private val minBlobAgeSeconds: Long = 600L,
    private val quarantineMaxAgeSeconds: Long = 3600L,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(RecoveryJob::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job =
            scope.launch {
                log.info("RecoveryJob started (interval=${sweepIntervalSeconds}s)")
                var lastBlobSweep = 0L
                while (true) {
                    try {
                        sweepTempFiles()
                        sweepStaleMultipartUploads()
                        val now = System.currentTimeMillis()
                        if (now - lastBlobSweep > blobSweepIntervalSeconds * 1000L) {
                            sweepStaleBlobWriteIntents()
                            sweepUnreferencedBlobs()
                            lastBlobSweep = now
                        }
                    } catch (t: Throwable) {
                        log.error("Recovery sweep failed", t)
                    }
                    delay(sweepIntervalSeconds * 1000L)
                }
            }
    }

    /** Visible for testing — runs one full sweep synchronously. */
    fun runOnce() {
        sweepTempFiles()
        sweepStaleMultipartUploads()
        sweepStaleBlobWriteIntents()
        sweepUnreferencedBlobs()
        sweepQuarantinedBlobs()
    }

    internal fun sweepTempFiles() {
        val orphans = blobStore.listOrphanTempFiles(tempMaxAgeSeconds)
        for (p in orphans) {
            val deleted = blobStore.deleteTemp(p)
            if (deleted) log.info("Deleted orphan temp file {}", p)
        }
    }

    internal fun sweepStaleMultipartUploads() {
        val stale = database.withConnection { c -> repo.listStaleMultipartUploads(c, multipartMaxAgeSeconds) }
        for (s in stale) {
            log.info("Aborting stale multipart upload uploadId={} bucket={} key={}", s.uploadId, s.bucket, s.key)
            // Delete part rows so the blobs become unreferenced and the next
            // sweepUnreferencedBlobs pass will GC them. The upload row is
            // marked ABORTED so we don't pick it up again.
            database.withTransaction { c ->
                repo.deleteParts(c, s.uploadId)
                repo.markMultipartAborted(c, s.uploadId)
            }
        }
        // Also recover uploads stuck in COMPLETING state (crash during
        // CompleteMultipartUpload). Do not reopen them to INITIATED: once
        // completion starts, allowing part mutation again is unsafe. Use
        // completing_at instead of initiated_at because an upload can be
        // initiated long before it enters COMPLETING.
        val stuckCompleting =
            database.withConnection { c ->
                c
                    .prepareStatement(
                        "SELECT upload_id FROM multipart_uploads " +
                            "WHERE state = 'COMPLETING' " +
                            "AND completing_at < (now() - (? || ' seconds')::interval) " +
                            "LIMIT 100",
                    ).use { ps ->
                        ps.setLong(1, 600L) // 10 min
                        ps.executeQuery().use { rs ->
                            val out = ArrayList<String>()
                            while (rs.next()) out += rs.getString(1)
                            out
                        }
                    }
            }
        for (uploadId in stuckCompleting) {
            log.warn("Marking stuck COMPLETING upload {} as FAILED_COMPLETION", uploadId)
            database.withTransaction { c -> repo.failMultipartCompletion(c, uploadId) }
        }
    }

    internal fun sweepStaleBlobWriteIntents() {
        val deleted =
            database.withTransaction { c ->
                repo.deleteStaleBlobWriteIntents(c, blobWriteIntentMaxAgeSeconds)
            }
        if (deleted > 0) {
            log.warn("Deleted {} stale blob write intents", deleted)
        }
    }

    /**
     * Two-phase mark+sweep blob GC (gap #1 fix).
     *
     * Phase 1 (mark): inside a transaction, identify unreferenced blobs and
     *   record their sha256 in `blob_gc_tombstones`. Commit.
     * Phase 2 (sweep): for each tombstone, re-check against live references.
     *   If still unreferenced, delete the file and remove the tombstone.
     *   If a new reference appeared, just remove the tombstone (don't delete).
     *
     * This is safe because:
     *   - The mark phase commits before any file is deleted. If the mark
     *     transaction aborts, no files are touched.
     *   - The sweep phase re-checks references immediately before each
     *     deletion. If a concurrent upload committed a reference between
     *     mark and sweep, the re-check sees it and skips the deletion.
     *   - Tombstones are cleaned up after processing, so the table doesn't
     *     grow unboundedly.
     *
     * Soft-deleted objects are excluded from the reference set so their blobs
     * can be reclaimed.
     */
    internal fun sweepUnreferencedBlobs() {
        val objectsDir = (blobStore as FsBlobStore).dataDir.resolve("objects")
        if (!Files.exists(objectsDir)) return

        val allBlobs: List<Path> =
            Files.walk(objectsDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .filter { isOlderThan(it, minBlobAgeSeconds) }
                    .toList()
            }
        if (allBlobs.isEmpty()) return

        val batchSize = 1000
        // ---- Phase 1: mark unreferenced blobs ----
        for (i in allBlobs.indices step batchSize) {
            val chunk = allBlobs.subList(i, minOf(i + batchSize, allBlobs.size))
            val names = chunk.map { it.fileName.toString() }
            try {
                database.withTransaction { conn ->
                    val used = lookupReferencedSha256s(conn, names)
                    val toMark = names.filter { it !in used }
                    for (sha in toMark) {
                        conn
                            .prepareStatement(
                                "INSERT INTO blob_gc_tombstones (blob_sha256_hex) VALUES (?) " +
                                    "ON CONFLICT (blob_sha256_hex) DO NOTHING",
                            ).use { ps ->
                                ps.setString(1, sha)
                                ps.executeUpdate()
                            }
                    }
                }
            } catch (e: Exception) {
                log.warn("Blob GC mark phase failed for batch (will retry next sweep): {}", e.message)
            }
        }

        // ---- Phase 2: sweep — re-check and delete ----
        var quarantined = 0
        var skipped = 0
        val tombstones =
            database.withConnection { conn ->
                conn
                    .prepareStatement(
                        "SELECT blob_sha256_hex FROM blob_gc_tombstones ORDER BY marked_at LIMIT 1000",
                    ).use { ps ->
                        ps.executeQuery().use { rs ->
                            val out = ArrayList<String>()
                            while (rs.next()) out += rs.getString(1)
                            out
                        }
                    }
            }

        for (sha in tombstones) {
            // Re-check: is this blob now referenced?
            val stillUnreferenced =
                database.withConnection { conn ->
                    lookupReferencedSha256s(conn, listOf(sha)).isEmpty()
                }
            if (stillUnreferenced) {
                // Find the blob file by sha256 (sharded path). Move it to
                // quarantine first; final deletion happens after another DB
                // reference check in sweepQuarantinedBlobs.
                val blobPath = blobStore.pathFor(sha)
                if (blobStore.quarantine(blobPath, sha) != null) quarantined++
            } else {
                // A new reference appeared between mark and sweep — skip.
                skipped++
            }
            // Remove the tombstone regardless.
            database.withConnection { conn ->
                conn
                    .prepareStatement(
                        "DELETE FROM blob_gc_tombstones WHERE blob_sha256_hex = ?",
                    ).use { ps ->
                        ps.setString(1, sha)
                        ps.executeUpdate()
                    }
            }
        }

        if (quarantined > 0 || skipped > 0) {
            log.info("Blob GC: quarantined {} blobs, skipped {} (newly referenced)", quarantined, skipped)
        }
    }

    internal fun sweepQuarantinedBlobs() {
        val candidates = (blobStore as FsBlobStore).listQuarantinedBlobs(quarantineMaxAgeSeconds)
        var deleted = 0
        var restored = 0
        for (path in candidates) {
            val sha = path.fileName.toString()
            val stillUnreferenced =
                database.withConnection { conn ->
                    lookupReferencedSha256s(conn, listOf(sha)).isEmpty()
                }
            if (stillUnreferenced) {
                if (blobStore.deleteQuarantined(path)) deleted++
            } else {
                if (blobStore.restoreQuarantined(path) != null) restored++
            }
        }
        if (deleted > 0 || restored > 0) {
            log.info("Blob GC quarantine: deleted {} blobs, restored {} newly referenced blobs", deleted, restored)
        }
    }

    private fun lookupReferencedSha256s(
        conn: Connection,
        hexes: List<String>,
    ): Set<String> {
        if (hexes.isEmpty()) return emptySet()
        val placeholders = hexes.joinToString(",") { "?" }
        // Live objects, live multipart parts, and active blob write intents
        // count as references. Soft-deleted object blobs can be reclaimed.
        val sql =
            "SELECT encode(blob_sha256, 'hex') AS h FROM objects " +
                "WHERE encode(blob_sha256, 'hex') IN ($placeholders) AND deleted_at IS NULL AND is_delete_marker = FALSE " +
                "UNION " +
                "SELECT encode(blob_sha256, 'hex') AS h FROM multipart_parts " +
                "WHERE encode(blob_sha256, 'hex') IN ($placeholders) " +
                "UNION " +
                "SELECT blob_sha256_hex AS h FROM blob_write_intents " +
                "WHERE blob_sha256_hex IN ($placeholders)"
        return conn.prepareStatement(sql).use { ps ->
            var idx = 1
            for (h in hexes) ps.setString(idx++, h)
            for (h in hexes) ps.setString(idx++, h)
            for (h in hexes) ps.setString(idx++, h)
            ps.executeQuery().use { rs ->
                val out = HashSet<String>()
                while (rs.next()) out += rs.getString("h")
                out
            }
        }
    }

    private fun isOlderThan(
        path: Path,
        ageSeconds: Long,
    ): Boolean {
        if (ageSeconds <= 0) return true
        val ageMillis = ageSeconds.coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L
        val cutoff = System.currentTimeMillis() - ageMillis
        return runCatching { Files.getLastModifiedTime(path).toMillis() < cutoff }.getOrDefault(false)
    }

    override fun close() {
        scope.cancel()
        job = null
        log.info("RecoveryJob stopped")
    }
}
