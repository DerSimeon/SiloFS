package app.silofs.metadata

import app.silofs.common.BucketInfo
import app.silofs.common.ListObject
import app.silofs.common.ListObjectsV2Result
import app.silofs.common.ObjectMetadata
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

private fun staleCutoff(olderThanSeconds: Long): Timestamp? {
    val now = Instant.now()
    if (olderThanSeconds >= now.epochSecond) return null
    return Timestamp.from(now.minusSeconds(olderThanSeconds))
}

/**
 * The metadata repository interface. Implementations are JDBC-based.
 *
 * All methods are blocking; the server wraps them in `withContext(Dispatchers.IO)`
 * at the boundary. This keeps the repository trivially testable.
 */
interface MetadataRepository {

    // Buckets
    fun createBucket(conn: Connection, name: String, region: String, ownerId: String)
    fun bucketExists(conn: Connection, name: String): Boolean
    fun getBucket(conn: Connection, name: String): BucketInfo?
    fun listBuckets(conn: Connection): List<BucketInfo>
    fun deleteBucket(conn: Connection, name: String): Boolean
    fun countObjectsInBucket(conn: Connection, name: String): Long

    // Objects
    fun putObject(conn: Connection, meta: ObjectMetadata): ObjectMetadata
    fun getObject(conn: Connection, bucket: String, key: String): ObjectMetadata?
    fun deleteObject(conn: Connection, bucket: String, key: String): Boolean

    /**
     * M2.1: extended listObjects with [prefix], [delimiter], and [encodingType].
     *
     *   - [prefix] filters keys that start with it; the prefix itself is not
     *     returned as a CommonPrefix.
     *   - [delimiter] rolls up keys that share the substring up to and including
     *     the first occurrence of the delimiter after the prefix into a single
     *     CommonPrefix entry. The default delimiter `/` produces the familiar
     *     "directory" grouping.
     *   - [encodingType] = `"url"` causes keys and common prefixes to be
     *     URL-encoded in the result (the caller is responsible for emitting
     *     them verbatim in the XML response).
     */
    fun listObjects(
        conn: Connection,
        bucket: String,
        maxKeys: Int,
        continuationToken: String?,
        startAfter: String?,
        prefix: String? = null,
        delimiter: String? = null,
        encodingType: String? = null
    ): ListObjectsV2Result

    // Multipart uploads (M3)
    fun createMultipartUpload(
        conn: Connection,
        uploadId: String,
        bucket: String,
        key: String,
        contentType: String,
        userMetadata: Map<String, String>,
        storageClass: String,
        contentEncoding: String?,
        contentLanguage: String?,
        cacheControl: String?,
        contentDisposition: String?,
        expires: String?,
        checksumAlgorithm: String?
    )

    /** Returns the multipart upload row if it exists and is active (INITIATED or COMPLETING). */
    fun getMultipartUpload(conn: Connection, uploadId: String): MultipartUploadInfo?

    /** Upserts a part row. Re-uploading the same part number overwrites the previous one. */
    fun uploadPart(
        conn: Connection,
        uploadId: String,
        partNumber: Int,
        blobPath: String,
        blobSha256Hex: String,
        etag: String,
        sizeBytes: Long,
        checksumCrc32: String? = null,
        checksumCrc32C: String? = null,
        checksumSha1: String? = null,
        checksumSha256: String? = null
    ): PartInfo

    /** Returns a single part row, or null if not uploaded. */
    fun getPart(conn: Connection, uploadId: String, partNumber: Int): PartInfo?

    /** Returns all parts for an upload, ordered by part_number ASC. */
    fun listParts(conn: Connection, uploadId: String): List<PartInfo>

    /** Deletes all part rows for an upload. Used during abort + complete. */
    fun deleteParts(conn: Connection, uploadId: String): Int

    /** Marks the upload as COMPLETED. The parts rows should already be deleted. */
    fun completeMultipartUpload(conn: Connection, uploadId: String): Boolean

    /**
     * Atomically transitions an upload from INITIATED to COMPLETING.
     * Returns true if the transition succeeded (the upload was INITIATED),
     * false otherwise. This closes the race window where a concurrent
     * UploadPart could add/replace parts while CompleteMultipartUpload
     * is reading them.
     *
     * If the transition succeeds, no concurrent UploadPart will be able
     * to write parts (they check for INITIATED state). The caller must
     * either commit the completion or move the upload to FAILED_COMPLETION
     * for unsafe failures.
     */
    fun markMultipartCompleting(conn: Connection, uploadId: String): Boolean

    fun abortMultipartUpload(conn: Connection, uploadId: String): Boolean
    fun failMultipartCompletion(conn: Connection, uploadId: String): Boolean
    fun listStaleMultipartUploads(conn: Connection, olderThanSeconds: Long): List<StaleMultipart>
    fun markMultipartAborted(conn: Connection, uploadId: String)

    /** Returns all multipart uploads for a bucket (used by ListMultipartUploads). */
    fun listMultipartUploads(conn: Connection, bucket: String?): List<MultipartUploadInfo>

    // Blob write intents protect filesystem blobs between publish and metadata commit.
    fun createBlobWriteIntent(conn: Connection, blobSha256Hex: String): String
    fun clearBlobWriteIntent(conn: Connection, intentId: String): Boolean
    fun deleteStaleBlobWriteIntents(conn: Connection, olderThanSeconds: Long): Int

    // Access keys
    fun upsertAccessKey(conn: Connection, accessKeyId: String, secretAccessKey: String, description: String?)
    fun lookupSecret(conn: Connection, accessKeyId: String): String?
    fun upsertAccessKeyRecord(conn: Connection, record: AccessKeyRecord)
    fun lookupAccessKey(conn: Connection, accessKeyId: String): AccessKeyRecord?
    fun listAccessKeys(conn: Connection, includeDeleted: Boolean = false): List<AccessKeyRecord>
    fun updateAccessKeyState(conn: Connection, accessKeyId: String, state: String): Boolean
    fun softDeleteAccessKey(conn: Connection, accessKeyId: String): Boolean
    fun insertAuditEvent(conn: Connection, event: AuditEventRecord)
    fun listAuditEvents(conn: Connection, limit: Int = 100): List<AuditEventRecord>
}

data class StaleMultipart(val uploadId: String, val bucket: String, val key: String, val initiatedAt: Instant)

data class AccessKeyRecord(
    val accessKeyId: String,
    val secretAccessKey: String?,
    val secretCiphertext: ByteArray? = null,
    val secretNonce: ByteArray? = null,
    val secretKeyId: String? = null,
    val description: String? = null,
    val state: String = "ACTIVE",
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val disabledAt: Instant? = null,
    val deletedAt: Instant? = null,
    val rotatedAt: Instant? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AccessKeyRecord) return false
        return accessKeyId == other.accessKeyId &&
            secretAccessKey == other.secretAccessKey &&
            secretCiphertext.contentEqualsNullable(other.secretCiphertext) &&
            secretNonce.contentEqualsNullable(other.secretNonce) &&
            secretKeyId == other.secretKeyId &&
            description == other.description &&
            state == other.state &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt &&
            disabledAt == other.disabledAt &&
            deletedAt == other.deletedAt &&
            rotatedAt == other.rotatedAt
    }

    override fun hashCode(): Int {
        var result = accessKeyId.hashCode()
        result = 31 * result + (secretAccessKey?.hashCode() ?: 0)
        result = 31 * result + (secretCiphertext?.contentHashCode() ?: 0)
        result = 31 * result + (secretNonce?.contentHashCode() ?: 0)
        result = 31 * result + (secretKeyId?.hashCode() ?: 0)
        result = 31 * result + (description?.hashCode() ?: 0)
        result = 31 * result + state.hashCode()
        result = 31 * result + (createdAt?.hashCode() ?: 0)
        result = 31 * result + (updatedAt?.hashCode() ?: 0)
        result = 31 * result + (disabledAt?.hashCode() ?: 0)
        result = 31 * result + (deletedAt?.hashCode() ?: 0)
        result = 31 * result + (rotatedAt?.hashCode() ?: 0)
        return result
    }
}

data class AuditEventRecord(
    val eventId: UUID = UUID.randomUUID(),
    val occurredAt: Instant? = null,
    val requestId: String?,
    val accessKeyId: String?,
    val operation: String,
    val bucket: String? = null,
    val objectKey: String? = null,
    val status: Int? = null,
    val latencyMs: Long? = null,
    val source: String = "s3",
    val detailJson: String = "{}",
)

/** Full info about a multipart upload, used by CreateMultipartUpload and ListMultipartUploads. */
data class MultipartUploadInfo(
    val uploadId: String,
    val bucket: String,
    val key: String,
    val contentType: String,
    val userMetadata: Map<String, String>,
    val storageClass: String,
    val state: String,
    val initiatedAt: Instant,
    val contentEncoding: String? = null,
    val contentLanguage: String? = null,
    val cacheControl: String? = null,
    val contentDisposition: String? = null,
    val expires: String? = null,
    val checksumAlgorithm: String? = null
)

/** Info about a single uploaded part, used by UploadPart response and ListParts. */
data class PartInfo(
    val partNumber: Int,
    val blobPath: String,
    val blobSha256Hex: String,
    val etag: String,
    val sizeBytes: Long,
    val uploadedAt: Instant,
    val checksumCrc32: String? = null,
    val checksumCrc32C: String? = null,
    val checksumSha1: String? = null,
    val checksumSha256: String? = null
)

/**
 * Plain JDBC implementation. SQL is hand-written so we have full control over
 * the queries and can run them through EXPLAIN later if needed.
 */
class JdbcMetadataRepository : MetadataRepository {

    override fun createBucket(conn: Connection, name: String, region: String, ownerId: String) {
        conn.prepareStatement(
            "INSERT INTO buckets(name, region, owner_id) VALUES (?, ?, ?) " +
                "ON CONFLICT (name) DO NOTHING"
        ).use { ps ->
            ps.setString(1, name)
            ps.setString(2, region)
            ps.setString(3, ownerId)
            ps.executeUpdate()
        }
    }

    override fun bucketExists(conn: Connection, name: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM buckets WHERE name = ? AND deleted_at IS NULL").use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { it.next() }
        }

    override fun getBucket(conn: Connection, name: String): BucketInfo? =
        conn.prepareStatement(
            "SELECT name, region, owner_id, created_at FROM buckets WHERE name = ? AND deleted_at IS NULL"
        ).use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null else BucketInfo(
                    name = rs.getString("name"),
                    region = rs.getString("region"),
                    ownerId = rs.getString("owner_id"),
                    createdAt = rs.getTimestamp("created_at").toInstant()
                )
            }
        }

    override fun listBuckets(conn: Connection): List<BucketInfo> =
        conn.prepareStatement(
            "SELECT name, region, owner_id, created_at FROM buckets WHERE deleted_at IS NULL ORDER BY created_at"
        ).use { ps ->
            ps.executeQuery().use { rs ->
                val out = ArrayList<BucketInfo>()
                while (rs.next()) {
                    out += BucketInfo(
                        name = rs.getString("name"),
                        region = rs.getString("region"),
                        ownerId = rs.getString("owner_id"),
                        createdAt = rs.getTimestamp("created_at").toInstant()
                    )
                }
                out
            }
        }

    /**
     * Soft-deletes the bucket row. The caller MUST first check
     * [countObjectsInBucket] == 0 and throw [BucketNotEmpty] otherwise —
     * we do not enforce that here so the check can run in the same
     * transaction as the delete.
     */
    override fun deleteBucket(conn: Connection, name: String): Boolean =
        conn.prepareStatement(
            "UPDATE buckets SET deleted_at = now() WHERE name = ? AND deleted_at IS NULL"
        ).use { ps ->
            ps.setString(1, name)
            ps.executeUpdate() > 0
        }

    override fun countObjectsInBucket(conn: Connection, name: String): Long =
        conn.prepareStatement(
            "SELECT COUNT(*) FROM objects WHERE bucket = ? AND deleted_at IS NULL"
        ).use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }

    override fun putObject(conn: Connection, meta: ObjectMetadata): ObjectMetadata {
        // Upsert on (bucket, object_key, version_id). For M1 version_id is always
        // 'null', so this is effectively a single-row-per-key upsert.
        val sql = """
            INSERT INTO objects(
                bucket, object_key, blob_path, blob_sha256, etag, size_bytes,
                content_type, content_encoding, content_language, cache_control,
                content_disposition, expires, user_metadata, version_id, is_latest,
                storage_class, created_at,
                checksum_crc32, checksum_crc32c, checksum_sha1, checksum_sha256, checksum_type
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, TRUE, ?, now(), ?, ?, ?, ?, ?)
            ON CONFLICT (bucket, object_key, version_id) DO UPDATE SET
                blob_path = EXCLUDED.blob_path,
                blob_sha256 = EXCLUDED.blob_sha256,
                etag = EXCLUDED.etag,
                size_bytes = EXCLUDED.size_bytes,
                content_type = EXCLUDED.content_type,
                content_encoding = EXCLUDED.content_encoding,
                content_language = EXCLUDED.content_language,
                cache_control = EXCLUDED.cache_control,
                content_disposition = EXCLUDED.content_disposition,
                expires = EXCLUDED.expires,
                user_metadata = EXCLUDED.user_metadata,
                storage_class = EXCLUDED.storage_class,
                created_at = now(),
                deleted_at = NULL,
                checksum_crc32 = EXCLUDED.checksum_crc32,
                checksum_crc32c = EXCLUDED.checksum_crc32c,
                checksum_sha1 = EXCLUDED.checksum_sha1,
                checksum_sha256 = EXCLUDED.checksum_sha256,
                checksum_type = EXCLUDED.checksum_type
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            ps.applyObjectMeta(meta)
            ps.executeUpdate()
        }
        return meta.copy(createdAt = Instant.now())
    }

    override fun getObject(conn: Connection, bucket: String, key: String): ObjectMetadata? {
        val sql = """
            SELECT bucket, object_key, blob_path, blob_sha256, etag, size_bytes,
                   content_type, content_encoding, content_language, cache_control,
                   content_disposition, expires, user_metadata, version_id, storage_class,
                   created_at,
                   checksum_crc32, checksum_crc32c, checksum_sha1, checksum_sha256, checksum_type
            FROM objects
            WHERE bucket = ? AND object_key = ? AND deleted_at IS NULL
        """.trimIndent()
        return conn.prepareStatement(sql).use { ps ->
            ps.setString(1, bucket)
            ps.setString(2, key)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null else rs.toObjectMeta()
            }
        }
    }

    override fun deleteObject(conn: Connection, bucket: String, key: String): Boolean {
        // Soft-delete keeps the row for the (future) versioning milestone.
        return conn.prepareStatement(
            "UPDATE objects SET deleted_at = now() WHERE bucket = ? AND object_key = ? AND deleted_at IS NULL"
        ).use { ps ->
            ps.setString(1, bucket)
            ps.setString(2, key)
            ps.executeUpdate() > 0
        }
    }

    override fun listObjects(
        conn: Connection,
        bucket: String,
        maxKeys: Int,
        continuationToken: String?,
        startAfter: String?,
        prefix: String?,
        delimiter: String?,
        encodingType: String?
    ): ListObjectsV2Result {
        val effectiveMax = maxKeys.coerceIn(1, 1000)
        // The continuation token is the last *raw* key (or common prefix) we
        // returned. When the last entry was a CommonPrefix, the token is the
        // prefix string itself; when it was a regular Content, the token is
        // the key. We compare against `object_key > ?` for both, which works
        // because prefixes sort lexicographically with the keys they contain.
        val startAfterKey = continuationToken ?: startAfter

        // We fetch up to effectiveMax + 1 rows past the cursor to detect
        // truncation. When a delimiter is set we need to fetch many more rows
        // from the DB than we'll return to the client, because multiple DB
        // rows may collapse into a single CommonPrefix. We fetch a generous
        // upper bound of (effectiveMax + 1) * 50 rows; the in-memory grouping
        // then trims this down. This is acceptable for the single-node use
        // case; a true pagination cursor that survives delimiter grouping
        // would require storing the grouping state server-side.
        val fetchLimit = if (delimiter != null) (effectiveMax + 1) * 50 else effectiveMax + 1

        val sql = buildString {
            append("SELECT object_key, etag, size_bytes, created_at, storage_class ")
            append("FROM objects ")
            append("WHERE bucket = ? AND deleted_at IS NULL ")
            if (prefix != null) append("AND object_key LIKE ? ESCAPE '\\' ")
            if (startAfterKey != null) append("AND object_key > ? ")
            append("ORDER BY object_key ASC ")
            append("LIMIT ?")
        }

        val rawRows: List<ListObject> = conn.prepareStatement(sql).use { ps ->
            var idx = 1
            ps.setString(idx++, bucket)
            if (prefix != null) ps.setString(idx++, likeEscape(prefix))
            if (startAfterKey != null) ps.setString(idx++, startAfterKey)
            ps.setInt(idx, fetchLimit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<ListObject>()
                while (rs.next()) {
                    out += ListObject(
                        key = rs.getString("object_key"),
                        etag = rs.getString("etag"),
                        sizeBytes = rs.getLong("size_bytes"),
                        lastModified = rs.getTimestamp("created_at").toInstant(),
                        storageClass = rs.getString("storage_class")
                    )
                }
                out
            }
        }

        // Apply delimiter grouping in-memory.
        val groupedContents = ArrayList<ListObject>()
        val groupedPrefixes = ArrayList<String>()
        for (row in rawRows) {
            if (delimiter != null && delimiter.isNotEmpty()) {
                val afterPrefix = if (prefix != null && row.key.startsWith(prefix)) {
                    row.key.substring(prefix.length)
                } else {
                    row.key
                }
                val delimIdx = afterPrefix.indexOf(delimiter)
                if (delimIdx >= 0) {
                    val commonPrefix = (prefix ?: "") + afterPrefix.substring(0, delimIdx + delimiter.length)
                    if (commonPrefix !in groupedPrefixes) {
                        groupedPrefixes += commonPrefix
                    }
                    continue
                }
            }
            groupedContents += row
        }

        // Merge contents and prefixes in sorted order, then paginate.
        // We interleave by lexicographic key: a CommonPrefix "a/" sorts before
        // "a/b" but after "a0". We synthesise a sortable tuple list.
        data class Entry(val sortKey: String, val isPrefix: Boolean, val obj: ListObject?, val prefix: String?)
        val merged = ArrayList<Entry>(groupedContents.size + groupedPrefixes.size)
        for (c in groupedContents) merged += Entry(c.key, false, c, null)
        for (p in groupedPrefixes) merged += Entry(p, true, null, p)
        merged.sortBy { it.sortKey }

        val truncated = merged.size > effectiveMax
        val page = if (truncated) merged.subList(0, effectiveMax) else merged
        val lastEntry = page.lastOrNull()
        val nextToken = if (truncated) lastEntry?.sortKey else null

        val pageContents = page.filter { !it.isPrefix }.map { it.obj!! }
        val pagePrefixes = page.filter { it.isPrefix }.map { it.prefix!! }

        val enc = encodingType
        val finalContents = if (enc == "url") pageContents.map { it.copy(key = urlEncode(it.key)) } else pageContents
        val finalPrefixes = if (enc == "url") pagePrefixes.map { urlEncode(it) } else pagePrefixes

        // KeyCount counts BOTH contents AND common prefixes (per AWS spec).
        val keyCount = finalContents.size + finalPrefixes.size

        return ListObjectsV2Result(
            bucket = bucket,
            contents = finalContents,
            isTruncated = truncated,
            continuationToken = continuationToken,
            nextContinuationToken = nextToken,
            keyCount = keyCount,
            maxKeys = effectiveMax,
            prefix = prefix,
            delimiter = delimiter,
            commonPrefixes = finalPrefixes,
            startAfter = startAfter,
            encodingType = encodingType
        )
    }

    /**
     * Escape a string for use in a SQL LIKE pattern: `\` and `%` and `_` are
     * escaped so the prefix is matched literally. The caller must use
     * `ESCAPE '\'` in the SQL.
     */
    private fun likeEscape(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    private fun urlEncode(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            when {
                c == 0x2f -> sb.append('/') // '/' — S3 preserves in encoding-type=url
                c in 0x30..0x39 || c in 0x41..0x5a || c in 0x61..0x7a ||
                    c == 0x2d || c == 0x5f || c == 0x2e || c == 0x7e -> sb.append(c.toChar())
                else -> sb.append('%').append("%02X".format(c))
            }
        }
        return sb.toString()
    }

    override fun createMultipartUpload(
        conn: Connection,
        uploadId: String,
        bucket: String,
        key: String,
        contentType: String,
        userMetadata: Map<String, String>,
        storageClass: String,
        contentEncoding: String?,
        contentLanguage: String?,
        cacheControl: String?,
        contentDisposition: String?,
        expires: String?,
        checksumAlgorithm: String?
    ) {
        conn.prepareStatement(
            """
            INSERT INTO multipart_uploads(
                upload_id, bucket, object_key, content_type, user_metadata, storage_class, state,
                content_encoding, content_language, cache_control, content_disposition, expires, checksum_algorithm
            ) VALUES (?, ?, ?, ?, ?::jsonb, ?, 'INITIATED', ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, uploadId)
            ps.setString(2, bucket)
            ps.setString(3, key)
            ps.setString(4, contentType)
            ps.setString(5, userMetadata.toJson())
            ps.setString(6, storageClass)
            ps.setString(7, contentEncoding)
            ps.setString(8, contentLanguage)
            ps.setString(9, cacheControl)
            ps.setString(10, contentDisposition)
            ps.setString(11, expires)
            ps.setString(12, checksumAlgorithm)
            ps.executeUpdate()
        }
    }

    override fun getMultipartUpload(conn: Connection, uploadId: String): MultipartUploadInfo? {
        val sql = """
            SELECT upload_id, bucket, object_key, content_type, user_metadata, storage_class, state,
                   initiated_at, content_encoding, content_language, cache_control,
                   content_disposition, expires, checksum_algorithm
            FROM multipart_uploads
            WHERE upload_id = ? AND state IN ('INITIATED', 'COMPLETING')
        """.trimIndent()
        return conn.prepareStatement(sql).use { ps ->
            ps.setString(1, uploadId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null else rs.toMultipartUploadInfo()
            }
        }
    }

    override fun uploadPart(
        conn: Connection,
        uploadId: String,
        partNumber: Int,
        blobPath: String,
        blobSha256Hex: String,
        etag: String,
        sizeBytes: Long,
        checksumCrc32: String?,
        checksumCrc32C: String?,
        checksumSha1: String?,
        checksumSha256: String?
    ): PartInfo {
        // Upsert: re-uploading the same part number overwrites the previous part.
        val sql = """
            INSERT INTO multipart_parts(upload_id, part_number, blob_path, blob_sha256, etag, size_bytes,
                checksum_crc32, checksum_crc32c, checksum_sha1, checksum_sha256)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (upload_id, part_number) DO UPDATE SET
                blob_path = EXCLUDED.blob_path,
                blob_sha256 = EXCLUDED.blob_sha256,
                etag = EXCLUDED.etag,
                size_bytes = EXCLUDED.size_bytes,
                uploaded_at = now(),
                checksum_crc32 = EXCLUDED.checksum_crc32,
                checksum_crc32c = EXCLUDED.checksum_crc32c,
                checksum_sha1 = EXCLUDED.checksum_sha1,
                checksum_sha256 = EXCLUDED.checksum_sha256
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, uploadId)
            ps.setInt(2, partNumber)
            ps.setString(3, blobPath)
            ps.setBytes(4, hexToBytes(blobSha256Hex))
            ps.setString(5, etag)
            ps.setLong(6, sizeBytes)
            ps.setString(7, checksumCrc32)
            ps.setString(8, checksumCrc32C)
            ps.setString(9, checksumSha1)
            ps.setString(10, checksumSha256)
            ps.executeUpdate()
        }
        return PartInfo(
            partNumber = partNumber,
            blobPath = blobPath,
            blobSha256Hex = blobSha256Hex,
            etag = etag,
            sizeBytes = sizeBytes,
            uploadedAt = Instant.now(),
            checksumCrc32 = checksumCrc32,
            checksumCrc32C = checksumCrc32C,
            checksumSha1 = checksumSha1,
            checksumSha256 = checksumSha256
        )
    }

    override fun getPart(conn: Connection, uploadId: String, partNumber: Int): PartInfo? {
        val sql = """
            SELECT part_number, blob_path, blob_sha256, etag, size_bytes, uploaded_at,
                   checksum_crc32, checksum_crc32c, checksum_sha1, checksum_sha256
            FROM multipart_parts
            WHERE upload_id = ? AND part_number = ?
        """.trimIndent()
        return conn.prepareStatement(sql).use { ps ->
            ps.setString(1, uploadId)
            ps.setInt(2, partNumber)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null else rs.toPartInfo()
            }
        }
    }

    override fun listParts(conn: Connection, uploadId: String): List<PartInfo> {
        val sql = """
            SELECT part_number, blob_path, blob_sha256, etag, size_bytes, uploaded_at,
                   checksum_crc32, checksum_crc32c, checksum_sha1, checksum_sha256
            FROM multipart_parts
            WHERE upload_id = ?
            ORDER BY part_number ASC
        """.trimIndent()
        return conn.prepareStatement(sql).use { ps ->
            ps.setString(1, uploadId)
            ps.executeQuery().use { rs ->
                val out = ArrayList<PartInfo>()
                while (rs.next()) out += rs.toPartInfo()
                out
            }
        }
    }

    override fun deleteParts(conn: Connection, uploadId: String): Int =
        conn.prepareStatement("DELETE FROM multipart_parts WHERE upload_id = ?").use { ps ->
            ps.setString(1, uploadId)
            ps.executeUpdate()
        }

    override fun completeMultipartUpload(conn: Connection, uploadId: String): Boolean =
        conn.prepareStatement(
            "UPDATE multipart_uploads SET state = 'COMPLETED', completed_at = now() " +
                "WHERE upload_id = ? AND state = 'COMPLETING'"
        ).use { ps ->
            ps.setString(1, uploadId)
            ps.executeUpdate() > 0
        }

    override fun markMultipartCompleting(conn: Connection, uploadId: String): Boolean =
        conn.prepareStatement(
            "UPDATE multipart_uploads SET state = 'COMPLETING', completing_at = now() " +
                "WHERE upload_id = ? AND state = 'INITIATED'"
        ).use { ps ->
            ps.setString(1, uploadId)
            ps.executeUpdate() > 0
        }

    override fun abortMultipartUpload(conn: Connection, uploadId: String): Boolean =
        conn.prepareStatement(
            "UPDATE multipart_uploads SET state = 'ABORTED', aborted_at = now() " +
                "WHERE upload_id = ? AND state = 'INITIATED'"
        ).use { ps ->
            ps.setString(1, uploadId)
            ps.executeUpdate() > 0
        }

    override fun failMultipartCompletion(conn: Connection, uploadId: String): Boolean =
        conn.prepareStatement(
            "UPDATE multipart_uploads SET state = 'FAILED_COMPLETION' " +
                "WHERE upload_id = ? AND state = 'COMPLETING'"
        ).use { ps ->
            ps.setString(1, uploadId)
            ps.executeUpdate() > 0
        }

    override fun listStaleMultipartUploads(conn: Connection, olderThanSeconds: Long): List<StaleMultipart> {
        val cutoff = staleCutoff(olderThanSeconds) ?: return emptyList()
        val sql = """
            SELECT upload_id, bucket, object_key, initiated_at
            FROM multipart_uploads
            WHERE state = 'INITIATED'
              AND initiated_at < ?
            ORDER BY initiated_at ASC
            LIMIT 1000
        """.trimIndent()
        return conn.prepareStatement(sql).use { ps ->
            ps.setTimestamp(1, cutoff)
            ps.executeQuery().use { rs ->
                val out = ArrayList<StaleMultipart>()
                while (rs.next()) {
                    out += StaleMultipart(
                        uploadId = rs.getString("upload_id"),
                        bucket = rs.getString("bucket"),
                        key = rs.getString("object_key"),
                        initiatedAt = rs.getTimestamp("initiated_at").toInstant()
                    )
                }
                out
            }
        }
    }

    override fun markMultipartAborted(conn: Connection, uploadId: String) {
        conn.prepareStatement(
            "UPDATE multipart_uploads SET state = 'ABORTED', aborted_at = now() WHERE upload_id = ?"
        ).use { ps ->
            ps.setString(1, uploadId)
            ps.executeUpdate()
        }
    }

    override fun listMultipartUploads(conn: Connection, bucket: String?): List<MultipartUploadInfo> {
        val sql = if (bucket != null) {
            """
            SELECT upload_id, bucket, object_key, content_type, user_metadata, storage_class, state,
                   initiated_at, content_encoding, content_language, cache_control,
                   content_disposition, expires, checksum_algorithm
            FROM multipart_uploads
            WHERE state = 'INITIATED' AND bucket = ?
            ORDER BY initiated_at ASC
            LIMIT 1000
            """.trimIndent()
        } else {
            """
            SELECT upload_id, bucket, object_key, content_type, user_metadata, storage_class, state,
                   initiated_at, content_encoding, content_language, cache_control,
                   content_disposition, expires, checksum_algorithm
            FROM multipart_uploads
            WHERE state = 'INITIATED'
            ORDER BY initiated_at ASC
            LIMIT 1000
            """.trimIndent()
        }
        return conn.prepareStatement(sql).use { ps ->
            if (bucket != null) ps.setString(1, bucket)
            ps.executeQuery().use { rs ->
                val out = ArrayList<MultipartUploadInfo>()
                while (rs.next()) out += rs.toMultipartUploadInfo()
                out
            }
        }
    }

    override fun createBlobWriteIntent(conn: Connection, blobSha256Hex: String): String {
        val intentId = UUID.randomUUID().toString()
        conn.prepareStatement(
            "INSERT INTO blob_write_intents(intent_id, blob_sha256_hex) VALUES (?, ?)"
        ).use { ps ->
            ps.setString(1, intentId)
            ps.setString(2, blobSha256Hex)
            ps.executeUpdate()
        }
        return intentId
    }

    override fun clearBlobWriteIntent(conn: Connection, intentId: String): Boolean =
        conn.prepareStatement("DELETE FROM blob_write_intents WHERE intent_id = ?").use { ps ->
            ps.setString(1, intentId)
            ps.executeUpdate() > 0
        }

    override fun deleteStaleBlobWriteIntents(conn: Connection, olderThanSeconds: Long): Int {
        val cutoff = staleCutoff(olderThanSeconds) ?: return 0
        return conn.prepareStatement(
            "DELETE FROM blob_write_intents " +
                "WHERE created_at < ?"
        ).use { ps ->
            ps.setTimestamp(1, cutoff)
            ps.executeUpdate()
        }
    }

    override fun upsertAccessKey(conn: Connection, accessKeyId: String, secretAccessKey: String, description: String?) {
        upsertAccessKeyRecord(
            conn,
            AccessKeyRecord(
                accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey,
                description = description,
                state = "ACTIVE",
            )
        )
    }

    override fun lookupSecret(conn: Connection, accessKeyId: String): String? =
        conn.prepareStatement(
            "SELECT secret_access_key FROM access_keys " +
                "WHERE access_key_id = ? AND state = 'ACTIVE' AND deleted_at IS NULL"
        ).use { ps ->
            ps.setString(1, accessKeyId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    override fun upsertAccessKeyRecord(conn: Connection, record: AccessKeyRecord) {
        conn.prepareStatement(
            "INSERT INTO access_keys(" +
                "access_key_id, secret_access_key, secret_ciphertext, secret_nonce, secret_key_id, " +
                "description, state, updated_at, disabled_at, deleted_at, rotated_at" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?, ?, ?) " +
                "ON CONFLICT (access_key_id) DO UPDATE SET " +
                "secret_access_key = EXCLUDED.secret_access_key, " +
                "secret_ciphertext = EXCLUDED.secret_ciphertext, " +
                "secret_nonce = EXCLUDED.secret_nonce, " +
                "secret_key_id = EXCLUDED.secret_key_id, " +
                "description = EXCLUDED.description, " +
                "state = EXCLUDED.state, " +
                "updated_at = now(), " +
                "disabled_at = EXCLUDED.disabled_at, " +
                "deleted_at = EXCLUDED.deleted_at, " +
                "rotated_at = COALESCE(EXCLUDED.rotated_at, access_keys.rotated_at)"
        ).use { ps ->
            ps.setString(1, record.accessKeyId)
            ps.setString(2, record.secretAccessKey)
            ps.setBytes(3, record.secretCiphertext)
            ps.setBytes(4, record.secretNonce)
            ps.setString(5, record.secretKeyId)
            ps.setString(6, record.description)
            ps.setString(7, record.state)
            ps.setTimestamp(8, record.disabledAt?.let { Timestamp.from(it) })
            ps.setTimestamp(9, record.deletedAt?.let { Timestamp.from(it) })
            ps.setTimestamp(10, record.rotatedAt?.let { Timestamp.from(it) })
            ps.executeUpdate()
        }
    }

    override fun lookupAccessKey(conn: Connection, accessKeyId: String): AccessKeyRecord? =
        conn.prepareStatement(
            "SELECT access_key_id, secret_access_key, secret_ciphertext, secret_nonce, secret_key_id, " +
                "description, state, created_at, updated_at, disabled_at, deleted_at, rotated_at " +
                "FROM access_keys WHERE access_key_id = ? AND state = 'ACTIVE' AND deleted_at IS NULL"
        ).use { ps ->
            ps.setString(1, accessKeyId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toAccessKeyRecord() else null }
        }

    override fun listAccessKeys(conn: Connection, includeDeleted: Boolean): List<AccessKeyRecord> {
        val sql = "SELECT access_key_id, secret_access_key, secret_ciphertext, secret_nonce, secret_key_id, " +
            "description, state, created_at, updated_at, disabled_at, deleted_at, rotated_at " +
            "FROM access_keys " +
            (if (includeDeleted) "" else "WHERE deleted_at IS NULL ") +
            "ORDER BY created_at, access_key_id"
        return conn.prepareStatement(sql).use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toAccessKeyRecord())
                }
            }
        }
    }

    override fun updateAccessKeyState(conn: Connection, accessKeyId: String, state: String): Boolean {
        val now = Timestamp.from(Instant.now())
        val disabledAt = if (state == "DISABLED") "disabled_at = COALESCE(disabled_at, ?), " else "disabled_at = NULL, "
        return conn.prepareStatement(
            "UPDATE access_keys SET state = ?, updated_at = now(), $disabledAt deleted_at = NULL " +
                "WHERE access_key_id = ? AND deleted_at IS NULL"
        ).use { ps ->
            ps.setString(1, state)
            var i = 2
            if (state == "DISABLED") ps.setTimestamp(i++, now)
            ps.setString(i, accessKeyId)
            ps.executeUpdate() > 0
        }
    }

    override fun softDeleteAccessKey(conn: Connection, accessKeyId: String): Boolean =
        conn.prepareStatement(
            "UPDATE access_keys SET state = 'DELETED', deleted_at = COALESCE(deleted_at, now()), updated_at = now() " +
                "WHERE access_key_id = ? AND deleted_at IS NULL"
        ).use { ps ->
            ps.setString(1, accessKeyId)
            ps.executeUpdate() > 0
        }

    override fun insertAuditEvent(conn: Connection, event: AuditEventRecord) {
        conn.prepareStatement(
            "INSERT INTO audit_events(" +
                "event_id, request_id, access_key_id, operation, bucket, object_key, status, latency_ms, source, detail" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)"
        ).use { ps ->
            ps.setObject(1, event.eventId)
            ps.setString(2, event.requestId)
            ps.setString(3, event.accessKeyId)
            ps.setString(4, event.operation)
            ps.setString(5, event.bucket)
            ps.setString(6, event.objectKey)
            if (event.status == null) ps.setNull(7, java.sql.Types.INTEGER) else ps.setInt(7, event.status)
            if (event.latencyMs == null) ps.setNull(8, java.sql.Types.BIGINT) else ps.setLong(8, event.latencyMs)
            ps.setString(9, event.source)
            ps.setString(10, event.detailJson.ifBlank { "{}" })
            ps.executeUpdate()
        }
    }

    override fun listAuditEvents(conn: Connection, limit: Int): List<AuditEventRecord> =
        conn.prepareStatement(
            "SELECT event_id, occurred_at, request_id, access_key_id, operation, bucket, object_key, " +
                "status, latency_ms, source, detail::text AS detail_json " +
                "FROM audit_events ORDER BY occurred_at DESC LIMIT ?"
        ).use { ps ->
            ps.setInt(1, limit.coerceIn(1, 10_000))
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            AuditEventRecord(
                                eventId = rs.getObject("event_id", UUID::class.java),
                                occurredAt = rs.getTimestamp("occurred_at").toInstant(),
                                requestId = rs.getString("request_id"),
                                accessKeyId = rs.getString("access_key_id"),
                                operation = rs.getString("operation"),
                                bucket = rs.getString("bucket"),
                                objectKey = rs.getString("object_key"),
                                status = rs.getObject("status") as? Int,
                                latencyMs = rs.getObject("latency_ms") as? Long,
                                source = rs.getString("source"),
                                detailJson = rs.getString("detail_json") ?: "{}",
                            )
                        )
                    }
                }
            }
        }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    when {
        this == null && other == null -> true
        this == null || other == null -> false
        else -> contentEquals(other)
    }

private fun ResultSet.toAccessKeyRecord(): AccessKeyRecord =
    AccessKeyRecord(
        accessKeyId = getString("access_key_id"),
        secretAccessKey = getString("secret_access_key"),
        secretCiphertext = getBytes("secret_ciphertext"),
        secretNonce = getBytes("secret_nonce"),
        secretKeyId = getString("secret_key_id"),
        description = getString("description"),
        state = getString("state"),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
        disabledAt = getTimestamp("disabled_at")?.toInstant(),
        deletedAt = getTimestamp("deleted_at")?.toInstant(),
        rotatedAt = getTimestamp("rotated_at")?.toInstant(),
    )

private fun PreparedStatement.applyObjectMeta(meta: ObjectMetadata) {
    var i = 1
    setString(i++, meta.bucket)
    setString(i++, meta.key)
    setString(i++, meta.blobPath)
    setBytes(i++, hexToBytes(meta.blobSha256Hex))
    setString(i++, meta.etag)
    setLong(i++, meta.sizeBytes)
    setString(i++, meta.contentType)
    setString(i++, meta.contentEncoding)
    setString(i++, meta.contentLanguage)
    setString(i++, meta.cacheControl)
    setString(i++, meta.contentDisposition)
    setString(i++, meta.expires)
    setString(i++, meta.userMetadata.toJson())
    setString(i++, meta.versionId)
    setString(i++, meta.storageClass)
    // M2.1: checksum fields (nullable)
    setString(i++, meta.checksumCrc32)
    setString(i++, meta.checksumCrc32C)
    setString(i++, meta.checksumSha1)
    setString(i++, meta.checksumSha256)
    setString(i, meta.checksumType)
}

private fun ResultSet.toObjectMeta(): ObjectMetadata {
    val metaJson = getString("user_metadata")
    val userMeta = if (metaJson.isNullOrBlank()) emptyMap() else metaJson.fromJson()
    return ObjectMetadata(
        bucket = getString("bucket"),
        key = getString("object_key"),
        blobPath = getString("blob_path"),
        blobSha256Hex = bytesToHex(getBytes("blob_sha256")),
        etag = getString("etag"),
        sizeBytes = getLong("size_bytes"),
        contentType = getString("content_type") ?: "application/octet-stream",
        contentEncoding = getString("content_encoding"),
        contentLanguage = getString("content_language"),
        cacheControl = getString("cache_control"),
        contentDisposition = getString("content_disposition"),
        expires = getString("expires"),
        userMetadata = userMeta,
        versionId = getString("version_id") ?: "null",
        storageClass = getString("storage_class") ?: "STANDARD",
        createdAt = getTimestamp("created_at").toInstant(),
        // M2.1: checksum fields
        checksumCrc32 = getString("checksum_crc32"),
        checksumCrc32C = getString("checksum_crc32c"),
        checksumSha1 = getString("checksum_sha1"),
        checksumSha256 = getString("checksum_sha256"),
        checksumType = getString("checksum_type")
    )
}

private fun ResultSet.toMultipartUploadInfo(): MultipartUploadInfo {
    val metaJson = getString("user_metadata")
    val userMeta = if (metaJson.isNullOrBlank()) emptyMap() else metaJson.fromJson()
    return MultipartUploadInfo(
        uploadId = getString("upload_id"),
        bucket = getString("bucket"),
        key = getString("object_key"),
        contentType = getString("content_type") ?: "application/octet-stream",
        userMetadata = userMeta,
        storageClass = getString("storage_class") ?: "STANDARD",
        state = getString("state"),
        initiatedAt = getTimestamp("initiated_at").toInstant(),
        contentEncoding = getString("content_encoding"),
        contentLanguage = getString("content_language"),
        cacheControl = getString("cache_control"),
        contentDisposition = getString("content_disposition"),
        expires = getString("expires"),
        checksumAlgorithm = getString("checksum_algorithm")
    )
}

private fun ResultSet.toPartInfo(): PartInfo = PartInfo(
    partNumber = getInt("part_number"),
    blobPath = getString("blob_path"),
    blobSha256Hex = bytesToHex(getBytes("blob_sha256")),
    etag = getString("etag"),
    sizeBytes = getLong("size_bytes"),
    uploadedAt = getTimestamp("uploaded_at").toInstant(),
    checksumCrc32 = getString("checksum_crc32"),
    checksumCrc32C = getString("checksum_crc32c"),
    checksumSha1 = getString("checksum_sha1"),
    checksumSha256 = getString("checksum_sha256")
)

fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "Hex string must have even length: $hex" }
    val out = ByteArray(hex.length / 2)
    var i = 0
    while (i < hex.length) {
        out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        i += 2
    }
    return out
}

fun bytesToHex(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it) }

fun Map<String, String>.toJson(): String {
    if (this.isEmpty()) return "{}"
    val sb = StringBuilder("{")
    entries.forEachIndexed { idx, (k, v) ->
        if (idx > 0) sb.append(',')
        sb.append('"').append(k.escapeJson()).append("\":\"").append(v.escapeJson()).append('"')
    }
    sb.append('}')
    return sb.toString()
}

fun String.fromJson(): Map<String, String> {
    val s = this.trim()
    if (s.isEmpty() || s == "{}") return emptyMap()
    val out = LinkedHashMap<String, String>()
    var i = 1 // skip {
    while (i < s.length && s[i] != '}') {
        while (i < s.length && s[i].isWhitespace()) i++
        if (i >= s.length || s[i] == '}') break
        // skip comma
        if (s[i] == ',') {
            i++
            while (i < s.length && s[i].isWhitespace()) i++
        }
        require(s[i] == '"') { "Expected '\"' at $i in $s" }
        val (key, afterKey) = readJsonString(s, i)
        i = afterKey
        while (i < s.length && s[i].isWhitespace()) i++
        require(i < s.length && s[i] == ':') { "Expected ':' at $i in $s" }
        i++
        while (i < s.length && s[i].isWhitespace()) i++
        require(i < s.length && s[i] == '"') { "Expected '\"' at $i in $s" }
        val (value, afterValue) = readJsonString(s, i)
        i = afterValue
        out[key] = value
    }
    return out
}

private fun readJsonString(s: String, start: Int): Pair<String, Int> {
    require(s[start] == '"')
    val sb = StringBuilder()
    var i = start + 1
    while (i < s.length && s[i] != '"') {
        if (s[i] == '\\' && i + 1 < s.length) {
            when (val next = s[i + 1]) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                else -> sb.append(next)
            }
            i += 2
        } else {
            sb.append(s[i])
            i++
        }
    }
    require(i < s.length && s[i] == '"') { "Unterminated string at $start in $s" }
    return sb.toString() to (i + 1)
}

private fun String.escapeJson(): String =
    buildString(length + 4) {
        for (c in this@escapeJson) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
    }

fun newUploadId(): String = UUID.randomUUID().toString().replace("-", "").lowercase()
