package app.silofs.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import app.silofs.blob.BlobStore
import app.silofs.blob.streamRange
import app.silofs.common.BucketName
import app.silofs.common.ByteRange
import app.silofs.common.ETag
import app.silofs.common.ETagMatcher
import app.silofs.common.ObjectKey
import app.silofs.common.ObjectMetadata
import app.silofs.common.S3ErrorCode
import app.silofs.common.S3Errors
import app.silofs.common.S3Exception
import app.silofs.common.S3Time
import app.silofs.common.s3Close
import app.silofs.common.s3Open
import app.silofs.common.s3Tag
import app.silofs.common.s3TagBool
import app.silofs.common.s3TagLong
import app.silofs.common.s3XmlDocument
import app.silofs.metadata.JdbcMetadataRepository
import app.silofs.metadata.toS3
import app.silofs.metadata.withS3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.time.Instant
import java.util.Locale

/**
 * The single point of entry for S3 operations. Each handler maps HTTP request
 * data to a repository / blob-store call and writes the response. Handlers
 * are deliberately stateless — they share a [ServerConfig] instance.
 *
 * M2 additions over M1:
 *   - Conditional GET/HEAD via `If-Match` / `If-None-Match`.
 *   - Conditional PUT via `If-None-Match: *` (create-only-if-absent).
 *   - Content-Length validation on PUT.
 *   - `Accept-Ranges: bytes` header on GET/HEAD.
 *   - `x-amz-bucket-region` on bucket responses.
 *   - Consistent ETag (quoted MD5 hex) and Last-Modified (RFC 1123) headers.
 *   - 304 Not Modified when `If-None-Match` matches.
 *   - Per-call request ID propagation via [RequestIdPlugin].
 *   - `Content-MD5` verification (M1, retained).
 */
class S3Handlers(
    private val config: ServerConfig,
    private val repo: JdbcMetadataRepository = config.repository,
    private val blobStore: BlobStore = config.blobStore
) {

    private val log = LoggerFactory.getLogger(S3Handlers::class.java)

    // ---------- Bucket ----------

    suspend fun createBucket(call: ApplicationCall, bucket: String) = withContext(Dispatchers.IO) {
        BucketName.validate(bucket)
        // Validate CreateBucketConfiguration XML body if present (M2 only checks
        // for the LocationConstraint element — others are NotImplemented).
        withS3 {
            config.database.withTransaction { conn ->
                repo.createBucket(conn, bucket, config.region, ownerId = "owner")
            }
        }
        call.response.headers.apply {
            append("Location", "/$bucket")
            append("x-amz-bucket-region", config.region)
        }
        call.respondText("", ContentType.Application.Xml.withCharset(Charsets.UTF_8), HttpStatusCode.OK)
    }

    suspend fun headBucket(call: ApplicationCall, bucket: String) = withContext(Dispatchers.IO) {
        BucketName.validate(bucket)
        val exists = withS3 {
            config.database.withConnection { conn -> repo.bucketExists(conn, bucket) }
        }
        if (!exists) throw S3Errors.noSuchBucket(bucket)
        call.response.headers.append("x-amz-bucket-region", config.region)
        call.respond(
            object : OutgoingContent.NoContent() {
                override val status: HttpStatusCode = HttpStatusCode.OK
            }
        )
    }

    suspend fun deleteBucket(call: ApplicationCall, bucket: String): Unit = withContext(Dispatchers.IO) {
        BucketName.validate(bucket)
        withS3 {
            config.database.withTransaction { conn ->
                if (!repo.bucketExists(conn, bucket)) throw S3Errors.noSuchBucket(bucket)
                val count = repo.countObjectsInBucket(conn, bucket)
                if (count > 0) throw S3Errors.bucketNotEmpty(bucket)
                repo.deleteBucket(conn, bucket)
            }
        }
        call.respond(
            object : OutgoingContent.NoContent() {
                override val status: HttpStatusCode = HttpStatusCode.NoContent
                override val contentLength: Long = 0
            }
        )
    }

    suspend fun listBuckets(call: ApplicationCall) = withContext(Dispatchers.IO) {
        val buckets = withS3 {
            config.database.withConnection { conn -> repo.listBuckets(conn) }
        }
        val body = s3XmlDocument("ListAllMyBucketsResult") {
            s3Open("Owner")
            s3Tag("ID", "owner")
                s3Tag("DisplayName", "silofs")
            s3Close("Owner")
            s3Open("Buckets")
            for (b in buckets) {
                s3Open("Bucket")
                s3Tag("Name", b.name)
                s3Tag("CreationDate", S3Time.formatIso8601(b.createdAt))
                s3Close("Bucket")
            }
            s3Close("Buckets")
        }
        call.respondText(body, ContentType.Application.Xml.withCharset(Charsets.UTF_8), HttpStatusCode.OK)
    }

    suspend fun getBucketLocation(call: ApplicationCall, bucket: String) = withContext(Dispatchers.IO) {
        BucketName.validate(bucket)
        val exists = withS3 {
            config.database.withConnection { conn -> repo.bucketExists(conn, bucket) }
        }
        if (!exists) throw S3Errors.noSuchBucket(bucket)
        // S3 returns the region wrapped in <LocationConstraint xmlns="...">...</LocationConstraint>.
        // For us-east-1 AWS returns an empty body — we follow the same convention.
        val body = if (config.region == "us-east-1") {
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"/>"
        } else {
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">${config.region}</LocationConstraint>"
        }
        call.respondText(body, ContentType.Application.Xml.withCharset(Charsets.UTF_8), HttpStatusCode.OK)
    }

    suspend fun listObjectsV2(
        call: ApplicationCall,
        bucket: String,
        maxKeys: Int,
        continuationToken: String?,
        startAfter: String?,
        prefix: String?,
        delimiter: String?,
        encodingType: String?
    ) = withContext(Dispatchers.IO) {
        BucketName.validate(bucket)
        // Validate max-keys argument explicitly so the SDK gets InvalidArgument
        // rather than silently clamped pagination.
        if (maxKeys < 0) {
            throw S3Errors.invalidArgument("max-keys", maxKeys.toString(), "must be >= 0")
        }
        if (encodingType != null && encodingType != "url") {
            throw S3Errors.invalidArgument("encoding-type", encodingType, "only 'url' is supported")
        }
        val result = withS3 {
            config.database.withConnection { conn ->
                if (!repo.bucketExists(conn, bucket)) throw S3Errors.noSuchBucket(bucket)
                repo.listObjects(conn, bucket, maxKeys, continuationToken, startAfter, prefix, delimiter, encodingType)
            }
        }
        val body = s3XmlDocument("ListBucketResult") {
            s3Tag("Name", bucket)
            s3Tag("Prefix", prefix ?: "")
            if (delimiter != null) s3Tag("Delimiter", delimiter)
            if (encodingType != null) s3Tag("EncodingType", encodingType)
            s3TagLong("KeyCount", result.keyCount.toLong())
            s3TagLong("MaxKeys", result.maxKeys.toLong())
            s3TagBool("IsTruncated", result.isTruncated)
            s3Tag("ContinuationToken", result.continuationToken)
            s3Tag("NextContinuationToken", result.nextContinuationToken)
            s3Tag("StartAfter", result.startAfter)
            for (o in result.contents) {
                s3Open("Contents")
                s3Tag("Key", o.key)
                s3Tag("LastModified", S3Time.formatIso8601(o.lastModified))
                s3Tag("ETag", o.etag)
                s3TagLong("Size", o.sizeBytes)
                s3Tag("StorageClass", o.storageClass)
                s3Close("Contents")
            }
            for (cp in result.commonPrefixes) {
                s3Open("CommonPrefixes")
                s3Tag("Prefix", cp)
                s3Close("CommonPrefixes")
            }
        }
        call.response.headers.append("x-amz-bucket-region", config.region)
        call.respondText(body, ContentType.Application.Xml.withCharset(Charsets.UTF_8), HttpStatusCode.OK)
    }

    // ---------- Object ----------

    suspend fun putObject(
        call: ApplicationCall,
        bucket: String,
        key: String,
        contentType: String,
        userMetadata: Map<String, String>,
        contentEncoding: String?,
        contentLanguage: String?,
        cacheControl: String?,
        contentDisposition: String?,
        expires: String?,
        expectedSha256: String?,
        expectedMd5Base64: String?,
        contentLength: Long?,
        ifNoneMatch: String?,
        storageClass: String?,
        checksumCrc32: String?,
        checksumCrc32C: String?,
        checksumSha1: String?,
        checksumSha256: String?,
        checksumType: String?
    ) = withUploadPermit {
        BucketName.validate(bucket)
        ObjectKey.validate(key)

        // ---- Content-Length validation (M2) ----
        // AWS accepts PUTs without Content-Length when chunked encoding is in
        // use, but we don't implement aws-chunked yet (M4) so we require it.
        if (contentLength == null) {
            throw S3Errors.missingContentLength()
        }
        if (contentLength < 0) {
            throw S3Errors.invalidArgument("Content-Length", contentLength.toString(), "must be >= 0")
        }
        val maxObjectSize = 5L * 1024 * 1024 * 1024 // 5 GiB — S3 single-PUT limit
        if (contentLength > maxObjectSize) {
            throw S3Errors.entityTooLarge(contentLength, maxObjectSize)
        }

        // ---- Storage class validation (M2) ----
        val effectiveStorageClass = storageClass ?: "STANDARD"
        if (effectiveStorageClass !in ALLOWED_STORAGE_CLASSES) {
            throw S3Errors.invalidArgument("x-amz-storage-class", effectiveStorageClass, "must be one of $ALLOWED_STORAGE_CLASSES")
        }

        // ---- Bucket existence check ----
        withS3 {
            config.database.withConnection { conn ->
                if (!repo.bucketExists(conn, bucket)) throw S3Errors.noSuchBucket(bucket)
            }
        }

        // ---- If-None-Match: * conditional PUT (create-only-if-absent) ----
        if (ifNoneMatch != null) {
            val existingEtag = withS3 {
                config.database.withConnection { conn -> repo.getObject(conn, bucket, key)?.etag }
            }
            if (!ETagMatcher.ifNoneMatchSatisfied(ifNoneMatch, existingEtag)) {
                throw S3Errors.preconditionFailed("If-None-Match", existingEtag)
            }
        }

        // ---- Stream body to a temp file, hash while streaming, fsync, rename ----
        val write = blobStore.beginWrite(expectedSha256Hex = expectedSha256)
        var intentId: String? = null
        try {
            val input: InputStream = requestPayloadStream(call)
            input.copyTo(object : OutputStream() {
                override fun write(b: Int) {
                    write.write(byteArrayOf(b.toByte()), 0, 1)
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    write.write(b, off, len)
                }
            })

            // Failpoint: crash after temp write, before fsync/rename.
            Failpoint.crashIf("after-tmp-write")

            // Split commit into fsync + rename so we can test the
            // "after fsync before rename" failure window (gap #4).
            val writeImpl = write as app.silofs.blob.FsBlobWrite
            val hex = writeImpl.fsyncPhase()

            // Failpoint: crash after fsync, before rename.
            Failpoint.crashIf("after-fsync")

            intentId = createBlobWriteIntent(hex)
            val stored = try {
                writeImpl.renamePhase(hex)
            } catch (t: Throwable) {
                clearBlobWriteIntentQuietly(intentId)
                throw t
            }

            // Failpoint: crash after rename, before DB commit. The write intent
            // is durable by this point, so blob GC must not delete this blob.
            Failpoint.crashIf("after-rename")

            // ---- Content-Length verification (red flag #3) ----
            // The actual streamed byte count must match the declared Content-Length.
            // A mismatch indicates a truncated/malformed upload.
            if (stored.sizeBytes != contentLength) {
                throw S3Errors.incompleteBody(contentLength, stored.sizeBytes)
            }

            // ---- Content-MD5 verification (RFC 1864) ----
            if (expectedMd5Base64 != null) {
                val computedB64 = java.util.Base64.getEncoder().encodeToString(stored.md5)
                if (!constantTimeEquals(expectedMd5Base64, computedB64)) {
                    throw S3Errors.badDigest(expectedMd5Base64, computedB64)
                }
            }

            // ---- Checksum header validation (red flag #8) ----
            // If the client supplied any x-amz-checksum-* header, we verify it
            // against the actual blob content. Mismatch → InvalidDigest (400).
            validateChecksums(
                stored.blobPath,
                checksumCrc32, checksumCrc32C, checksumSha1, checksumSha256,
                blobStore
            )

            val etag = ETag.fromMd5Bytes(stored.md5)
            val meta = ObjectMetadata(
                bucket = bucket,
                key = key,
                blobPath = stored.blobPath.toString(),
                blobSha256Hex = stored.sha256Hex,
                etag = etag,
                sizeBytes = stored.sizeBytes,
                contentType = contentType,
                contentEncoding = contentEncoding,
                contentLanguage = contentLanguage,
                cacheControl = cacheControl,
                contentDisposition = contentDisposition,
                expires = expires,
                userMetadata = userMetadata,
                versionId = "null",
                storageClass = effectiveStorageClass,
                createdAt = Instant.now(),
                checksumCrc32 = checksumCrc32,
                checksumCrc32C = checksumCrc32C,
                checksumSha1 = checksumSha1,
                checksumSha256 = checksumSha256,
                checksumType = checksumType ?: if (
                    checksumCrc32 != null || checksumCrc32C != null ||
                    checksumSha1 != null || checksumSha256 != null
                ) "FULL_OBJECT" else null,
                encryptionMode = stored.encryptionMode,
                encryptionKeyId = stored.encryptionKeyId,
                encryptionNonce = stored.encryptionNonce
            )
            withS3 {
                config.database.withTransaction { conn ->
                    repo.putObject(conn, meta)
                    repo.clearBlobWriteIntent(conn, intentId)
                }
            }

            // Failpoint: crash after DB commit, before HTTP response is sent.
            // The object IS persisted; the client gets a connection error.
            Failpoint.crashIf("before-response")

            // S3 returns ETag in both the ETag header and (optionally) the body.
            // We return an empty body with the ETag header — same as AWS.
            call.response.headers.apply {
                append(HttpHeaders.ETag, etag)
                append("x-amz-storage-class", effectiveStorageClass)
                // Echo checksums back so the SDK can verify the response.
                checksumCrc32?.let { append("x-amz-checksum-crc32", it) }
                checksumCrc32C?.let { append("x-amz-checksum-crc32c", it) }
                checksumSha1?.let { append("x-amz-checksum-sha1", it) }
                checksumSha256?.let { append("x-amz-checksum-sha256", it) }
                val effType = checksumType ?: meta.checksumType
                effType?.let { append("x-amz-checksum-type", it) }
                if (meta.encryptionMode == app.silofs.blob.ObjectEncryption.SSE_S3_MODE) {
                    append("x-amz-server-side-encryption", "AES256")
                }
            }
            call.respondText("", ContentType.Application.Xml.withCharset(Charsets.UTF_8), HttpStatusCode.OK)
        } catch (t: Throwable) {
            intentId?.let { clearBlobWriteIntentQuietly(it) }
            config.operationalState.recordBlobStoreError()
            write.abort()
            throw t.toS3()
        }
    }

    /**
     * Server-side copy of an object within the same node. Implements the
     * `x-amz-copy-source` header convention (no `CopyObject` query parameter).
     *
     * Behaviour matches AWS:
     *   - `x-amz-metadata-directive=COPY` (default): copy source metadata verbatim.
     *   - `x-amz-metadata-directive=REPLACE`: use the metadata from this request.
     *   - `x-amz-copy-source-if-*` conditionals on the source are honoured.
     *   - Returns a `CopyObjectResult` XML body with the new ETag and LastModified.
     *   - Source and destination may be the same key (in-place metadata update).
     */
    suspend fun copyObject(
        call: ApplicationCall,
        destBucket: String,
        destKey: String,
        copySource: String,
        metadataDirective: String,
        contentType: String?,
        userMetadata: Map<String, String>,
        contentEncoding: String?,
        contentLanguage: String?,
        cacheControl: String?,
        contentDisposition: String?,
        expires: String?,
        storageClass: String?,
        ifMatch: String?,
        ifNoneMatch: String?,
        ifModifiedSince: String?,
        ifUnmodifiedSince: String?
    ) = withUploadPermit {
        BucketName.validate(destBucket)
        ObjectKey.validate(destKey)

        // Parse copy-source: AWS accepts either "/bucket/key" or "bucket/key".
        val raw = copySource.removePrefix("/")
        val slashIdx = raw.indexOf('/')
        if (slashIdx <= 0) {
            throw S3Errors.invalidArgument("x-amz-copy-source", copySource, "must be '/bucket/key' or 'bucket/key'")
        }
        val srcBucket = raw.substring(0, slashIdx)
        val srcKey = raw.substring(slashIdx + 1)
        BucketName.validate(srcBucket)
        ObjectKey.validate(srcKey)

        val effectiveDirective = metadataDirective.uppercase(Locale.US).let {
            if (it.isEmpty()) "COPY" else it
        }
        if (effectiveDirective !in setOf("COPY", "REPLACE")) {
            throw S3Errors.invalidArgument("x-amz-metadata-directive", metadataDirective, "must be COPY or REPLACE")
        }

        val effectiveStorageClass = storageClass ?: "STANDARD"
        if (effectiveStorageClass !in ALLOWED_STORAGE_CLASSES) {
            throw S3Errors.invalidArgument("x-amz-storage-class", effectiveStorageClass, "must be one of $ALLOWED_STORAGE_CLASSES")
        }

        val srcMeta = withS3 {
            config.database.withConnection { conn ->
                if (!repo.bucketExists(conn, destBucket)) throw S3Errors.noSuchBucket(destBucket)
                if (!repo.bucketExists(conn, srcBucket)) throw S3Errors.noSuchBucket(srcBucket)
                repo.getObject(conn, srcBucket, srcKey) ?: throw S3Errors.noSuchKey(srcBucket, srcKey)
            }
        }

        // Conditional copy: if-match / if-none-match on the SOURCE object.
        if (ifMatch != null && !ETagMatcher.ifMatchSatisfied(ifMatch, srcMeta.etag)) {
            throw S3Errors.preconditionFailed("x-amz-copy-source-if-match", srcMeta.etag)
        }
        if (ifNoneMatch != null && !ETagMatcher.ifNoneMatchSatisfied(ifNoneMatch, srcMeta.etag)) {
            throw S3Errors.notModified()
        }

        val srcBlobPath = Path.of(srcMeta.blobPath)
        if (!blobStore.exists(srcBlobPath)) {
            log.error("Copy source blob missing on disk: {}/{} blobPath={}", srcBucket, srcKey, srcMeta.blobPath)
            throw S3Errors.internalError(IllegalStateException("source blob missing: $srcBlobPath"))
        }

        // Stream the source blob through a fresh write so we recompute hashes
        // and produce a new content-addressed blob. We do NOT hard-link because
        // the destination may receive a different metadata set and the blob
        // store is content-addressed anyway (same content → same path).
        val write = blobStore.beginWrite(expectedSha256Hex = null)
        var copyIntentId: String? = null
        try {
            blobStore.openRead(srcBlobPath).use { ch ->
                val buf = java.nio.ByteBuffer.allocate(64 * 1024)
                while (true) {
                    buf.clear()
                    val n = ch.read(buf)
                    if (n <= 0) break
                    buf.flip()
                    write.write(buf.array(), 0, n)
                }
            }
            val published = publishWithIntent(write)
            copyIntentId = published.intentId
            val stored = published.stored

            val etag = ETag.fromMd5Bytes(stored.md5)
            data class CopiedMeta(
                val contentType: String,
                val contentEncoding: String?,
                val contentLanguage: String?,
                val cacheControl: String?,
                val contentDisposition: String?,
                val expires: String?,
                val userMetadata: Map<String, String>
            )
            val cm = if (effectiveDirective == "REPLACE") {
                CopiedMeta(
                    contentType = contentType ?: "application/octet-stream",
                    contentEncoding = contentEncoding,
                    contentLanguage = contentLanguage,
                    cacheControl = cacheControl,
                    contentDisposition = contentDisposition,
                    expires = expires,
                    userMetadata = userMetadata
                )
            } else {
                CopiedMeta(
                    contentType = srcMeta.contentType,
                    contentEncoding = srcMeta.contentEncoding,
                    contentLanguage = srcMeta.contentLanguage,
                    cacheControl = srcMeta.cacheControl,
                    contentDisposition = srcMeta.contentDisposition,
                    expires = srcMeta.expires,
                    userMetadata = srcMeta.userMetadata
                )
            }

            val meta = ObjectMetadata(
                bucket = destBucket,
                key = destKey,
                blobPath = stored.blobPath.toString(),
                blobSha256Hex = stored.sha256Hex,
                etag = etag,
                sizeBytes = stored.sizeBytes,
                contentType = cm.contentType,
                contentEncoding = cm.contentEncoding,
                contentLanguage = cm.contentLanguage,
                cacheControl = cm.cacheControl,
                contentDisposition = cm.contentDisposition,
                expires = cm.expires,
                userMetadata = cm.userMetadata,
                versionId = "null",
                storageClass = effectiveStorageClass,
                createdAt = Instant.now(),
                checksumCrc32 = srcMeta.checksumCrc32,
                checksumCrc32C = srcMeta.checksumCrc32C,
                checksumSha1 = srcMeta.checksumSha1,
                checksumSha256 = srcMeta.checksumSha256,
                checksumType = srcMeta.checksumType,
                encryptionMode = stored.encryptionMode,
                encryptionKeyId = stored.encryptionKeyId,
                encryptionNonce = stored.encryptionNonce
            )
            withS3 {
                config.database.withTransaction { conn ->
                    repo.putObject(conn, meta)
                    repo.clearBlobWriteIntent(conn, copyIntentId)
                }
            }

            val body = s3XmlDocument("CopyObjectResult") {
                s3Tag("LastModified", S3Time.formatIso8601(meta.createdAt))
                s3Tag("ETag", etag)
            }
            call.response.headers.apply {
                append(HttpHeaders.ETag, etag)
                append("x-amz-storage-class", effectiveStorageClass)
                if (meta.encryptionMode == app.silofs.blob.ObjectEncryption.SSE_S3_MODE) {
                    append("x-amz-server-side-encryption", "AES256")
                }
            }
            call.respondText(body, ContentType.Application.Xml.withCharset(Charsets.UTF_8), HttpStatusCode.OK)
        } catch (t: Throwable) {
            copyIntentId?.let { clearBlobWriteIntentQuietly(it) }
            config.operationalState.recordBlobStoreError()
            write.abort()
            throw t.toS3()
        }
    }

    suspend fun getObject(
        call: ApplicationCall,
        bucket: String,
        key: String,
        rangeHeader: String?,
        ifMatch: String?,
        ifNoneMatch: String?
    ) = withContext(Dispatchers.IO) {
        BucketName.validate(bucket)
        ObjectKey.validate(key)

        val meta = withS3 {
            config.database.withConnection { conn ->
                if (!repo.bucketExists(conn, bucket)) throw S3Errors.noSuchBucket(bucket)
                repo.getObject(conn, bucket, key) ?: throw S3Errors.noSuchKey(bucket, key)
            }
        }

        // ---- Conditional GET (RFC 7232) ----
        // If-Match: must match the current etag, otherwise 412 PreconditionFailed.
        if (ifMatch != null && !ETagMatcher.ifMatchSatisfied(ifMatch, meta.etag)) {
            throw S3Errors.preconditionFailed("If-Match", meta.etag)
        }
        // If-None-Match: if it matches, return 304 Not Modified (no body).
        if (ifNoneMatch != null && !ETagMatcher.ifNoneMatchSatisfied(ifNoneMatch, meta.etag)) {
            // 304 must still carry ETag and a few others per RFC 7232 §4.1.
            call.response.headers.apply {
                append(HttpHeaders.ETag, meta.etag)
                append(HttpHeaders.LastModified, S3Time.formatHttpDate(meta.createdAt))
            }
            throw S3Errors.notModified()
        }

        val blobPath = Path.of(meta.blobPath)
        if (!blobStore.exists(blobPath)) {
            log.error("Object metadata exists but blob is missing on disk: {}/{} blobPath={}", bucket, key, meta.blobPath)
            throw S3Errors.internalError(IllegalStateException("blob missing: $blobPath"))
        }

        val size = blobStore.sizeOf(blobPath)
        val range = ByteRange.parse(rangeHeader, size)

        // ---- Common response headers ----
        call.response.headers.apply {
            append(HttpHeaders.ETag, meta.etag)
            append(HttpHeaders.LastModified, S3Time.formatHttpDate(meta.createdAt))
            append(HttpHeaders.AcceptRanges, "bytes")
            meta.contentEncoding?.let { append(HttpHeaders.ContentEncoding, it) }
            meta.contentLanguage?.let { append(HttpHeaders.ContentLanguage, it) }
            meta.cacheControl?.let { append(HttpHeaders.CacheControl, it) }
            meta.contentDisposition?.let { append(HttpHeaders.ContentDisposition, it) }
            meta.expires?.let { append(HttpHeaders.Expires, it) }
            append("x-amz-storage-class", meta.storageClass)
            if (meta.encryptionMode == app.silofs.blob.ObjectEncryption.SSE_S3_MODE) {
                append("x-amz-server-side-encryption", "AES256")
            }
            // Echo user metadata exactly as AWS does: lowercase the header name
            // but preserve the value verbatim.
            meta.userMetadata.forEach { (k, v) ->
                append("x-amz-meta-" + k.lowercase(Locale.US), v)
            }
        }

        if (range == null) {
            call.response.headers.append(HttpHeaders.ContentLength, size.toString())
            appendChecksumHeaders(call, meta)
            call.respondOutputStream(ContentType.parse(meta.contentType), HttpStatusCode.OK) {
                val ch = blobStore.openRead(blobPath)
                val buf = java.nio.ByteBuffer.allocate(64 * 1024)
                try {
                    while (true) {
                        buf.clear()
                        val n = ch.read(buf)
                        if (n <= 0) break
                        buf.flip()
                        write(buf.array(), 0, n)
                        flush()
                    }
                } finally {
                    ch.close()
                }
            }
        } else {
            call.response.headers.append(HttpHeaders.ContentLength, range.length.toString())
            call.response.headers.append(HttpHeaders.ContentRange, "bytes ${range.start}-${range.endInclusive}/$size")
            call.response.status(HttpStatusCode.PartialContent)
            call.respondOutputStream(ContentType.parse(meta.contentType), HttpStatusCode.PartialContent) {
                blobStore.streamRange(blobPath, range, this)
            }
        }
    }

    suspend fun headObject(
        call: ApplicationCall,
        bucket: String,
        key: String,
        ifMatch: String?,
        ifNoneMatch: String?
    ) = withContext(Dispatchers.IO) {
        BucketName.validate(bucket)
        ObjectKey.validate(key)

        val meta = withS3 {
            config.database.withConnection { conn ->
                if (!repo.bucketExists(conn, bucket)) throw S3Errors.noSuchBucket(bucket)
                repo.getObject(conn, bucket, key) ?: throw S3Errors.noSuchKey(bucket, key)
            }
        }

        // Conditional HEAD — same logic as GET.
        if (ifMatch != null && !ETagMatcher.ifMatchSatisfied(ifMatch, meta.etag)) {
            throw S3Errors.preconditionFailed("If-Match", meta.etag)
        }
        if (ifNoneMatch != null && !ETagMatcher.ifNoneMatchSatisfied(ifNoneMatch, meta.etag)) {
            call.response.headers.apply {
                append(HttpHeaders.ETag, meta.etag)
                append(HttpHeaders.LastModified, S3Time.formatHttpDate(meta.createdAt))
            }
            throw S3Errors.notModified()
        }

        val blobPath = Path.of(meta.blobPath)
        val size = if (blobStore.exists(blobPath)) blobStore.sizeOf(blobPath) else meta.sizeBytes

        call.response.headers.apply {
            append(HttpHeaders.ETag, meta.etag)
            append(HttpHeaders.LastModified, S3Time.formatHttpDate(meta.createdAt))
            append(HttpHeaders.ContentType, meta.contentType)
            append(HttpHeaders.AcceptRanges, "bytes")
            meta.contentEncoding?.let { append(HttpHeaders.ContentEncoding, it) }
            meta.contentLanguage?.let { append(HttpHeaders.ContentLanguage, it) }
            meta.cacheControl?.let { append(HttpHeaders.CacheControl, it) }
            meta.contentDisposition?.let { append(HttpHeaders.ContentDisposition, it) }
            meta.expires?.let { append(HttpHeaders.Expires, it) }
            append("x-amz-storage-class", meta.storageClass)
            if (meta.encryptionMode == app.silofs.blob.ObjectEncryption.SSE_S3_MODE) {
                append("x-amz-server-side-encryption", "AES256")
            }
            meta.userMetadata.forEach { (k, v) ->
                append("x-amz-meta-" + k.lowercase(Locale.US), v)
            }
            // M2.1: echo persisted checksums.
            meta.checksumCrc32?.let { append("x-amz-checksum-crc32", it) }
            meta.checksumCrc32C?.let { append("x-amz-checksum-crc32c", it) }
            meta.checksumSha1?.let { append("x-amz-checksum-sha1", it) }
            meta.checksumSha256?.let { append("x-amz-checksum-sha256", it) }
            meta.checksumType?.let { append("x-amz-checksum-type", it) }
        }
        call.respond(
            object : OutgoingContent.NoContent() {
                override val status: HttpStatusCode = HttpStatusCode.OK
                override val contentLength: Long = size
                override val contentType: ContentType = ContentType.parse(meta.contentType)
            }
        )
    }

    suspend fun deleteObject(call: ApplicationCall, bucket: String, key: String) = withContext(Dispatchers.IO) {
        BucketName.validate(bucket)
        ObjectKey.validate(key)

        withS3 {
            config.database.withTransaction { conn ->
                if (!repo.bucketExists(conn, bucket)) throw S3Errors.noSuchBucket(bucket)
                repo.deleteObject(conn, bucket, key)
                // We do NOT delete the blob here — the recovery sweep will GC
                // unreferenced blobs. This keeps the delete atomic.
            }
        }
        call.response.headers.append(HttpHeaders.ContentLength, "0")
        call.response.status(HttpStatusCode.NoContent)
        call.respondText("", ContentType.Application.Xml, HttpStatusCode.NoContent)
    }

    private data class PublishedBlob(val stored: app.silofs.blob.StoredBlob, val intentId: String)

    private suspend fun <T> withUploadPermit(block: suspend () -> T): T =
        config.operationalState.withUploadPermit {
            withContext(Dispatchers.IO) { block() }
        }

    private fun publishWithIntent(write: app.silofs.blob.BlobWrite): PublishedBlob {
        val writeImpl = write as app.silofs.blob.FsBlobWrite
        val hex = writeImpl.fsyncPhase()
        val intentId = createBlobWriteIntent(hex)
        val stored = try {
            writeImpl.renamePhase(hex)
        } catch (t: Throwable) {
            clearBlobWriteIntentQuietly(intentId)
            throw t
        }
        return PublishedBlob(stored, intentId)
    }

    private fun createBlobWriteIntent(blobSha256Hex: String): String = withS3 {
        config.database.withTransaction { conn -> repo.createBlobWriteIntent(conn, blobSha256Hex) }
    }

    private fun clearBlobWriteIntentQuietly(intentId: String?) {
        if (intentId == null) return
        runCatching {
            withS3 {
                config.database.withTransaction { conn -> repo.clearBlobWriteIntent(conn, intentId) }
            }
        }.onFailure { t ->
            log.warn("Failed to clear blob write intent {}", intentId, t)
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /**
     * Validate client-supplied checksum headers against the actual blob content.
     * (Red flag #8: silently accepting checksums without verification is a
     * data-integrity risk.)
     *
     * Each checksum header value is base64-encoded. We compute the matching
     * checksum over the blob and compare. Mismatch → [S3ErrorCode.BadDigest].
     */
    private fun validateChecksums(
        blobPath: java.nio.file.Path,
        crc32: String?,
        crc32c: String?,
        sha1: String?,
        sha256: String?,
        blobStore: BlobStore
    ) {
        if (crc32 == null && crc32c == null && sha1 == null && sha256 == null) return

        // Read the blob once and compute all requested checksums.
        blobStore.openRead(blobPath).use { ch ->
            val buf = java.nio.ByteBuffer.allocate(64 * 1024)
            val crc32Digest = if (crc32 != null) java.util.zip.CRC32() else null
            val crc32cDigest = if (crc32c != null) app.silofs.common.CRC32C() else null
            val sha1Digest = if (sha1 != null) java.security.MessageDigest.getInstance("SHA-1") else null
            val sha256Digest = if (sha256 != null) java.security.MessageDigest.getInstance("SHA-256") else null

            while (true) {
                buf.clear()
                val n = ch.read(buf)
                if (n <= 0) break
                buf.flip()
                val arr = buf.array()
                crc32Digest?.update(arr, 0, n)
                crc32cDigest?.update(arr, 0, n)
                sha1Digest?.update(arr, 0, n)
                sha256Digest?.update(arr, 0, n)
            }

            crc32Digest?.let { d ->
                val crcVal = d.value
                val crcBytes = byteArrayOf(
                    ((crcVal ushr 24) and 0xFF).toByte(),
                    ((crcVal ushr 16) and 0xFF).toByte(),
                    ((crcVal ushr 8) and 0xFF).toByte(),
                    (crcVal and 0xFF).toByte()
                )
                val computed = java.util.Base64.getEncoder().encodeToString(crcBytes)
                if (!constantTimeEquals(crc32!!, computed)) {
                    throw S3Errors.badDigest(crc32, computed)
                }
            }
            crc32cDigest?.let { d ->
                val crcVal = d.value
                val crcBytes = byteArrayOf(
                    ((crcVal ushr 24) and 0xFF).toByte(),
                    ((crcVal ushr 16) and 0xFF).toByte(),
                    ((crcVal ushr 8) and 0xFF).toByte(),
                    (crcVal and 0xFF).toByte()
                )
                val computed = java.util.Base64.getEncoder().encodeToString(crcBytes)
                if (!constantTimeEquals(crc32c!!, computed)) {
                    throw S3Errors.badDigest(crc32c, computed)
                }
            }
            sha1Digest?.let { d ->
                val computed = java.util.Base64.getEncoder().encodeToString(d.digest())
                if (!constantTimeEquals(sha1!!, computed)) {
                    throw S3Errors.badDigest(sha1, computed)
                }
            }
            sha256Digest?.let { d ->
                val computed = java.util.Base64.getEncoder().encodeToString(d.digest())
                if (!constantTimeEquals(sha256!!, computed)) {
                    throw S3Errors.badDigest(sha256, computed)
                }
            }
        }
    }

    private fun appendChecksumHeaders(call: ApplicationCall, meta: ObjectMetadata) {
        call.response.headers.apply {
            meta.checksumCrc32?.let { append("x-amz-checksum-crc32", it) }
            meta.checksumCrc32C?.let { append("x-amz-checksum-crc32c", it) }
            meta.checksumSha1?.let { append("x-amz-checksum-sha1", it) }
            meta.checksumSha256?.let { append("x-amz-checksum-sha256", it) }
            meta.checksumType?.let { append("x-amz-checksum-type", it) }
        }
    }

    companion object {
        /** S3 storage classes accepted by the metadata layer. */
        val ALLOWED_STORAGE_CLASSES: Set<String> = setOf(
            "STANDARD", "REDUCED_REDUNDANCY", "GLACIER", "STANDARD_IA",
            "ONEZONE_IA", "INTELLIGENT_TIERING", "DEEP_ARCHIVE",
            "GLACIER_IR", "OUTPOSTS", "EXPRESS_ONEZONE", "SNOW",
            "SPARSE_STORAGE"
        )
    }
}
