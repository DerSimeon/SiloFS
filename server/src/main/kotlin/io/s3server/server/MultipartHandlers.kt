package app.silofs.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import app.silofs.blob.BlobStore
import app.silofs.blob.streamRange
import app.silofs.common.BucketName
import app.silofs.common.ByteRange
import app.silofs.common.ETag
import app.silofs.common.ObjectKey
import app.silofs.common.ObjectMetadata
import app.silofs.common.S3Errors
import app.silofs.common.S3Time
import app.silofs.common.s3Close
import app.silofs.common.s3Open
import app.silofs.common.s3Tag
import app.silofs.common.s3TagBool
import app.silofs.common.s3TagLong
import app.silofs.common.s3XmlDocument
import app.silofs.metadata.JdbcMetadataRepository
import app.silofs.metadata.MultipartUploadInfo
import app.silofs.metadata.PartInfo
import app.silofs.metadata.newUploadId
import app.silofs.metadata.toS3
import app.silofs.metadata.withS3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.time.Instant

/**
 * Multipart upload handlers (M3).
 *
 * Operations:
 *   - [createMultipartUpload] — POST /{bucket}/{key}?uploads
 *   - [uploadPart]            — PUT  /{bucket}/{key}?partNumber=N&uploadId=X
 *   - [completeMultipartUpload] — POST /{bucket}/{key}?uploadId=X
 *   - [abortMultipartUpload]  — DELETE /{bucket}/{key}?uploadId=X
 *   - [listParts]             — GET   /{bucket}/{key}?uploadId=X
 *
 * Durability:
 *   - Each part is streamed to a temp file, fsync'd, and atomically renamed
 *     to a content-addressed blob before the part row is committed.
 *   - CompleteMultipartUpload concatenates parts into a new temp file, hashes
 *     while copying, fsyncs, renames, and then writes the object metadata +
 *     deletes part rows + marks the upload COMPLETED — all in a single
 *     Postgres transaction. A crash before commit leaves the upload
 *     COMPLETING or FAILED_COMPLETION; parts remain intact unless the final
 *     completion transaction commits.
 *   - AbortMultipartUpload marks the upload ABORTED and deletes part rows in
 *     one transaction. The blobs are left on disk; the recovery sweep GCs
 *     them via the existing unreferenced-blob sweep.
 *
 * Validation:
 *   - Part numbers must be in 1..10000.
 *   - Part size must be >= 5 MiB except for the last part (validated at
 *     CompleteMultipartUpload time, since we don't know which part is last
 *     until the client says so).
 *   - Upload IDs must reference an INITIATED upload.
 *   - The CompleteMultipartUpload XML part list must be in ascending order
 *     with no gaps (we permit gaps by treating missing parts as NoSuchUpload
 *     per AWS behaviour — the client is required to list every part).
 *   - Re-uploading the same part number overwrites the previous part (UPSERT).
 */
class MultipartHandlers(
    private val config: ServerConfig,
    private val repo: JdbcMetadataRepository = config.repository,
    private val blobStore: BlobStore = config.blobStore
) {

    private val log = LoggerFactory.getLogger(MultipartHandlers::class.java)

    // ---------- CreateMultipartUpload ----------

    suspend fun createMultipartUpload(
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
        storageClass: String?,
        checksumAlgorithm: String?
    ) = withContext(Dispatchers.IO) {
        BucketName.validate(bucket)
        ObjectKey.validate(key)

        val effectiveStorageClass = storageClass ?: "STANDARD"
        if (effectiveStorageClass !in S3Handlers.ALLOWED_STORAGE_CLASSES) {
            throw S3Errors.invalidArgument("x-amz-storage-class", effectiveStorageClass, "must be one of ${S3Handlers.ALLOWED_STORAGE_CLASSES}")
        }

        val uploadId = newUploadId()
        withS3 {
            config.database.withTransaction { conn ->
                if (!repo.bucketExists(conn, bucket)) throw S3Errors.noSuchBucket(bucket)
                repo.createMultipartUpload(
                    conn, uploadId, bucket, key, contentType, userMetadata, effectiveStorageClass,
                    contentEncoding, contentLanguage, cacheControl, contentDisposition, expires,
                    checksumAlgorithm
                )
            }
        }
        log.info("CreateMultipartUpload bucket={} key={} uploadId={}", bucket, key, uploadId)
        val body = s3XmlDocument("InitiateMultipartUploadResult") {
            s3Tag("Bucket", bucket)
            s3Tag("Key", key)
            s3Tag("UploadId", uploadId)
        }
        call.respondText(body, ContentType.Application.Xml.withCharset(Charsets.UTF_8), HttpStatusCode.OK)
    }

    // ---------- UploadPart ----------

    suspend fun uploadPart(
        call: ApplicationCall,
        bucket: String,
        key: String,
        uploadId: String,
        partNumber: Int,
        contentLength: Long?,
        expectedSha256: String?,
        expectedMd5Base64: String?,
        checksumCrc32: String? = null,
        checksumCrc32C: String? = null,
        checksumSha1: String? = null,
        checksumSha256: String? = null,
        checksumAlgorithm: String? = null
    ) = withUploadPermit {
        BucketName.validate(bucket)
        ObjectKey.validate(key)

        // ---- Part number validation ----
        if (partNumber < 1 || partNumber > 10_000) {
            throw S3Errors.invalidArgument("partNumber", partNumber.toString(), "must be between 1 and 10000")
        }
        // ---- Content-Length validation ----
        if (contentLength == null) {
            throw S3Errors.missingContentLength()
        }
        if (contentLength < 0) {
            throw S3Errors.invalidArgument("Content-Length", contentLength.toString(), "must be >= 0")
        }
        // Per-part size limit (5 GiB) — same as single-PUT.
        val maxPartSize = 5L * 1024 * 1024 * 1024
        if (contentLength > maxPartSize) {
            throw S3Errors.entityTooLarge(contentLength, maxPartSize)
        }

        // ---- Verify upload exists and is INITIATED (not COMPLETING) ----
        withS3 {
            config.database.withConnection { conn ->
                val mpu = repo.getMultipartUpload(conn, uploadId)
                    ?: throw S3Errors.noSuchUpload(uploadId)
                if (mpu.bucket != bucket || mpu.key != key) {
                    throw S3Errors.noSuchUpload(uploadId)
                }
                // Reject part uploads while the upload is being completed.
                if (mpu.state == "COMPLETING") {
                    throw S3Errors.noSuchUpload(uploadId)
                }
            }
        }

        // ---- Stream part to temp file, fsync, rename ----
        val write = blobStore.beginWrite(expectedSha256Hex = expectedSha256)
        var intentId: String? = null
        try {
            val input: InputStream = call.receiveStream()
            input.copyTo(object : OutputStream() {
                override fun write(b: Int) {
                    write.write(byteArrayOf(b.toByte()), 0, 1)
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    write.write(b, off, len)
                }
            })
            val published = publishWithIntent(write)
            intentId = published.intentId
            val stored = published.stored

            // ---- Content-Length verification (red flag #3) ----
            if (stored.sizeBytes != contentLength) {
                throw S3Errors.incompleteBody(contentLength, stored.sizeBytes)
            }

            // ---- Content-MD5 verification ----
            if (expectedMd5Base64 != null) {
                val computedB64 = java.util.Base64.getEncoder().encodeToString(stored.md5)
                if (!constantTimeEquals(expectedMd5Base64, computedB64)) {
                    throw S3Errors.badDigest(expectedMd5Base64, computedB64)
                }
            }

            // ---- Checksum header validation (red flag #8) ----
            validatePartChecksums(
                stored.blobPath,
                checksumCrc32, checksumCrc32C, checksumSha1, checksumSha256,
                blobStore
            )
            val effectiveChecksumSha256 = checksumSha256
                ?: if (checksumAlgorithm.equals("SHA256", ignoreCase = true)) {
                    sha256HexToBase64(stored.sha256Hex)
                } else {
                    null
                }

            val etag = ETag.fromMd5Bytes(stored.md5)
            withS3 {
                config.database.withTransaction { conn ->
                    // Gap #2 fix: the final transaction must require state == INITIATED.
                    // getMultipartUpload returns both INITIATED and COMPLETING, so
                    // we must explicitly check the state here. This prevents a
                    // part from being inserted/replaced while a completion is
                    // in progress.
                    val mpu = repo.getMultipartUpload(conn, uploadId)
                        ?: throw S3Errors.noSuchUpload(uploadId)
                    if (mpu.bucket != bucket || mpu.key != key) {
                        throw S3Errors.noSuchUpload(uploadId)
                    }
                    if (mpu.state != "INITIATED") {
                        // Upload is COMPLETING (or COMPLETED/ABORTED if state
                        // changed between the initial check and now). Reject.
                        throw S3Errors.noSuchUpload(uploadId)
                    }
                    repo.uploadPart(
                        conn, uploadId, partNumber,
                        blobPath = stored.blobPath.toString(),
                        blobSha256Hex = stored.sha256Hex,
                        etag = etag,
                        sizeBytes = stored.sizeBytes,
                        checksumCrc32 = checksumCrc32,
                        checksumCrc32C = checksumCrc32C,
                        checksumSha1 = checksumSha1,
                        checksumSha256 = effectiveChecksumSha256,
                        encryptionMode = stored.encryptionMode,
                        encryptionKeyId = stored.encryptionKeyId,
                        encryptionNonce = stored.encryptionNonce
                    )
                    repo.clearBlobWriteIntent(conn, intentId)
                }
            }

            log.debug("UploadPart bucket={} key={} uploadId={} part={} size={}",
                bucket, key, uploadId, partNumber, stored.sizeBytes)
            call.response.headers.apply {
                append(HttpHeaders.ETag, etag)
                checksumCrc32?.let { append("x-amz-checksum-crc32", it) }
                checksumCrc32C?.let { append("x-amz-checksum-crc32c", it) }
                checksumSha1?.let { append("x-amz-checksum-sha1", it) }
                effectiveChecksumSha256?.let { append("x-amz-checksum-sha256", it) }
            }
            call.respondText("", ContentType.Application.Xml.withCharset(Charsets.UTF_8), HttpStatusCode.OK)
        } catch (t: Throwable) {
            intentId?.let { clearBlobWriteIntentQuietly(it) }
            config.operationalState.recordBlobStoreError()
            write.abort()
            throw t.toS3()
        }
    }

    // ---------- CompleteMultipartUpload ----------

    /**
     * Atomically materialise the final object from the uploaded parts.
     *
     * Race-safety (red flag #4): the upload is transitioned to COMPLETING
     * BEFORE parts are read. This prevents concurrent UploadPart calls from
     * adding/replacing parts while completion is in progress. The transition
     * is atomic (conditional UPDATE), so only one completer can win.
     *
     * Crash-safety: the new blob is fsync'd and renamed to its content-addressed
     * path BEFORE the DB transaction opens. If the transaction commits, the
     * object is live. If it rolls back (or the process crashes first), the
     * blob is unreferenced and the recovery sweep will GC it after the write
     * intent is cleared or expires. The upload is not reopened to INITIATED;
     * unsafe failures are marked FAILED_COMPLETION.
     */
    suspend fun completeMultipartUpload(
        call: ApplicationCall,
        bucket: String,
        key: String,
        uploadId: String,
        requestedParts: List<RequestedPart>
    ) = withMultipartCompletionPermit {
        BucketName.validate(bucket)
        ObjectKey.validate(key)

        // ---- Validate the requested part list ----
        if (requestedParts.isEmpty()) {
            throw S3Errors.invalidArgument("parts", null, "CompleteMultipartUpload requires at least one part")
        }
        // Parts must be in ascending order with no duplicates.
        var prev = 0
        for (rp in requestedParts) {
            if (rp.partNumber <= prev) {
                throw S3Errors.invalidArgument("partNumber", rp.partNumber.toString(), "parts must be in ascending order with no duplicates")
            }
            prev = rp.partNumber
        }
        if (requestedParts.size > 10_000) {
            throw S3Errors.invalidArgument("parts", requestedParts.size.toString(), "too many parts (max 10000)")
        }

        // ---- Transition to COMPLETING (closes the race window) ----
        // This atomic UPDATE ensures only one completer can proceed. If the
        // upload is already COMPLETING or COMPLETED, we return NoSuchUpload.
        withS3 {
            config.database.withTransaction { conn ->
                if (!repo.bucketExists(conn, bucket)) throw S3Errors.noSuchBucket(bucket)
                val mpu = repo.getMultipartUpload(conn, uploadId)
                    ?: throw S3Errors.noSuchUpload(uploadId)
                if (mpu.bucket != bucket || mpu.key != key) {
                    throw S3Errors.noSuchUpload(uploadId)
                }
                if (mpu.state == "COMPLETING") {
                    // Another completer is already in progress. Reject so the
                    // client retries.
                    throw S3Errors.noSuchUpload(uploadId)
                }
                // Gap #3 fix: check the return value. If the transition fails
                // (another completer won the race between our read and our
                // UPDATE), fail immediately.
                val transitioned = repo.markMultipartCompleting(conn, uploadId)
                if (!transitioned) {
                    throw S3Errors.noSuchUpload(uploadId)
                }
            }
        }

        // ---- Read parts and validate (now safe from concurrent modification) ----
        var finalIntentId: String? = null
        try {
            val (mpu, parts) = withS3 {
                config.database.withConnection { conn ->
                    val mpu = repo.getMultipartUpload(conn, uploadId)
                        ?: throw S3Errors.noSuchUpload(uploadId)
                    val all = repo.listParts(conn, uploadId)
                    mpu to all.associateBy { it.partNumber }
                }
            }

            // ---- Validate every requested part exists and matches the ETag ----
            val orderedParts = ArrayList<PartInfo>(requestedParts.size)
            for (rp in requestedParts) {
                val p = parts[rp.partNumber] ?: throw S3Errors.noSuchPart(uploadId, rp.partNumber)
                // AWS requires the client-supplied ETag to match the stored one.
                if (rp.etag != null && normalizeEtag(rp.etag) != normalizeEtag(p.etag)) {
                    throw S3Errors.invalidPartETag(rp.partNumber, rp.etag, p.etag)
                }
                orderedParts += p
            }

            // ---- Validate part sizes: every part except the last must be >= 5 MiB ----
            val minPartSize = 5L * 1024 * 1024
            for ((idx, p) in orderedParts.withIndex()) {
                if (idx < orderedParts.lastIndex && p.sizeBytes < minPartSize) {
                    throw S3Errors.entityTooSmall(p.partNumber, p.sizeBytes, minPartSize)
                }
            }

            // ---- Concatenate parts into a new content-addressed blob ----
            val write = blobStore.beginWrite(expectedSha256Hex = null)
            val partMd5s = ArrayList<ByteArray>(orderedParts.size)
            try {
                Failpoint.crashIf("mpu-before-final-publish")
                for (p in orderedParts) {
                    val partPath = Path.of(p.blobPath)
                    if (!blobStore.exists(partPath)) {
                        log.error("Part blob missing on disk: uploadId={} part={}", uploadId, p.partNumber)
                        throw S3Errors.internalError(IllegalStateException("part blob missing: $partPath"))
                    }
                    val md5 = java.security.MessageDigest.getInstance("MD5")
                    blobStore.openRead(partPath).use { ch ->
                        val buf = java.nio.ByteBuffer.allocate(64 * 1024)
                        while (true) {
                            buf.clear()
                            val n = ch.read(buf)
                            if (n <= 0) break
                            buf.flip()
                            val arr = buf.array()
                            write.write(arr, 0, n)
                            md5.update(arr, 0, n)
                        }
                    }
                    partMd5s += md5.digest()
                }
                val published = publishWithIntent(write)
                finalIntentId = published.intentId
                val stored = published.stored
                Failpoint.crashIf("mpu-after-final-publish")

                val etag = ETag.fromMultipart(partMd5s)

                val meta = ObjectMetadata(
                    bucket = bucket,
                    key = key,
                    blobPath = stored.blobPath.toString(),
                    blobSha256Hex = stored.sha256Hex,
                    etag = etag,
                    sizeBytes = stored.sizeBytes,
                    contentType = mpu.contentType,
                    contentEncoding = mpu.contentEncoding,
                    contentLanguage = mpu.contentLanguage,
                    cacheControl = mpu.cacheControl,
                    contentDisposition = mpu.contentDisposition,
                    expires = mpu.expires,
                    userMetadata = mpu.userMetadata,
                    versionId = "null",
                    storageClass = mpu.storageClass,
                    createdAt = Instant.now(),
                    encryptionMode = stored.encryptionMode,
                    encryptionKeyId = stored.encryptionKeyId,
                    encryptionNonce = stored.encryptionNonce
                )

                // ---- Atomic commit: object row + mark COMPLETED + delete parts ----
                withS3 {
                    config.database.withTransaction { conn ->
                        repo.putObject(conn, meta)
                        Failpoint.crashIf("mpu-after-object-put")
                        // Check the return value. If the upload is no longer
                        // COMPLETING, the transaction rolls back and no object
                        // row or part deletion is committed.
                        val transitioned = repo.completeMultipartUpload(conn, uploadId)
                        if (!transitioned) {
                            throw S3Errors.internalError(
                                IllegalStateException("completeMultipartUpload state transition failed for $uploadId")
                            )
                        }
                        Failpoint.crashIf("mpu-after-completed-state")
                        repo.deleteParts(conn, uploadId)
                        repo.clearBlobWriteIntent(conn, finalIntentId)
                    }
                }

                log.info("CompleteMultipartUpload bucket={} key={} uploadId={} parts={} size={}",
                    bucket, key, uploadId, orderedParts.size, stored.sizeBytes)

                val location = "/$bucket/$key"
                val body = s3XmlDocument("CompleteMultipartUploadResult") {
                    s3Tag("Location", location)
                    s3Tag("Bucket", bucket)
                    s3Tag("Key", key)
                    s3Tag("ETag", etag)
                }
                call.response.headers.append(HttpHeaders.ETag, etag)
                call.respondText(body, ContentType.Application.Xml.withCharset(Charsets.UTF_8), HttpStatusCode.OK)
            } catch (t: Throwable) {
                write.abort()
                throw t
            }
        } catch (t: Throwable) {
            finalIntentId?.let { clearBlobWriteIntentQuietly(it) }
            // Do not reopen a COMPLETING upload to INITIATED. Once completion
            // starts, part mutation must remain closed; unsafe failures become
            // an explicit terminal state for operator inspection.
            runCatching {
                withS3 {
                    config.database.withTransaction { conn ->
                        repo.failMultipartCompletion(conn, uploadId)
                    }
                }
            }
            throw t.toS3()
        }
    }

    // ---------- AbortMultipartUpload ----------

    suspend fun abortMultipartUpload(
        call: ApplicationCall,
        bucket: String,
        key: String,
        uploadId: String
    ) = withContext(Dispatchers.IO) {
        BucketName.validate(bucket)
        ObjectKey.validate(key)
        withS3 {
            config.database.withTransaction { conn ->
                if (!repo.bucketExists(conn, bucket)) throw S3Errors.noSuchBucket(bucket)
                val mpu = repo.getMultipartUpload(conn, uploadId)
                    ?: throw S3Errors.noSuchUpload(uploadId)
                if (mpu.bucket != bucket || mpu.key != key || mpu.state != "INITIATED") {
                    throw S3Errors.noSuchUpload(uploadId)
                }
                val transitioned = repo.abortMultipartUpload(conn, uploadId)
                if (!transitioned) {
                    throw S3Errors.noSuchUpload(uploadId)
                }
                // Only the transaction that successfully moves INITIATED ->
                // ABORTED may delete part rows. If completion won the race,
                // the conditional update above returns false and parts remain
                // available to the completer.
                repo.deleteParts(conn, uploadId)
            }
        }
        log.info("AbortMultipartUpload bucket={} key={} uploadId={}", bucket, key, uploadId)
        call.respond(
            object : OutgoingContent.NoContent() {
                override val status: HttpStatusCode = HttpStatusCode.NoContent
                override val contentLength: Long = 0
            }
        )
    }

    // ---------- ListParts ----------

    suspend fun listParts(
        call: ApplicationCall,
        bucket: String,
        key: String,
        uploadId: String
    ) = withContext(Dispatchers.IO) {
        BucketName.validate(bucket)
        ObjectKey.validate(key)
        val (mpu, parts) = withS3 {
            config.database.withConnection { conn ->
                if (!repo.bucketExists(conn, bucket)) throw S3Errors.noSuchBucket(bucket)
                val mpu = repo.getMultipartUpload(conn, uploadId)
                    ?: throw S3Errors.noSuchUpload(uploadId)
                if (mpu.bucket != bucket || mpu.key != key) {
                    throw S3Errors.noSuchUpload(uploadId)
                }
                mpu to repo.listParts(conn, uploadId)
            }
        }
        val body = s3XmlDocument("ListPartsResult") {
            s3Tag("Bucket", bucket)
            s3Tag("Key", key)
            s3Tag("UploadId", uploadId)
            s3Tag("StorageClass", mpu.storageClass)
            s3Tag("PartNumberMarker", "0")
            s3Tag("NextPartNumberMarker", parts.lastOrNull()?.partNumber?.toString() ?: "0")
            s3TagLong("MaxParts", parts.size.toLong())
            s3TagBool("IsTruncated", false)
            for (p in parts) {
                s3Open("Part")
                s3TagLong("PartNumber", p.partNumber.toLong())
                s3Tag("LastModified", S3Time.formatIso8601(p.uploadedAt))
                s3Tag("ETag", p.etag)
                s3TagLong("Size", p.sizeBytes)
                p.checksumCrc32?.let { s3Tag("ChecksumCRC32", it) }
                p.checksumCrc32C?.let { s3Tag("ChecksumCRC32C", it) }
                p.checksumSha1?.let { s3Tag("ChecksumSHA1", it) }
                p.checksumSha256?.let { s3Tag("ChecksumSHA256", it) }
                s3Close("Part")
            }
        }
        call.respondText(body, ContentType.Application.Xml.withCharset(Charsets.UTF_8), HttpStatusCode.OK)
    }

    // ---------- ListMultipartUploads ----------

    /**
     * `GET /{bucket}?uploads` — list all in-progress multipart uploads for a bucket.
     *
     * Returns `ListMultipartUploadsResult` XML with `<Upload>` entries, each
     * containing `Key`, `UploadId`, `Initiated`, and `StorageClass`. Supports
     * optional `prefix` and `delimiter` query parameters (same semantics as
     * ListObjectsV2).
     */
    suspend fun listMultipartUploads(
        call: ApplicationCall,
        bucket: String,
        prefix: String?,
        delimiter: String?
    ) = withContext(Dispatchers.IO) {
        BucketName.validate(bucket)
        val uploads = withS3 {
            config.database.withConnection { conn ->
                if (!repo.bucketExists(conn, bucket)) throw S3Errors.noSuchBucket(bucket)
                repo.listMultipartUploads(conn, bucket)
            }
        }
        // Apply prefix + delimiter in-memory (same approach as ListObjectsV2).
        val filtered = uploads.filter { u ->
            prefix == null || u.key.startsWith(prefix)
        }
        val (contents, commonPrefixes) = if (delimiter != null && delimiter.isNotEmpty()) {
            val cps = mutableSetOf<String>()
            val direct = mutableListOf<MultipartUploadInfo>()
            for (u in filtered) {
                val afterPrefix = if (prefix != null && u.key.startsWith(prefix)) {
                    u.key.substring(prefix.length)
                } else {
                    u.key
                }
                val delimIdx = afterPrefix.indexOf(delimiter)
                if (delimIdx >= 0) {
                    cps += (prefix ?: "") + afterPrefix.substring(0, delimIdx + delimiter.length)
                } else {
                    direct += u
                }
            }
            direct to cps.toList()
        } else {
            filtered to emptyList()
        }
        val body = s3XmlDocument("ListMultipartUploadsResult") {
            s3Tag("Bucket", bucket)
            if (prefix != null) s3Tag("Prefix", prefix) else s3Tag("Prefix", "")
            if (delimiter != null) s3Tag("Delimiter", delimiter)
            s3Tag("KeyMarker", "")
            s3Tag("UploadIdMarker", "")
            s3TagLong("MaxUploads", 1000L)
            s3TagBool("IsTruncated", false)
            for (u in contents) {
                s3Open("Upload")
                s3Tag("Key", u.key)
                s3Tag("UploadId", u.uploadId)
                s3Tag("Initiated", S3Time.formatIso8601(u.initiatedAt))
                s3Tag("StorageClass", u.storageClass)
                s3Close("Upload")
            }
            for (cp in commonPrefixes) {
                s3Open("CommonPrefixes")
                s3Tag("Prefix", cp)
                s3Close("CommonPrefixes")
            }
        }
        call.respondText(body, ContentType.Application.Xml.withCharset(Charsets.UTF_8), HttpStatusCode.OK)
    }

    // ---------- UploadPartCopy ----------

    /**
     * `PUT /{bucket}/{key}?partNumber=N&uploadId=X` with `x-amz-copy-source` header.
     *
     * Copies a byte range from an existing object into a part of a multipart
     * upload. Supports optional `x-amz-copy-source-range` for partial copies.
     * Returns `CopyPartResult` XML with `ETag` and `LastModified`.
     */
    suspend fun uploadPartCopy(
        call: ApplicationCall,
        destBucket: String,
        destKey: String,
        uploadId: String,
        partNumber: Int,
        copySource: String,
        copySourceRange: String?
    ) = withUploadPermit {
        BucketName.validate(destBucket)
        ObjectKey.validate(destKey)

        // ---- Part number validation ----
        if (partNumber < 1 || partNumber > 10_000) {
            throw S3Errors.invalidArgument("partNumber", partNumber.toString(), "must be between 1 and 10000")
        }

        // ---- Parse copy-source ----
        val raw = copySource.removePrefix("/")
        val slashIdx = raw.indexOf('/')
        if (slashIdx <= 0) {
            throw S3Errors.invalidArgument("x-amz-copy-source", copySource, "must be '/bucket/key' or 'bucket/key'")
        }
        val srcBucket = raw.substring(0, slashIdx)
        val srcKey = raw.substring(slashIdx + 1)
        BucketName.validate(srcBucket)
        ObjectKey.validate(srcKey)

        // ---- Verify upload exists ----
        val mpu = withS3 {
            config.database.withConnection { conn ->
                if (!repo.bucketExists(conn, destBucket)) throw S3Errors.noSuchBucket(destBucket)
                if (!repo.bucketExists(conn, srcBucket)) throw S3Errors.noSuchBucket(srcBucket)
                val m = repo.getMultipartUpload(conn, uploadId)
                    ?: throw S3Errors.noSuchUpload(uploadId)
                if (m.bucket != destBucket || m.key != destKey) {
                    throw S3Errors.noSuchUpload(uploadId)
                }
                m
            }
        }

        // ---- Fetch source object ----
        val srcMeta = withS3 {
            config.database.withConnection { conn ->
                repo.getObject(conn, srcBucket, srcKey) ?: throw S3Errors.noSuchKey(srcBucket, srcKey)
            }
        }
        val srcBlobPath = Path.of(srcMeta.blobPath)
        if (!blobStore.exists(srcBlobPath)) {
            throw S3Errors.internalError(IllegalStateException("source blob missing: $srcBlobPath"))
        }
        val srcSize = blobStore.sizeOf(srcBlobPath)

        // ---- Parse optional copy-source-range ----
        val range = if (copySourceRange != null) {
            ByteRange.parse(copySourceRange, srcSize)
                ?: throw S3Errors.invalidArgument("x-amz-copy-source-range", copySourceRange, "invalid range")
        } else {
            ByteRange(0, srcSize - 1)
        }

        // ---- Stream the (range of) source blob into a new part write ----
        val write = blobStore.beginWrite(expectedSha256Hex = null)
        var copyIntentId: String? = null
        try {
            blobStore.streamRange(srcBlobPath, range, object : OutputStream() {
                override fun write(b: Int) {
                    write.write(byteArrayOf(b.toByte()), 0, 1)
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    write.write(b, off, len)
                }
            })
            val published = publishWithIntent(write)
            copyIntentId = published.intentId
            val stored = published.stored
            val etag = ETag.fromMd5Bytes(stored.md5)

            withS3 {
                config.database.withTransaction { conn ->
                    // Re-check upload is still INITIATED.
                    val current = repo.getMultipartUpload(conn, uploadId)
                        ?: throw S3Errors.noSuchUpload(uploadId)
                    if (current.state != "INITIATED") {
                        throw S3Errors.noSuchUpload(uploadId)
                    }
                    repo.uploadPart(
                        conn, uploadId, partNumber,
                        blobPath = stored.blobPath.toString(),
                        blobSha256Hex = stored.sha256Hex,
                        etag = etag,
                        sizeBytes = stored.sizeBytes,
                        checksumCrc32 = null,
                        checksumCrc32C = null,
                        checksumSha1 = null,
                        checksumSha256 = null,
                        encryptionMode = stored.encryptionMode,
                        encryptionKeyId = stored.encryptionKeyId,
                        encryptionNonce = stored.encryptionNonce
                    )
                    repo.clearBlobWriteIntent(conn, copyIntentId)
                }
            }

            log.debug("UploadPartCopy dest={}/{} uploadId={} part={} src={}/{} range={}",
                destBucket, destKey, uploadId, partNumber, srcBucket, srcKey, range)

            val body = s3XmlDocument("CopyPartResult") {
                s3Tag("LastModified", S3Time.formatIso8601(Instant.now()))
                s3Tag("ETag", etag)
            }
            call.response.headers.append(HttpHeaders.ETag, etag)
            call.respondText(body, ContentType.Application.Xml.withCharset(Charsets.UTF_8), HttpStatusCode.OK)
        } catch (t: Throwable) {
            copyIntentId?.let { clearBlobWriteIntentQuietly(it) }
            config.operationalState.recordBlobStoreError()
            write.abort()
            throw t.toS3()
        }
    }

    // ---------- Helpers ----------

    private data class PublishedBlob(val stored: app.silofs.blob.StoredBlob, val intentId: String)

    private suspend fun <T> withUploadPermit(block: suspend () -> T): T =
        config.operationalState.withUploadPermit {
            withContext(Dispatchers.IO) { block() }
        }

    private suspend fun <T> withMultipartCompletionPermit(block: suspend () -> T): T =
        config.operationalState.withMultipartCompletionPermit {
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

    private fun normalizeEtag(etag: String): String =
        etag.trim().removeSurrounding("\"")

    /**
     * Validate client-supplied part checksum headers against the actual part
     * blob content. (Red flag #8.)
     */
    private fun validatePartChecksums(
        blobPath: java.nio.file.Path,
        crc32: String?,
        crc32c: String?,
        sha1: String?,
        sha256: String?,
        blobStore: BlobStore
    ) {
        if (crc32 == null && crc32c == null && sha1 == null && sha256 == null) return

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

    private fun sha256HexToBase64(hex: String): String {
        require(hex.length % 2 == 0) { "SHA-256 hex must have even length" }
        val bytes = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            val hi = Character.digit(hex[i], 16)
            val lo = Character.digit(hex[i + 1], 16)
            require(hi >= 0 && lo >= 0) { "SHA-256 hex contains non-hex characters" }
            bytes[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }
}

/** A part entry from the CompleteMultipartUpload request XML. */
data class RequestedPart(val partNumber: Int, val etag: String?)
