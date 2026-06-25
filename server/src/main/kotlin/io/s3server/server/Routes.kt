package app.silofs.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import app.silofs.common.ObjectKey
import app.silofs.common.S3Errors
import app.silofs.common.S3Exception
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Path-style routing. The first path segment is the bucket; the rest (joined
 * with `/`) is the object key. Special endpoints (`/healthz`, `/readyz`, `/metricsz`,
 * `/`) are mounted directly at the root.
 *
 * M3 additions:
 *   - `POST /{bucket}/{key}?uploads` → CreateMultipartUpload
 *   - `PUT  /{bucket}/{key}?partNumber=N&uploadId=X` → UploadPart
 *   - `POST /{bucket}/{key}?uploadId=X` → CompleteMultipartUpload
 *   - `DELETE /{bucket}/{key}?uploadId=X` → AbortMultipartUpload
 *   - `GET   /{bucket}/{key}?uploadId=X` → ListParts
 */
fun Application.s3Routes(config: ServerConfig, handlers: S3Handlers, multipart: MultipartHandlers) {
    routing {
        get("/healthz") {
            call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.OK)
        }
        get("/readyz") {
            val checks = collectReadiness(config)
            val status = if (checks.all { it.ok }) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respondText(renderReadiness(checks), ContentType.Text.Plain, status)
        }
        get("/metricsz") {
            call.respondText(renderMetrics(collectMetrics(config)), ContentType.Text.Plain, HttpStatusCode.OK)
        }
        // GET / → ListBuckets
        get("/") {
            handlers.listBuckets(call)
        }

        // Bucket-level operations
        route("/{bucket}") {
            put {
                val bucket = call.parameters["bucket"]!!
                handlers.createBucket(call, bucket)
            }
            head {
                val bucket = call.parameters["bucket"]!!
                handlers.headBucket(call, bucket)
            }
            delete {
                val bucket = call.parameters["bucket"]!!
                handlers.deleteBucket(call, bucket)
            }
            get {
                val bucket = call.parameters["bucket"]!!
                when {
                    call.request.queryParameters["location"] != null -> {
                        handlers.getBucketLocation(call, bucket)
                    }
                    call.request.queryParameters["uploads"] != null -> {
                        // ListMultipartUploads
                        val prefix = call.request.queryParameters["prefix"]
                        val delimiter = call.request.queryParameters["delimiter"]
                        multipart.listMultipartUploads(call, bucket, prefix, delimiter)
                    }
                    else -> {
                        val listType = call.request.queryParameters["list-type"]
                        if (listType == "2" || listType == null) {
                            val maxKeys = call.request.queryParameters["max-keys"]?.toIntOrNull() ?: 1000
                            val continuationToken = call.request.queryParameters["continuation-token"]
                            val startAfter = call.request.queryParameters["start-after"]
                            val prefix = call.request.queryParameters["prefix"]
                            val delimiter = call.request.queryParameters["delimiter"]
                            val encodingType = call.request.queryParameters["encoding-type"]
                            handlers.listObjectsV2(
                                call, bucket, maxKeys, continuationToken, startAfter,
                                prefix, delimiter, encodingType
                            )
                        } else {
                            throw S3Errors.notImplemented("list-type=$listType")
                        }
                    }
                }
            }
        }

        // Some SDKs normalize bucket-level operations to /{bucket}/. Treat the
        // trailing slash as bucket-root, not as an empty object key.
        route("/{bucket}/") {
            put {
                val bucket = call.parameters["bucket"]!!
                handlers.createBucket(call, bucket)
            }
            head {
                val bucket = call.parameters["bucket"]!!
                handlers.headBucket(call, bucket)
            }
            delete {
                val bucket = call.parameters["bucket"]!!
                handlers.deleteBucket(call, bucket)
            }
            get {
                val bucket = call.parameters["bucket"]!!
                when {
                    call.request.queryParameters["location"] != null -> {
                        handlers.getBucketLocation(call, bucket)
                    }
                    call.request.queryParameters["uploads"] != null -> {
                        val prefix = call.request.queryParameters["prefix"]
                        val delimiter = call.request.queryParameters["delimiter"]
                        multipart.listMultipartUploads(call, bucket, prefix, delimiter)
                    }
                    else -> {
                        val listType = call.request.queryParameters["list-type"]
                        if (listType == "2" || listType == null) {
                            val maxKeys = call.request.queryParameters["max-keys"]?.toIntOrNull() ?: 1000
                            val continuationToken = call.request.queryParameters["continuation-token"]
                            val startAfter = call.request.queryParameters["start-after"]
                            val prefix = call.request.queryParameters["prefix"]
                            val delimiter = call.request.queryParameters["delimiter"]
                            val encodingType = call.request.queryParameters["encoding-type"]
                            handlers.listObjectsV2(
                                call, bucket, maxKeys, continuationToken, startAfter,
                                prefix, delimiter, encodingType
                            )
                        } else {
                            throw S3Errors.notImplemented("list-type=$listType")
                        }
                    }
                }
            }
        }

        // Object-level operations
        route("/{bucket}/{key...}") {
            // ---- POST: CreateMultipartUpload (?uploads) or CompleteMultipartUpload (?uploadId) ----
            post {
                val bucket = call.parameters["bucket"]!!
                val decodedKey = ObjectKey.fromPathSegment(joinPath(call.parameters.getAll("key") ?: emptyList()))
                val uploadId = call.request.queryParameters["uploadId"]
                val uploadsFlag = call.request.queryParameters["uploads"]
                when {
                    uploadsFlag != null -> {
                        // CreateMultipartUpload
                        validateObjectEncryptionHeaders(call, config)
                        val contentType = call.request.headers[HttpHeaders.ContentType] ?: "application/octet-stream"
                        val userMetadata = call.request.headers.entries()
                            .filter { it.key.startsWith("x-amz-meta-", ignoreCase = true) }
                            .associate { e ->
                                val name = e.key.substring("x-amz-meta-".length).lowercase(Locale.US)
                                name to e.value.joinToString(",")
                            }
                        val contentEncoding = call.request.headers[HttpHeaders.ContentEncoding]
                        val contentLanguage = call.request.headers[HttpHeaders.ContentLanguage]
                        val cacheControl = call.request.headers[HttpHeaders.CacheControl]
                        val contentDisposition = call.request.headers[HttpHeaders.ContentDisposition]
                        val expires = call.request.headers[HttpHeaders.Expires]
                        val storageClass = call.request.headers["x-amz-storage-class"]
                        val checksumAlgorithm = call.request.headers["x-amz-checksum-algorithm"]
                        multipart.createMultipartUpload(
                            call = call,
                            bucket = bucket,
                            key = decodedKey,
                            contentType = contentType,
                            userMetadata = userMetadata,
                            contentEncoding = contentEncoding,
                            contentLanguage = contentLanguage,
                            cacheControl = cacheControl,
                            contentDisposition = contentDisposition,
                            expires = expires,
                            storageClass = storageClass,
                            checksumAlgorithm = checksumAlgorithm
                        )
                    }
                    uploadId != null -> {
                        // CompleteMultipartUpload — parse XML body for part list.
                        val body = call.receiveBoundedText(config.operationalConfig.completeXmlMaxBytes)
                        val parts = parseCompleteMultipartUploadXml(body)
                        multipart.completeMultipartUpload(call, bucket, decodedKey, uploadId, parts)
                    }
                    else -> {
                        throw S3Errors.notImplemented("POST without ?uploads or ?uploadId")
                    }
                }
            }

            put {
                val bucket = call.parameters["bucket"]!!
                val decodedKey = ObjectKey.fromPathSegment(joinPath(call.parameters.getAll("key") ?: emptyList()))

                // ---- UploadPart detection: ?partNumber + ?uploadId ----
                val uploadId = call.request.queryParameters["uploadId"]
                val partNumberStr = call.request.queryParameters["partNumber"]
                if (uploadId != null && partNumberStr != null) {
                    val partNumber = partNumberStr.toIntOrNull()
                        ?: throw S3Errors.invalidArgument("partNumber", partNumberStr, "must be an integer")

                    // ---- UploadPartCopy: x-amz-copy-source present ----
                    val copySource = call.request.headers["x-amz-copy-source"]
                    if (copySource != null) {
                        val copySourceRange = call.request.headers["x-amz-copy-source-range"]
                        multipart.uploadPartCopy(
                            call = call,
                            destBucket = bucket,
                            destKey = decodedKey,
                            uploadId = uploadId,
                            partNumber = partNumber,
                            copySource = copySource,
                            copySourceRange = copySourceRange
                        )
                        return@put
                    }

                    val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    val expectedSha256 = call.request.headers["x-amz-content-sha256"]
                        ?.takeIf { it != "UNSIGNED-PAYLOAD" }
                    val expectedMd5Base64 = call.request.headers["Content-MD5"]
                    val checksumCrc32 = call.request.headers["x-amz-checksum-crc32"]
                    val checksumCrc32C = call.request.headers["x-amz-checksum-crc32c"]
                    val checksumSha1 = call.request.headers["x-amz-checksum-sha1"]
                    val checksumSha256 = call.request.headers["x-amz-checksum-sha256"]
                    val checksumAlgorithm =
                        call.request.headers["x-amz-sdk-checksum-algorithm"]
                            ?: call.request.headers["x-amz-checksum-algorithm"]
                    multipart.uploadPart(
                        call = call,
                        bucket = bucket,
                        key = decodedKey,
                        uploadId = uploadId,
                        partNumber = partNumber,
                        contentLength = contentLength,
                        expectedSha256 = expectedSha256,
                        expectedMd5Base64 = expectedMd5Base64,
                        checksumCrc32 = checksumCrc32,
                        checksumCrc32C = checksumCrc32C,
                        checksumSha1 = checksumSha1,
                        checksumSha256 = checksumSha256,
                        checksumAlgorithm = checksumAlgorithm
                    )
                    return@put
                }

                // ---- CopyObject detection: presence of x-amz-copy-source ----
                val copySource = call.request.headers["x-amz-copy-source"]
                if (copySource != null) {
                    validateObjectEncryptionHeaders(call, config)
                    val metadataDirective = call.request.headers["x-amz-metadata-directive"] ?: "COPY"
                    val contentType = call.request.headers[HttpHeaders.ContentType]
                    val userMetadata = call.request.headers.entries()
                        .filter { it.key.startsWith("x-amz-meta-", ignoreCase = true) }
                        .associate { e ->
                            val name = e.key.substring("x-amz-meta-".length).lowercase(Locale.US)
                            name to e.value.joinToString(",")
                        }
                    val contentEncoding = call.request.headers[HttpHeaders.ContentEncoding]
                    val contentLanguage = call.request.headers[HttpHeaders.ContentLanguage]
                    val cacheControl = call.request.headers[HttpHeaders.CacheControl]
                    val contentDisposition = call.request.headers[HttpHeaders.ContentDisposition]
                    val expires = call.request.headers[HttpHeaders.Expires]
                    val storageClass = call.request.headers["x-amz-storage-class"]
                    val ifMatch = call.request.headers["x-amz-copy-source-if-match"]
                    val ifNoneMatch = call.request.headers["x-amz-copy-source-if-none-match"]
                    val ifModifiedSince = call.request.headers["x-amz-copy-source-if-modified-since"]
                    val ifUnmodifiedSince = call.request.headers["x-amz-copy-source-if-unmodified-since"]

                    handlers.copyObject(
                        call = call,
                        destBucket = bucket,
                        destKey = decodedKey,
                        copySource = copySource,
                        metadataDirective = metadataDirective,
                        contentType = contentType,
                        userMetadata = userMetadata,
                        contentEncoding = contentEncoding,
                        contentLanguage = contentLanguage,
                        cacheControl = cacheControl,
                        contentDisposition = contentDisposition,
                        expires = expires,
                        storageClass = storageClass,
                        ifMatch = ifMatch,
                        ifNoneMatch = ifNoneMatch,
                        ifModifiedSince = ifModifiedSince,
                        ifUnmodifiedSince = ifUnmodifiedSince
                    )
                    return@put
                }

                // ---- Regular PutObject ----
                validateObjectEncryptionHeaders(call, config)
                val contentType = call.request.headers[HttpHeaders.ContentType] ?: "application/octet-stream"
                val userMetadata = call.request.headers.entries()
                    .filter { it.key.startsWith("x-amz-meta-", ignoreCase = true) }
                    .associate { e ->
                        val name = e.key.substring("x-amz-meta-".length).lowercase(Locale.US)
                        name to e.value.joinToString(",")
                    }
                val contentEncoding = call.request.headers[HttpHeaders.ContentEncoding]
                val contentLanguage = call.request.headers[HttpHeaders.ContentLanguage]
                val cacheControl = call.request.headers[HttpHeaders.CacheControl]
                val contentDisposition = call.request.headers[HttpHeaders.ContentDisposition]
                val expires = call.request.headers[HttpHeaders.Expires]
                val expectedSha256 = call.request.headers["x-amz-content-sha256"]
                    ?.takeIf { it != "UNSIGNED-PAYLOAD" }
                val expectedMd5Base64 = call.request.headers["Content-MD5"]
                val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
                val storageClass = call.request.headers["x-amz-storage-class"]
                val checksumCrc32 = call.request.headers["x-amz-checksum-crc32"]
                val checksumCrc32C = call.request.headers["x-amz-checksum-crc32c"]
                val checksumSha1 = call.request.headers["x-amz-checksum-sha1"]
                val checksumSha256 = call.request.headers["x-amz-checksum-sha256"]
                val checksumType = call.request.headers["x-amz-checksum-type"]

                handlers.putObject(
                    call = call,
                    bucket = bucket,
                    key = decodedKey,
                    contentType = contentType,
                    userMetadata = userMetadata,
                    contentEncoding = contentEncoding,
                    contentLanguage = contentLanguage,
                    cacheControl = cacheControl,
                    contentDisposition = contentDisposition,
                    expires = expires,
                    expectedSha256 = expectedSha256,
                    expectedMd5Base64 = expectedMd5Base64,
                    contentLength = contentLength,
                    ifNoneMatch = ifNoneMatch,
                    storageClass = storageClass,
                    checksumCrc32 = checksumCrc32,
                    checksumCrc32C = checksumCrc32C,
                    checksumSha1 = checksumSha1,
                    checksumSha256 = checksumSha256,
                    checksumType = checksumType
                )
            }
            head {
                val bucket = call.parameters["bucket"]!!
                val decodedKey = ObjectKey.fromPathSegment(joinPath(call.parameters.getAll("key") ?: emptyList()))
                val ifMatch = call.request.headers[HttpHeaders.IfMatch]
                val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
                handlers.headObject(call, bucket, decodedKey, ifMatch, ifNoneMatch)
            }
            get {
                val bucket = call.parameters["bucket"]!!
                val decodedKey = ObjectKey.fromPathSegment(joinPath(call.parameters.getAll("key") ?: emptyList()))
                // ListParts detection: ?uploadId
                val uploadId = call.request.queryParameters["uploadId"]
                if (uploadId != null) {
                    multipart.listParts(call, bucket, decodedKey, uploadId)
                    return@get
                }
                val range = call.request.headers[HttpHeaders.Range]
                val ifMatch = call.request.headers[HttpHeaders.IfMatch]
                val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
                handlers.getObject(call, bucket, decodedKey, range, ifMatch, ifNoneMatch)
            }
            delete {
                val bucket = call.parameters["bucket"]!!
                val decodedKey = ObjectKey.fromPathSegment(joinPath(call.parameters.getAll("key") ?: emptyList()))
                // AbortMultipartUpload detection: ?uploadId
                val uploadId = call.request.queryParameters["uploadId"]
                if (uploadId != null) {
                    multipart.abortMultipartUpload(call, bucket, decodedKey, uploadId)
                    return@delete
                }
                handlers.deleteObject(call, bucket, decodedKey)
            }
        }
    }
}

/** Re-join path segments that Ktor splits, then percent-decode each piece. */
private fun joinPath(segments: List<String>): String {
    if (segments.isEmpty()) return ""
    return segments.joinToString("/")
}

internal suspend fun ApplicationCall.receiveBoundedText(maxBytes: Long): String {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > maxBytes) {
        throw S3Errors.maxMessageLengthExceeded(declared, maxBytes)
    }

    val buffer = ByteArray(8 * 1024)
    val out = ByteArrayOutputStream()
    var total = 0L
    receiveStream().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read.toLong()
            if (total > maxBytes) {
                throw S3Errors.maxMessageLengthExceeded(total, maxBytes)
            }
            out.write(buffer, 0, read)
        }
    }
    return out.toString(Charsets.UTF_8)
}

/**
 * Parse the CompleteMultipartUpload XML body using a proper SAX parser.
 *
 * Handles XML namespaces, malformed XML, XML escaping, and large bodies
 * defensively. Rejects bodies with no `<Part>` elements.
 *
 * Expected schema:
 *   <CompleteMultipartUpload>
 *     <Part>
 *       <PartNumber>1</PartNumber>
 *       <ETag>"abc..."</ETag>
 *     </Part>
 *     ...
 *   </CompleteMultipartUpload>
 */
internal fun parseCompleteMultipartUploadXml(body: String): List<RequestedPart> {
    if (body.isBlank()) return emptyList()

    val parts = ArrayList<RequestedPart>()
    var currentPartNumber: Int? = null
    var currentEtag: String? = null
    var inPart = false
    var currentElement: String? = null
    val charBuf = StringBuilder()

    val handler = object : org.xml.sax.helpers.DefaultHandler() {
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: org.xml.sax.Attributes?) {
            val name = localName?.takeIf { it.isNotEmpty() } ?: qName
            currentElement = name
            charBuf.clear()
            when (name) {
                "Part" -> {
                    inPart = true
                    currentPartNumber = null
                    currentEtag = null
                }
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (currentElement != null && inPart) {
                charBuf.append(ch, start, length)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = localName?.takeIf { it.isNotEmpty() } ?: qName
            when (name) {
                "PartNumber" -> {
                    val text = charBuf.toString().trim()
                    currentPartNumber = text.toIntOrNull()
                        ?: throw S3Errors.malformedXML("PartNumber must be an integer, got '$text'")
                }
                "ETag" -> {
                    currentEtag = charBuf.toString().trim().ifEmpty { null }
                }
                "Part" -> {
                    val num = currentPartNumber
                        ?: throw S3Errors.malformedXML("Part is missing PartNumber")
                    parts += RequestedPart(partNumber = num, etag = currentEtag)
                    inPart = false
                    currentPartNumber = null
                    currentEtag = null
                }
            }
            currentElement = null
        }
    }

    try {
        val reader = javax.xml.parsers.SAXParserFactory.newInstance().newSAXParser().xmlReader
        reader.contentHandler = handler
        reader.errorHandler = object : org.xml.sax.helpers.DefaultHandler() {
            override fun error(e: org.xml.sax.SAXParseException) {
                throw S3Errors.malformedXML("XML parse error at line ${e.lineNumber}: ${e.message}")
            }
            override fun fatalError(e: org.xml.sax.SAXParseException) {
                throw S3Errors.malformedXML("XML parse error at line ${e.lineNumber}: ${e.message}")
            }
        }
        // Disable external entity processing for security (XXE prevention).
        reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        reader.parse(org.xml.sax.InputSource(body.reader()))
    } catch (e: S3Exception) {
        throw e
    } catch (e: org.xml.sax.SAXException) {
        throw S3Errors.malformedXML("XML parse error: ${e.message}")
    } catch (e: Exception) {
        throw S3Errors.malformedXML("XML parse error: ${e.message}")
    }

    if (parts.isEmpty()) {
        throw S3Errors.malformedXML("CompleteMultipartUpload body has no <Part> elements")
    }
    return parts
}

private fun validateObjectEncryptionHeaders(call: ApplicationCall, config: ServerConfig) {
    val headers = call.request.headers
    if (
        headers["x-amz-server-side-encryption-customer-algorithm"] != null ||
        headers["x-amz-server-side-encryption-customer-key"] != null ||
        headers["x-amz-server-side-encryption-customer-key-MD5"] != null
    ) {
        throw S3Errors.notImplemented("SSE-C is not supported")
    }
    if (headers["x-amz-server-side-encryption-aws-kms-key-id"] != null) {
        throw S3Errors.notImplemented("SSE-KMS is not supported")
    }
    val requested = headers["x-amz-server-side-encryption"] ?: return
    if (!requested.equals("AES256", ignoreCase = true)) {
        throw S3Errors.notImplemented("server-side encryption mode '$requested' is not supported")
    }
    if (!config.objectEncryptionConfig.isEnabled) {
        throw S3Errors.notImplemented("SSE-S3 requires S3_OBJECT_ENCRYPTION_MODE=sse-s3")
    }
}
