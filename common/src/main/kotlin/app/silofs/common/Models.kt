package app.silofs.common

/**
 * Pure-data view of an object as returned by the metadata layer. The server
 * module copies fields onto the HTTP response.
 *
 * M2.1 additions: [checksumCrc32], [checksumCrc32C], [checksumSha1],
 * [checksumSha256] are persisted from the `x-amz-checksum-*` request headers
 * on PUT and echoed on GET/HEAD. Each value is the base64-encoded checksum
 * (the format AWS uses on the wire).
 */
data class ObjectMetadata(
    val bucket: String,
    val key: String,
    val blobPath: String,
    val blobSha256Hex: String,
    val etag: String,
    val sizeBytes: Long,
    val contentType: String,
    val contentEncoding: String?,
    val contentLanguage: String?,
    val cacheControl: String?,
    val contentDisposition: String?,
    val expires: String?,
    val userMetadata: Map<String, String>,
    val versionId: String,
    val storageClass: String,
    val createdAt: java.time.Instant,
    val checksumCrc32: String? = null,
    val checksumCrc32C: String? = null,
    val checksumSha1: String? = null,
    val checksumSha256: String? = null,
    val checksumType: String? = null,
    val encryptionMode: String? = null,
    val encryptionKeyId: String? = null,
    val encryptionNonce: ByteArray? = null,
    val isDeleteMarker: Boolean = false,
    val retentionMode: String? = null,
    val retainUntil: java.time.Instant? = null,
    val legalHold: Boolean = false,
)

data class BucketInfo(
    val name: String,
    val region: String,
    val ownerId: String,
    val createdAt: java.time.Instant,
    val versioningStatus: String = "DISABLED",
    val objectLockEnabled: Boolean = false,
    val defaultRetentionMode: String? = null,
    val defaultRetentionDays: Int? = null,
)

/**
 * Result of a `ListObjectsV2` call. The continuation token is opaque to the
 * client — internally it is the `object_key` of the last row returned.
 *
 * M2.1: [prefix], [delimiter], [commonPrefixes], and [encodingType] are now
 * honoured. When [encodingType] is `"url"`, the keys in [contents] and the
 * entries in [commonPrefixes] are returned URL-encoded — the caller is
 * responsible for decoding them when comparing against the raw keys.
 */
data class ListObjectsV2Result(
    val bucket: String,
    val contents: List<ListObject>,
    val isTruncated: Boolean,
    val continuationToken: String?,
    val nextContinuationToken: String?,
    val keyCount: Int,
    val maxKeys: Int,
    val prefix: String?,
    val delimiter: String?,
    val commonPrefixes: List<String>,
    val startAfter: String?,
    val encodingType: String? = null,
)

data class ListObject(
    val key: String,
    val etag: String,
    val sizeBytes: Long,
    val lastModified: java.time.Instant,
    val storageClass: String,
)

data class ObjectVersion(
    val key: String,
    val versionId: String,
    val isLatest: Boolean,
    val isDeleteMarker: Boolean,
    val etag: String?,
    val sizeBytes: Long,
    val lastModified: java.time.Instant,
    val storageClass: String,
)
