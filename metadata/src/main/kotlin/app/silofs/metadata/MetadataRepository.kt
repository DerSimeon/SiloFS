package app.silofs.metadata

import app.silofs.common.BucketInfo
import app.silofs.common.ListObject
import app.silofs.common.ListObjectsV2Result
import app.silofs.common.ObjectMetadata
import app.silofs.common.ObjectVersion
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
    fun createBucket(
        conn: Connection,
        name: String,
        region: String,
        ownerId: String,
    )

    fun bucketExists(
        conn: Connection,
        name: String,
    ): Boolean

    fun getBucket(
        conn: Connection,
        name: String,
    ): BucketInfo?

    fun listBuckets(conn: Connection): List<BucketInfo>

    fun deleteBucket(
        conn: Connection,
        name: String,
    ): Boolean

    fun countObjectsInBucket(
        conn: Connection,
        name: String,
    ): Long

    fun grantBucketPermission(
        conn: Connection,
        accessKeyId: String,
        bucket: String,
        permission: String,
    )

    fun revokeBucketPermission(
        conn: Connection,
        accessKeyId: String,
        bucket: String,
        permission: String,
    ): Boolean

    fun listBucketGrants(
        conn: Connection,
        accessKeyId: String? = null,
        bucket: String? = null,
    ): List<BucketGrantRecord>

    fun hasBucketPermission(
        conn: Connection,
        accessKeyId: String,
        bucket: String,
        permission: String,
    ): Boolean

    fun listBucketsForAccessKey(
        conn: Connection,
        accessKeyId: String,
    ): List<BucketInfo>

    // Objects
    fun putObject(
        conn: Connection,
        meta: ObjectMetadata,
    ): ObjectMetadata

    fun getObject(
        conn: Connection,
        bucket: String,
        key: String,
    ): ObjectMetadata?

    fun getObjectVersion(
        conn: Connection,
        bucket: String,
        key: String,
        versionId: String,
    ): ObjectMetadata?

    fun deleteObject(
        conn: Connection,
        bucket: String,
        key: String,
    ): Boolean

    fun deleteObjectVersion(
        conn: Connection,
        bucket: String,
        key: String,
        versionId: String,
        now: Instant = Instant.now(),
    ): Boolean

    fun createDeleteMarker(
        conn: Connection,
        bucket: String,
        key: String,
        versionId: String,
    ): ObjectMetadata

    fun listObjectVersions(
        conn: Connection,
        bucket: String,
        prefix: String? = null,
    ): List<ObjectVersion>

    fun setBucketVersioning(
        conn: Connection,
        bucket: String,
        status: String,
    ): Boolean

    fun setBucketObjectLock(
        conn: Connection,
        bucket: String,
        enabled: Boolean,
        defaultRetentionMode: String?,
        defaultRetentionDays: Int?,
    ): Boolean

    fun putRetention(
        conn: Connection,
        bucket: String,
        key: String,
        versionId: String,
        mode: String,
        retainUntil: Instant,
    ): Boolean

    fun putLegalHold(
        conn: Connection,
        bucket: String,
        key: String,
        versionId: String,
        enabled: Boolean,
    ): Boolean

    fun replaceLifecycleRules(
        conn: Connection,
        bucket: String,
        rules: List<LifecycleRuleRecord>,
    )

    fun listLifecycleRules(
        conn: Connection,
        bucket: String,
    ): List<LifecycleRuleRecord>

    fun expireLifecycleObjects(
        conn: Connection,
        batchSize: Int,
        now: Instant = Instant.now(),
    ): Int

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
        encodingType: String? = null,
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
        checksumAlgorithm: String?,
    )

    /** Returns the multipart upload row if it exists and is active (INITIATED or COMPLETING). */
    fun getMultipartUpload(
        conn: Connection,
        uploadId: String,
    ): MultipartUploadInfo?

    /** Upserts a part row. Re-uploading the same part number overwrites the previous one. */
    fun uploadPart(
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
        checksumSha256: String?,
        encryptionMode: String?,
        encryptionKeyId: String?,
        encryptionNonce: ByteArray?,
    ): PartInfo

    /** Returns a single part row, or null if not uploaded. */
    fun getPart(
        conn: Connection,
        uploadId: String,
        partNumber: Int,
    ): PartInfo?

    /** Returns all parts for an upload, ordered by part_number ASC. */
    fun listParts(
        conn: Connection,
        uploadId: String,
    ): List<PartInfo>

    /** Deletes all part rows for an upload. Used during abort + complete. */
    fun deleteParts(
        conn: Connection,
        uploadId: String,
    ): Int

    /** Marks the upload as COMPLETED. The parts rows should already be deleted. */
    fun completeMultipartUpload(
        conn: Connection,
        uploadId: String,
    ): Boolean

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
    fun markMultipartCompleting(
        conn: Connection,
        uploadId: String,
    ): Boolean

    fun abortMultipartUpload(
        conn: Connection,
        uploadId: String,
    ): Boolean

    fun failMultipartCompletion(
        conn: Connection,
        uploadId: String,
    ): Boolean

    fun listStaleMultipartUploads(
        conn: Connection,
        olderThanSeconds: Long,
    ): List<StaleMultipart>

    fun markMultipartAborted(
        conn: Connection,
        uploadId: String,
    )

    /** Returns all multipart uploads for a bucket (used by ListMultipartUploads). */
    fun listMultipartUploads(
        conn: Connection,
        bucket: String?,
    ): List<MultipartUploadInfo>

    // Blob write intents protect filesystem blobs between publish and metadata commit.
    fun createBlobWriteIntent(
        conn: Connection,
        blobSha256Hex: String,
    ): String

    fun clearBlobWriteIntent(
        conn: Connection,
        intentId: String,
    ): Boolean

    fun deleteStaleBlobWriteIntents(
        conn: Connection,
        olderThanSeconds: Long,
    ): Int

    // Access keys
    fun upsertAccessKey(
        conn: Connection,
        accessKeyId: String,
        secretAccessKey: String,
        description: String?,
    )

    fun lookupSecret(
        conn: Connection,
        accessKeyId: String,
    ): String?

    fun upsertAccessKeyRecord(
        conn: Connection,
        record: AccessKeyRecord,
    )

    fun lookupAccessKey(
        conn: Connection,
        accessKeyId: String,
    ): AccessKeyRecord?

    fun listAccessKeys(
        conn: Connection,
        includeDeleted: Boolean = false,
    ): List<AccessKeyRecord>

    fun updateAccessKeyState(
        conn: Connection,
        accessKeyId: String,
        state: String,
    ): Boolean

    fun softDeleteAccessKey(
        conn: Connection,
        accessKeyId: String,
    ): Boolean

    fun insertAuditEvent(
        conn: Connection,
        event: AuditEventRecord,
    )

    fun listAuditEvents(
        conn: Connection,
        limit: Int = 100,
    ): List<AuditEventRecord>
}

data class StaleMultipart(
    val uploadId: String,
    val bucket: String,
    val key: String,
    val initiatedAt: Instant,
)

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

data class BucketGrantRecord(
    val accessKeyId: String,
    val bucket: String,
    val permission: String,
    val createdAt: Instant? = null,
)

data class LifecycleRuleRecord(
    val bucket: String,
    val ruleId: String,
    val enabled: Boolean,
    val prefix: String?,
    val currentVersionExpirationDays: Int?,
    val noncurrentVersionExpirationDays: Int?,
    val abortIncompleteMultipartDays: Int?,
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
    val checksumAlgorithm: String? = null,
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
    val checksumSha256: String? = null,
    val encryptionMode: String? = null,
    val encryptionKeyId: String? = null,
    val encryptionNonce: ByteArray? = null,
)

/**
 * Plain JDBC implementation. SQL is hand-written so we have full control over
 * the queries and can run them through EXPLAIN later if needed.
 */
class JdbcMetadataRepository : MetadataRepository {
    override fun createBucket(
        conn: Connection,
        name: String,
        region: String,
        ownerId: String,
    ) {
        conn
            .prepareStatement(
                "INSERT INTO buckets(name, region, owner_id) VALUES (?, ?, ?) " +
                    "ON CONFLICT (name) DO NOTHING",
            ).use { ps ->
                ps.setString(1, name)
                ps.setString(2, region)
                ps.setString(3, ownerId)
                ps.executeUpdate()
            }
    }

    override fun bucketExists(
        conn: Connection,
        name: String,
    ): Boolean =
        conn.prepareStatement("SELECT 1 FROM buckets WHERE name = ? AND deleted_at IS NULL").use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { it.next() }
        }

    override fun getBucket(
        conn: Connection,
        name: String,
    ): BucketInfo? =
        conn
            .prepareStatement(
                """
                SELECT name, region, owner_id, created_at, versioning_status, object_lock_enabled,
                       default_retention_mode, default_retention_days
                FROM buckets WHERE name = ? AND deleted_at IS NULL
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, name)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        null
                    } else {
                        BucketInfo(
                            name = rs.getString("name"),
                            region = rs.getString("region"),
                            ownerId = rs.getString("owner_id"),
                            createdAt = rs.getTimestamp("created_at").toInstant(),
                            versioningStatus = rs.getString("versioning_status"),
                            objectLockEnabled = rs.getBoolean("object_lock_enabled"),
                            defaultRetentionMode = rs.getString("default_retention_mode"),
                            defaultRetentionDays = rs.getObject("default_retention_days") as Int?,
                        )
                    }
                }
            }

    override fun listBuckets(conn: Connection): List<BucketInfo> =
        conn
            .prepareStatement(
                """
                SELECT name, region, owner_id, created_at, versioning_status, object_lock_enabled,
                       default_retention_mode, default_retention_days
                FROM buckets WHERE deleted_at IS NULL ORDER BY created_at
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val out = ArrayList<BucketInfo>()
                    while (rs.next()) {
                        out +=
                            BucketInfo(
                                name = rs.getString("name"),
                                region = rs.getString("region"),
                                ownerId = rs.getString("owner_id"),
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                                versioningStatus = rs.getString("versioning_status"),
                                objectLockEnabled = rs.getBoolean("object_lock_enabled"),
                                defaultRetentionMode = rs.getString("default_retention_mode"),
                                defaultRetentionDays = rs.getObject("default_retention_days") as Int?,
                            )
                    }
                    out
                }
            }

    override fun grantBucketPermission(
        conn: Connection,
        accessKeyId: String,
        bucket: String,
        permission: String,
    ) {
        conn
            .prepareStatement(
                """
                INSERT INTO access_key_bucket_grants(access_key_id, bucket, permission)
                VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, accessKeyId)
                ps.setString(2, bucket)
                ps.setString(3, permission.uppercase())
                ps.executeUpdate()
            }
    }

    override fun revokeBucketPermission(
        conn: Connection,
        accessKeyId: String,
        bucket: String,
        permission: String,
    ): Boolean =
        conn
            .prepareStatement(
                "DELETE FROM access_key_bucket_grants WHERE access_key_id = ? AND bucket = ? AND permission = ?",
            ).use { ps ->
                ps.setString(1, accessKeyId)
                ps.setString(2, bucket)
                ps.setString(3, permission.uppercase())
                ps.executeUpdate() > 0
            }

    override fun listBucketGrants(
        conn: Connection,
        accessKeyId: String?,
        bucket: String?,
    ): List<BucketGrantRecord> {
        val sql =
            buildString {
                append("SELECT access_key_id, bucket, permission, created_at FROM access_key_bucket_grants WHERE 1=1 ")
                if (accessKeyId != null) append("AND access_key_id = ? ")
                if (bucket != null) append("AND bucket = ? ")
                append("ORDER BY access_key_id, bucket, permission")
            }
        return conn.prepareStatement(sql).use { ps ->
            var i = 1
            if (accessKeyId != null) ps.setString(i++, accessKeyId)
            if (bucket != null) ps.setString(i++, bucket)
            ps.executeQuery().use { rs ->
                val out = ArrayList<BucketGrantRecord>()
                while (rs.next()) {
                    out +=
                        BucketGrantRecord(
                            accessKeyId = rs.getString("access_key_id"),
                            bucket = rs.getString("bucket"),
                            permission = rs.getString("permission"),
                            createdAt = rs.getTimestamp("created_at").toInstant(),
                        )
                }
                out
            }
        }
    }

    override fun hasBucketPermission(
        conn: Connection,
        accessKeyId: String,
        bucket: String,
        permission: String,
    ): Boolean {
        val allowed =
            when (permission.uppercase()) {
                "READ" -> listOf("READ", "ADMIN")
                "WRITE" -> listOf("WRITE", "ADMIN")
                "ADMIN" -> listOf("ADMIN")
                else -> listOf(permission.uppercase())
            }
        val placeholders = allowed.joinToString(",") { "?" }
        val sql =
            """
            SELECT 1 FROM access_key_bucket_grants
            WHERE access_key_id = ? AND bucket IN (?, '*') AND permission IN ($placeholders)
            LIMIT 1
            """.trimIndent()
        return conn.prepareStatement(sql).use { ps ->
            ps.setString(1, accessKeyId)
            ps.setString(2, bucket)
            allowed.forEachIndexed { idx, value -> ps.setString(3 + idx, value) }
            ps.executeQuery().use { it.next() }
        }
    }

    override fun listBucketsForAccessKey(
        conn: Connection,
        accessKeyId: String,
    ): List<BucketInfo> {
        if (hasBucketPermission(conn, accessKeyId, "*", "ADMIN")) return listBuckets(conn)
        val sql =
            """
            SELECT DISTINCT b.name, b.region, b.owner_id, b.created_at, b.versioning_status,
                   b.object_lock_enabled, b.default_retention_mode, b.default_retention_days
            FROM buckets b
            JOIN access_key_bucket_grants g ON g.bucket = b.name
            WHERE b.deleted_at IS NULL AND g.access_key_id = ?
            ORDER BY b.created_at
            """.trimIndent()
        return conn.prepareStatement(sql).use { ps ->
            ps.setString(1, accessKeyId)
            ps.executeQuery().use { rs ->
                val out = ArrayList<BucketInfo>()
                while (rs.next()) {
                    out +=
                        BucketInfo(
                            name = rs.getString("name"),
                            region = rs.getString("region"),
                            ownerId = rs.getString("owner_id"),
                            createdAt = rs.getTimestamp("created_at").toInstant(),
                            versioningStatus = rs.getString("versioning_status"),
                            objectLockEnabled = rs.getBoolean("object_lock_enabled"),
                            defaultRetentionMode = rs.getString("default_retention_mode"),
                            defaultRetentionDays = rs.getObject("default_retention_days") as Int?,
                        )
                }
                out
            }
        }
    }

    /**
     * Soft-deletes the bucket row. The caller MUST first check
     * [countObjectsInBucket] == 0 and throw [BucketNotEmpty] otherwise —
     * we do not enforce that here so the check can run in the same
     * transaction as the delete.
     */
    override fun deleteBucket(
        conn: Connection,
        name: String,
    ): Boolean =
        conn
            .prepareStatement(
                "UPDATE buckets SET deleted_at = now() WHERE name = ? AND deleted_at IS NULL",
            ).use { ps ->
                ps.setString(1, name)
                ps.executeUpdate() > 0
            }

    override fun countObjectsInBucket(
        conn: Connection,
        name: String,
    ): Long =
        conn
            .prepareStatement(
                "SELECT COUNT(*) FROM objects WHERE bucket = ? AND deleted_at IS NULL",
            ).use { ps ->
                ps.setString(1, name)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }

    override fun putObject(
        conn: Connection,
        meta: ObjectMetadata,
    ): ObjectMetadata {
        if (meta.versionId != "null") {
            conn
                .prepareStatement(
                    "UPDATE objects SET is_latest = FALSE WHERE bucket = ? AND object_key = ? AND deleted_at IS NULL",
                ).use { ps ->
                    ps.setString(1, meta.bucket)
                    ps.setString(2, meta.key)
                    ps.executeUpdate()
                }
        }
        val sql =
            """
            INSERT INTO objects(
                bucket, object_key, blob_path, blob_sha256, etag, size_bytes,
                content_type, content_encoding, content_language, cache_control,
                content_disposition, expires, user_metadata, version_id, is_latest,
                storage_class, created_at,
                checksum_crc32, checksum_crc32c, checksum_sha1, checksum_sha256, checksum_type,
                encryption_mode, encryption_key_id, encryption_nonce,
                is_delete_marker, retention_mode, retain_until, legal_hold
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, TRUE, ?, now(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                checksum_type = EXCLUDED.checksum_type,
                encryption_mode = EXCLUDED.encryption_mode,
                encryption_key_id = EXCLUDED.encryption_key_id,
                encryption_nonce = EXCLUDED.encryption_nonce,
                is_delete_marker = EXCLUDED.is_delete_marker,
                retention_mode = EXCLUDED.retention_mode,
                retain_until = EXCLUDED.retain_until,
                legal_hold = EXCLUDED.legal_hold,
                is_latest = TRUE
            """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            ps.applyObjectMeta(meta)
            ps.executeUpdate()
        }
        return meta.copy(createdAt = Instant.now())
    }

    override fun getObject(
        conn: Connection,
        bucket: String,
        key: String,
    ): ObjectMetadata? {
        val sql =
            """
            SELECT bucket, object_key, blob_path, blob_sha256, etag, size_bytes,
                   content_type, content_encoding, content_language, cache_control,
                   content_disposition, expires, user_metadata, version_id, storage_class,
                   created_at,
                   checksum_crc32, checksum_crc32c, checksum_sha1, checksum_sha256, checksum_type,
                   encryption_mode, encryption_key_id, encryption_nonce,
                   is_delete_marker, retention_mode, retain_until, legal_hold
            FROM objects
            WHERE bucket = ? AND object_key = ? AND deleted_at IS NULL AND is_latest = TRUE
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent()
        return conn.prepareStatement(sql).use { ps ->
            ps.setString(1, bucket)
            ps.setString(2, key)
            ps.executeQuery().use { rs ->
                if (!rs.next() || rs.getBoolean("is_delete_marker")) null else rs.toObjectMeta()
            }
        }
    }

    override fun getObjectVersion(
        conn: Connection,
        bucket: String,
        key: String,
        versionId: String,
    ): ObjectMetadata? {
        val sql =
            """
            SELECT bucket, object_key, blob_path, blob_sha256, etag, size_bytes,
                   content_type, content_encoding, content_language, cache_control,
                   content_disposition, expires, user_metadata, version_id, storage_class,
                   created_at,
                   checksum_crc32, checksum_crc32c, checksum_sha1, checksum_sha256, checksum_type,
                   encryption_mode, encryption_key_id, encryption_nonce,
                   is_delete_marker, retention_mode, retain_until, legal_hold
            FROM objects
            WHERE bucket = ? AND object_key = ? AND version_id = ? AND deleted_at IS NULL
            """.trimIndent()
        return conn.prepareStatement(sql).use { ps ->
            ps.setString(1, bucket)
            ps.setString(2, key)
            ps.setString(3, versionId)
            ps.executeQuery().use { rs ->
                if (!rs.next() || rs.getBoolean("is_delete_marker")) null else rs.toObjectMeta()
            }
        }
    }

    override fun deleteObject(
        conn: Connection,
        bucket: String,
        key: String,
    ): Boolean =
        conn
            .prepareStatement(
                "UPDATE objects SET deleted_at = now(), is_latest = FALSE WHERE bucket = ? AND object_key = ? AND deleted_at IS NULL AND is_latest = TRUE",
            ).use { ps ->
                ps.setString(1, bucket)
                ps.setString(2, key)
                ps.executeUpdate() > 0
            }

    override fun deleteObjectVersion(
        conn: Connection,
        bucket: String,
        key: String,
        versionId: String,
        now: Instant,
    ): Boolean {
        val existing = getObjectRow(conn, bucket, key, versionId) ?: return false
        val retainUntil = existing.retainUntil
        if (existing.legalHold || (retainUntil != null && retainUntil.isAfter(now))) {
            return false
        }
        val wasLatest = isLatestObjectVersion(conn, bucket, key, versionId)
        val deleted =
            conn
                .prepareStatement(
                    "UPDATE objects SET deleted_at = now(), is_latest = FALSE WHERE bucket = ? AND object_key = ? AND version_id = ? AND deleted_at IS NULL",
                ).use { ps ->
                    ps.setString(1, bucket)
                    ps.setString(2, key)
                    ps.setString(3, versionId)
                    ps.executeUpdate() > 0
                }
        if (deleted && wasLatest) promoteNewestVersion(conn, bucket, key)
        return deleted
    }

    override fun createDeleteMarker(
        conn: Connection,
        bucket: String,
        key: String,
        versionId: String,
    ): ObjectMetadata {
        conn
            .prepareStatement(
                "UPDATE objects SET is_latest = FALSE WHERE bucket = ? AND object_key = ? AND deleted_at IS NULL",
            ).use { ps ->
                ps.setString(1, bucket)
                ps.setString(2, key)
                ps.executeUpdate()
            }
        val meta =
            ObjectMetadata(
                bucket = bucket,
                key = key,
                blobPath = "",
                blobSha256Hex = "00".repeat(32),
                etag = "",
                sizeBytes = 0,
                contentType = "application/octet-stream",
                contentEncoding = null,
                contentLanguage = null,
                cacheControl = null,
                contentDisposition = null,
                expires = null,
                userMetadata = emptyMap(),
                versionId = versionId,
                storageClass = "STANDARD",
                createdAt = Instant.now(),
                isDeleteMarker = true,
            )
        putObject(conn, meta)
        return meta
    }

    override fun listObjectVersions(
        conn: Connection,
        bucket: String,
        prefix: String?,
    ): List<ObjectVersion> {
        val sql =
            buildString {
                append("SELECT object_key, version_id, is_latest, is_delete_marker, etag, size_bytes, created_at, storage_class ")
                append("FROM objects WHERE bucket = ? AND deleted_at IS NULL ")
                if (prefix != null) append("AND object_key LIKE ? ESCAPE '\\' ")
                append("ORDER BY object_key ASC, created_at DESC")
            }
        return conn.prepareStatement(sql).use { ps ->
            ps.setString(1, bucket)
            if (prefix != null) ps.setString(2, likeEscape(prefix))
            ps.executeQuery().use { rs ->
                val out = ArrayList<ObjectVersion>()
                while (rs.next()) {
                    out +=
                        ObjectVersion(
                            key = rs.getString("object_key"),
                            versionId = rs.getString("version_id"),
                            isLatest = rs.getBoolean("is_latest"),
                            isDeleteMarker = rs.getBoolean("is_delete_marker"),
                            etag = rs.getString("etag"),
                            sizeBytes = rs.getLong("size_bytes"),
                            lastModified = rs.getTimestamp("created_at").toInstant(),
                            storageClass = rs.getString("storage_class"),
                        )
                }
                out
            }
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
        encodingType: String?,
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

        val sql =
            buildString {
                append("SELECT object_key, etag, size_bytes, created_at, storage_class ")
                append("FROM objects ")
                append("WHERE bucket = ? AND deleted_at IS NULL AND is_latest = TRUE AND is_delete_marker = FALSE ")
                if (prefix != null) append("AND object_key LIKE ? ESCAPE '\\' ")
                if (startAfterKey != null) append("AND object_key > ? ")
                append("ORDER BY object_key ASC ")
                append("LIMIT ?")
            }

        val rawRows: List<ListObject> =
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setString(idx++, bucket)
                if (prefix != null) ps.setString(idx++, likeEscape(prefix))
                if (startAfterKey != null) ps.setString(idx++, startAfterKey)
                ps.setInt(idx, fetchLimit)
                ps.executeQuery().use { rs ->
                    val out = ArrayList<ListObject>()
                    while (rs.next()) {
                        out +=
                            ListObject(
                                key = rs.getString("object_key"),
                                etag = rs.getString("etag"),
                                sizeBytes = rs.getLong("size_bytes"),
                                lastModified = rs.getTimestamp("created_at").toInstant(),
                                storageClass = rs.getString("storage_class"),
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
                val afterPrefix =
                    if (prefix != null && row.key.startsWith(prefix)) {
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
        data class Entry(
            val sortKey: String,
            val isPrefix: Boolean,
            val obj: ListObject?,
            val prefix: String?,
        )
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
            encodingType = encodingType,
        )
    }

    override fun setBucketVersioning(
        conn: Connection,
        bucket: String,
        status: String,
    ): Boolean =
        conn
            .prepareStatement("UPDATE buckets SET versioning_status = ? WHERE name = ? AND deleted_at IS NULL")
            .use { ps ->
                ps.setString(1, status.uppercase())
                ps.setString(2, bucket)
                ps.executeUpdate() > 0
            }

    override fun setBucketObjectLock(
        conn: Connection,
        bucket: String,
        enabled: Boolean,
        defaultRetentionMode: String?,
        defaultRetentionDays: Int?,
    ): Boolean =
        conn
            .prepareStatement(
                """
                UPDATE buckets SET object_lock_enabled = object_lock_enabled OR ?,
                    default_retention_mode = ?, default_retention_days = ?
                WHERE name = ? AND deleted_at IS NULL
                """.trimIndent(),
            ).use { ps ->
                ps.setBoolean(1, enabled)
                ps.setString(2, defaultRetentionMode)
                setNullableInt(ps, 3, defaultRetentionDays)
                ps.setString(4, bucket)
                ps.executeUpdate() > 0
            }

    override fun putRetention(
        conn: Connection,
        bucket: String,
        key: String,
        versionId: String,
        mode: String,
        retainUntil: Instant,
    ): Boolean =
        conn
            .prepareStatement(
                """
                UPDATE objects SET retention_mode = ?, retain_until = ?
                WHERE bucket = ? AND object_key = ? AND version_id = ? AND deleted_at IS NULL AND is_delete_marker = FALSE
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, mode.uppercase())
                ps.setTimestamp(2, Timestamp.from(retainUntil))
                ps.setString(3, bucket)
                ps.setString(4, key)
                ps.setString(5, versionId)
                ps.executeUpdate() > 0
            }

    override fun putLegalHold(
        conn: Connection,
        bucket: String,
        key: String,
        versionId: String,
        enabled: Boolean,
    ): Boolean =
        conn
            .prepareStatement(
                """
                UPDATE objects SET legal_hold = ?
                WHERE bucket = ? AND object_key = ? AND version_id = ? AND deleted_at IS NULL AND is_delete_marker = FALSE
                """.trimIndent(),
            ).use { ps ->
                ps.setBoolean(1, enabled)
                ps.setString(2, bucket)
                ps.setString(3, key)
                ps.setString(4, versionId)
                ps.executeUpdate() > 0
            }

    override fun replaceLifecycleRules(
        conn: Connection,
        bucket: String,
        rules: List<LifecycleRuleRecord>,
    ) {
        conn.prepareStatement("DELETE FROM bucket_lifecycle_rules WHERE bucket = ?").use { ps ->
            ps.setString(1, bucket)
            ps.executeUpdate()
        }
        conn
            .prepareStatement(
                """
                INSERT INTO bucket_lifecycle_rules(
                    bucket, rule_id, enabled, prefix, current_version_expiration_days,
                    noncurrent_version_expiration_days, abort_incomplete_multipart_days, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, now())
                """.trimIndent(),
            ).use { ps ->
                for (rule in rules) {
                    ps.setString(1, bucket)
                    ps.setString(2, rule.ruleId)
                    ps.setBoolean(3, rule.enabled)
                    ps.setString(4, rule.prefix)
                    setNullableInt(ps, 5, rule.currentVersionExpirationDays)
                    setNullableInt(ps, 6, rule.noncurrentVersionExpirationDays)
                    setNullableInt(ps, 7, rule.abortIncompleteMultipartDays)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
    }

    override fun listLifecycleRules(
        conn: Connection,
        bucket: String,
    ): List<LifecycleRuleRecord> =
        conn
            .prepareStatement(
                """
                SELECT bucket, rule_id, enabled, prefix, current_version_expiration_days,
                       noncurrent_version_expiration_days, abort_incomplete_multipart_days
                FROM bucket_lifecycle_rules WHERE bucket = ? ORDER BY rule_id
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, bucket)
                ps.executeQuery().use { rs ->
                    val out = ArrayList<LifecycleRuleRecord>()
                    while (rs.next()) {
                        out +=
                            LifecycleRuleRecord(
                                bucket = rs.getString("bucket"),
                                ruleId = rs.getString("rule_id"),
                                enabled = rs.getBoolean("enabled"),
                                prefix = rs.getString("prefix"),
                                currentVersionExpirationDays = rs.getObject("current_version_expiration_days") as Int?,
                                noncurrentVersionExpirationDays = rs.getObject("noncurrent_version_expiration_days") as Int?,
                                abortIncompleteMultipartDays = rs.getObject("abort_incomplete_multipart_days") as Int?,
                            )
                    }
                    out
                }
            }

    override fun expireLifecycleObjects(
        conn: Connection,
        batchSize: Int,
        now: Instant,
    ): Int {
        val sql =
            """
            WITH candidates AS (
                SELECT o.bucket, o.object_key, o.version_id
                FROM objects o
                JOIN bucket_lifecycle_rules r ON r.bucket = o.bucket AND r.enabled = TRUE
                WHERE o.deleted_at IS NULL
                  AND o.is_delete_marker = FALSE
                  AND o.legal_hold = FALSE
                  AND (o.retain_until IS NULL OR o.retain_until <= ?)
                  AND (r.prefix IS NULL OR o.object_key LIKE r.prefix || '%' ESCAPE '\')
                  AND (
                    (o.is_latest = TRUE AND r.current_version_expiration_days IS NOT NULL
                     AND o.created_at <= CAST(? AS TIMESTAMPTZ) - make_interval(days => r.current_version_expiration_days))
                    OR
                    (o.is_latest = FALSE AND r.noncurrent_version_expiration_days IS NOT NULL
                     AND o.created_at <= CAST(? AS TIMESTAMPTZ) - make_interval(days => r.noncurrent_version_expiration_days))
                  )
                ORDER BY o.created_at
                LIMIT ?
            )
            UPDATE objects o SET deleted_at = ?, is_latest = FALSE
            FROM candidates c
            WHERE o.bucket = c.bucket AND o.object_key = c.object_key AND o.version_id = c.version_id
            """.trimIndent()
        return conn.prepareStatement(sql).use { ps ->
            val ts = Timestamp.from(now)
            ps.setTimestamp(1, ts)
            ps.setTimestamp(2, ts)
            ps.setTimestamp(3, ts)
            ps.setInt(4, batchSize)
            ps.setTimestamp(5, ts)
            ps.executeUpdate()
        }
    }

    private fun getObjectRow(
        conn: Connection,
        bucket: String,
        key: String,
        versionId: String,
    ): ObjectMetadata? =
        conn
            .prepareStatement(
                """
                SELECT bucket, object_key, blob_path, blob_sha256, etag, size_bytes,
                       content_type, content_encoding, content_language, cache_control,
                       content_disposition, expires, user_metadata, version_id, storage_class,
                       created_at,
                       checksum_crc32, checksum_crc32c, checksum_sha1, checksum_sha256, checksum_type,
                       encryption_mode, encryption_key_id, encryption_nonce,
                       is_delete_marker, retention_mode, retain_until, legal_hold
                FROM objects
                WHERE bucket = ? AND object_key = ? AND version_id = ? AND deleted_at IS NULL
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, bucket)
                ps.setString(2, key)
                ps.setString(3, versionId)
                ps.executeQuery().use { rs -> if (!rs.next()) null else rs.toObjectMeta() }
            }

    private fun isLatestObjectVersion(
        conn: Connection,
        bucket: String,
        key: String,
        versionId: String,
    ): Boolean =
        conn
            .prepareStatement(
                "SELECT is_latest FROM objects WHERE bucket = ? AND object_key = ? AND version_id = ? AND deleted_at IS NULL",
            ).use { ps ->
                ps.setString(1, bucket)
                ps.setString(2, key)
                ps.setString(3, versionId)
                ps.executeQuery().use { rs -> rs.next() && rs.getBoolean("is_latest") }
            }

    private fun promoteNewestVersion(
        conn: Connection,
        bucket: String,
        key: String,
    ) {
        conn
            .prepareStatement(
                """
                UPDATE objects SET is_latest = TRUE
                WHERE bucket = ? AND object_key = ? AND version_id = (
                    SELECT version_id FROM objects
                    WHERE bucket = ? AND object_key = ? AND deleted_at IS NULL
                    ORDER BY created_at DESC
                    LIMIT 1
                )
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, bucket)
                ps.setString(2, key)
                ps.setString(3, bucket)
                ps.setString(4, key)
                ps.executeUpdate()
            }
    }

    /**
     * Escape a string for use in a SQL LIKE pattern: `\` and `%` and `_` are
     * escaped so the prefix is matched literally. The caller must use
     * `ESCAPE '\'` in the SQL.
     */
    private fun likeEscape(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    private fun urlEncode(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            when {
                c == 0x2f -> sb.append('/') // '/' — S3 preserves in encoding-type=url
                c in 0x30..0x39 ||
                    c in 0x41..0x5a ||
                    c in 0x61..0x7a ||
                    c == 0x2d ||
                    c == 0x5f ||
                    c == 0x2e ||
                    c == 0x7e -> sb.append(c.toChar())
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
        checksumAlgorithm: String?,
    ) {
        conn
            .prepareStatement(
                """
                INSERT INTO multipart_uploads(
                    upload_id, bucket, object_key, content_type, user_metadata, storage_class, state,
                    content_encoding, content_language, cache_control, content_disposition, expires, checksum_algorithm
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?, 'INITIATED', ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
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

    override fun getMultipartUpload(
        conn: Connection,
        uploadId: String,
    ): MultipartUploadInfo? {
        val sql =
            """
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
        checksumSha256: String?,
        encryptionMode: String?,
        encryptionKeyId: String?,
        encryptionNonce: ByteArray?,
    ): PartInfo {
        // Upsert: re-uploading the same part number overwrites the previous part.
        val sql =
            """
            INSERT INTO multipart_parts(upload_id, part_number, blob_path, blob_sha256, etag, size_bytes,
                checksum_crc32, checksum_crc32c, checksum_sha1, checksum_sha256,
                encryption_mode, encryption_key_id, encryption_nonce)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (upload_id, part_number) DO UPDATE SET
                blob_path = EXCLUDED.blob_path,
                blob_sha256 = EXCLUDED.blob_sha256,
                etag = EXCLUDED.etag,
                size_bytes = EXCLUDED.size_bytes,
                uploaded_at = now(),
                checksum_crc32 = EXCLUDED.checksum_crc32,
                checksum_crc32c = EXCLUDED.checksum_crc32c,
                checksum_sha1 = EXCLUDED.checksum_sha1,
                checksum_sha256 = EXCLUDED.checksum_sha256,
                encryption_mode = EXCLUDED.encryption_mode,
                encryption_key_id = EXCLUDED.encryption_key_id,
                encryption_nonce = EXCLUDED.encryption_nonce
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
            ps.setString(11, encryptionMode)
            ps.setString(12, encryptionKeyId)
            ps.setBytes(13, encryptionNonce)
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
            checksumSha256 = checksumSha256,
            encryptionMode = encryptionMode,
            encryptionKeyId = encryptionKeyId,
            encryptionNonce = encryptionNonce,
        )
    }

    fun uploadPart(
        conn: Connection,
        uploadId: String,
        partNumber: Int,
        blobPath: String,
        blobSha256Hex: String,
        etag: String,
        sizeBytes: Long,
    ): PartInfo =
        uploadPart(
            conn = conn,
            uploadId = uploadId,
            partNumber = partNumber,
            blobPath = blobPath,
            blobSha256Hex = blobSha256Hex,
            etag = etag,
            sizeBytes = sizeBytes,
            checksumCrc32 = null,
            checksumCrc32C = null,
            checksumSha1 = null,
            checksumSha256 = null,
            encryptionMode = null,
            encryptionKeyId = null,
            encryptionNonce = null,
        )

    override fun getPart(
        conn: Connection,
        uploadId: String,
        partNumber: Int,
    ): PartInfo? {
        val sql =
            """
            SELECT part_number, blob_path, blob_sha256, etag, size_bytes, uploaded_at,
                   checksum_crc32, checksum_crc32c, checksum_sha1, checksum_sha256,
                   encryption_mode, encryption_key_id, encryption_nonce
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

    override fun listParts(
        conn: Connection,
        uploadId: String,
    ): List<PartInfo> {
        val sql =
            """
            SELECT part_number, blob_path, blob_sha256, etag, size_bytes, uploaded_at,
                   checksum_crc32, checksum_crc32c, checksum_sha1, checksum_sha256,
                   encryption_mode, encryption_key_id, encryption_nonce
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

    override fun deleteParts(
        conn: Connection,
        uploadId: String,
    ): Int =
        conn.prepareStatement("DELETE FROM multipart_parts WHERE upload_id = ?").use { ps ->
            ps.setString(1, uploadId)
            ps.executeUpdate()
        }

    override fun completeMultipartUpload(
        conn: Connection,
        uploadId: String,
    ): Boolean =
        conn
            .prepareStatement(
                "UPDATE multipart_uploads SET state = 'COMPLETED', completed_at = now() " +
                    "WHERE upload_id = ? AND state = 'COMPLETING'",
            ).use { ps ->
                ps.setString(1, uploadId)
                ps.executeUpdate() > 0
            }

    override fun markMultipartCompleting(
        conn: Connection,
        uploadId: String,
    ): Boolean =
        conn
            .prepareStatement(
                "UPDATE multipart_uploads SET state = 'COMPLETING', completing_at = now() " +
                    "WHERE upload_id = ? AND state = 'INITIATED'",
            ).use { ps ->
                ps.setString(1, uploadId)
                ps.executeUpdate() > 0
            }

    override fun abortMultipartUpload(
        conn: Connection,
        uploadId: String,
    ): Boolean =
        conn
            .prepareStatement(
                "UPDATE multipart_uploads SET state = 'ABORTED', aborted_at = now() " +
                    "WHERE upload_id = ? AND state = 'INITIATED'",
            ).use { ps ->
                ps.setString(1, uploadId)
                ps.executeUpdate() > 0
            }

    override fun failMultipartCompletion(
        conn: Connection,
        uploadId: String,
    ): Boolean =
        conn
            .prepareStatement(
                "UPDATE multipart_uploads SET state = 'FAILED_COMPLETION' " +
                    "WHERE upload_id = ? AND state = 'COMPLETING'",
            ).use { ps ->
                ps.setString(1, uploadId)
                ps.executeUpdate() > 0
            }

    override fun listStaleMultipartUploads(
        conn: Connection,
        olderThanSeconds: Long,
    ): List<StaleMultipart> {
        val cutoff = staleCutoff(olderThanSeconds) ?: return emptyList()
        val sql =
            """
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
                    out +=
                        StaleMultipart(
                            uploadId = rs.getString("upload_id"),
                            bucket = rs.getString("bucket"),
                            key = rs.getString("object_key"),
                            initiatedAt = rs.getTimestamp("initiated_at").toInstant(),
                        )
                }
                out
            }
        }
    }

    override fun markMultipartAborted(
        conn: Connection,
        uploadId: String,
    ) {
        conn
            .prepareStatement(
                "UPDATE multipart_uploads SET state = 'ABORTED', aborted_at = now() WHERE upload_id = ?",
            ).use { ps ->
                ps.setString(1, uploadId)
                ps.executeUpdate()
            }
    }

    override fun listMultipartUploads(
        conn: Connection,
        bucket: String?,
    ): List<MultipartUploadInfo> {
        val sql =
            if (bucket != null) {
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

    override fun createBlobWriteIntent(
        conn: Connection,
        blobSha256Hex: String,
    ): String {
        val intentId = UUID.randomUUID().toString()
        conn
            .prepareStatement(
                "INSERT INTO blob_write_intents(intent_id, blob_sha256_hex) VALUES (?, ?)",
            ).use { ps ->
                ps.setString(1, intentId)
                ps.setString(2, blobSha256Hex)
                ps.executeUpdate()
            }
        return intentId
    }

    override fun clearBlobWriteIntent(
        conn: Connection,
        intentId: String,
    ): Boolean =
        conn.prepareStatement("DELETE FROM blob_write_intents WHERE intent_id = ?").use { ps ->
            ps.setString(1, intentId)
            ps.executeUpdate() > 0
        }

    override fun deleteStaleBlobWriteIntents(
        conn: Connection,
        olderThanSeconds: Long,
    ): Int {
        val cutoff = staleCutoff(olderThanSeconds) ?: return 0
        return conn
            .prepareStatement(
                "DELETE FROM blob_write_intents " +
                    "WHERE created_at < ?",
            ).use { ps ->
                ps.setTimestamp(1, cutoff)
                ps.executeUpdate()
            }
    }

    override fun upsertAccessKey(
        conn: Connection,
        accessKeyId: String,
        secretAccessKey: String,
        description: String?,
    ): Unit =
        throw UnsupportedOperationException(
            "Plaintext access-key storage is not supported; use upsertAccessKeyRecord with encrypted secret fields",
        )

    override fun lookupSecret(
        conn: Connection,
        accessKeyId: String,
    ): String? = null

    override fun upsertAccessKeyRecord(
        conn: Connection,
        record: AccessKeyRecord,
    ) {
        conn
            .prepareStatement(
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
                    "rotated_at = COALESCE(EXCLUDED.rotated_at, access_keys.rotated_at)",
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

    override fun lookupAccessKey(
        conn: Connection,
        accessKeyId: String,
    ): AccessKeyRecord? =
        conn
            .prepareStatement(
                "SELECT access_key_id, secret_access_key, secret_ciphertext, secret_nonce, secret_key_id, " +
                    "description, state, created_at, updated_at, disabled_at, deleted_at, rotated_at " +
                    "FROM access_keys WHERE access_key_id = ? AND state = 'ACTIVE' AND deleted_at IS NULL",
            ).use { ps ->
                ps.setString(1, accessKeyId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toAccessKeyRecord() else null }
            }

    override fun listAccessKeys(
        conn: Connection,
        includeDeleted: Boolean,
    ): List<AccessKeyRecord> {
        val sql =
            "SELECT access_key_id, secret_access_key, secret_ciphertext, secret_nonce, secret_key_id, " +
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

    override fun updateAccessKeyState(
        conn: Connection,
        accessKeyId: String,
        state: String,
    ): Boolean {
        val now = Timestamp.from(Instant.now())
        val disabledAt = if (state == "DISABLED") "disabled_at = COALESCE(disabled_at, ?), " else "disabled_at = NULL, "
        return conn
            .prepareStatement(
                "UPDATE access_keys SET state = ?, updated_at = now(), $disabledAt deleted_at = NULL " +
                    "WHERE access_key_id = ? AND deleted_at IS NULL",
            ).use { ps ->
                ps.setString(1, state)
                var i = 2
                if (state == "DISABLED") ps.setTimestamp(i++, now)
                ps.setString(i, accessKeyId)
                ps.executeUpdate() > 0
            }
    }

    override fun softDeleteAccessKey(
        conn: Connection,
        accessKeyId: String,
    ): Boolean =
        conn
            .prepareStatement(
                "UPDATE access_keys SET state = 'DELETED', deleted_at = COALESCE(deleted_at, now()), updated_at = now() " +
                    "WHERE access_key_id = ? AND deleted_at IS NULL",
            ).use { ps ->
                ps.setString(1, accessKeyId)
                ps.executeUpdate() > 0
            }

    override fun insertAuditEvent(
        conn: Connection,
        event: AuditEventRecord,
    ) {
        conn
            .prepareStatement(
                "INSERT INTO audit_events(" +
                    "event_id, request_id, access_key_id, operation, bucket, object_key, status, latency_ms, source, detail" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
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

    override fun listAuditEvents(
        conn: Connection,
        limit: Int,
    ): List<AuditEventRecord> =
        conn
            .prepareStatement(
                "SELECT event_id, occurred_at, request_id, access_key_id, operation, bucket, object_key, " +
                    "status, latency_ms, source, detail::text AS detail_json " +
                    "FROM audit_events ORDER BY occurred_at DESC LIMIT ?",
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
                                ),
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
    setString(i++, meta.checksumType)
    setString(i++, meta.encryptionMode)
    setString(i++, meta.encryptionKeyId)
    setBytes(i++, meta.encryptionNonce)
    setBoolean(i++, meta.isDeleteMarker)
    setString(i++, meta.retentionMode)
    if (meta.retainUntil == null) {
        setNull(i++, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
    } else {
        setTimestamp(i++, Timestamp.from(meta.retainUntil))
    }
    setBoolean(i, meta.legalHold)
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
        checksumType = getString("checksum_type"),
        encryptionMode = getString("encryption_mode"),
        encryptionKeyId = getString("encryption_key_id"),
        encryptionNonce = getBytes("encryption_nonce"),
        isDeleteMarker = getBoolean("is_delete_marker"),
        retentionMode = getString("retention_mode"),
        retainUntil = getTimestamp("retain_until")?.toInstant(),
        legalHold = getBoolean("legal_hold"),
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
        checksumAlgorithm = getString("checksum_algorithm"),
    )
}

private fun ResultSet.toPartInfo(): PartInfo =
    PartInfo(
        partNumber = getInt("part_number"),
        blobPath = getString("blob_path"),
        blobSha256Hex = bytesToHex(getBytes("blob_sha256")),
        etag = getString("etag"),
        sizeBytes = getLong("size_bytes"),
        uploadedAt = getTimestamp("uploaded_at").toInstant(),
        checksumCrc32 = getString("checksum_crc32"),
        checksumCrc32C = getString("checksum_crc32c"),
        checksumSha1 = getString("checksum_sha1"),
        checksumSha256 = getString("checksum_sha256"),
        encryptionMode = getString("encryption_mode"),
        encryptionKeyId = getString("encryption_key_id"),
        encryptionNonce = getBytes("encryption_nonce"),
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

fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

private fun setNullableInt(
    ps: PreparedStatement,
    index: Int,
    value: Int?,
) {
    if (value == null) {
        ps.setNull(index, java.sql.Types.INTEGER)
    } else {
        ps.setInt(index, value)
    }
}

fun Map<String, String>.toJson(): String {
    if (this.isEmpty()) return "{}"
    val sb = StringBuilder("{")
    entries.forEachIndexed { idx, (k, v) ->
        if (idx > 0) sb.append(',')
        sb
            .append('"')
            .append(k.escapeJson())
            .append("\":\"")
            .append(v.escapeJson())
            .append('"')
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

private fun readJsonString(
    s: String,
    start: Int,
): Pair<String, Int> {
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
        for (c in this@escapeJson) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

fun newUploadId(): String =
    UUID
        .randomUUID()
        .toString()
        .replace("-", "")
        .lowercase()
