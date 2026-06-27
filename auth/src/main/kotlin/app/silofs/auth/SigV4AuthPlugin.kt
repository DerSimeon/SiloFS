package app.silofs.auth

import app.silofs.common.S3ErrorCode
import app.silofs.common.S3Errors
import app.silofs.common.S3Exception
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.util.AttributeKey

/**
 * A parsed view of the request that the verifier needs. We deliberately decouple
 * this from Ktor's [ApplicationCall] so the verifier can be unit tested without
 * spinning up a server.
 */
data class SignedHttpRequest(
    val method: String,
    val path: String,
    val queryString: String,
    val headers: Map<String, List<String>>,
    val payloadHash: String,
)

data class AuthenticatedSigV4Request(
    val amzDate: String,
    val date: String,
    val region: String,
    val service: String,
    val seedSignature: String,
    val signingKey: ByteArray,
)

/**
 * Builds a [SignedHttpRequest] from a Ktor call. Headers are looked up in a
 * case-insensitive way (Ktor's Headers is already case-insensitive).
 */
fun ApplicationCall.toSignedRequest(): SignedHttpRequest {
    val headers = mutableMapOf<String, List<String>>()
    request.headers.entries().forEach { (k, v) ->
        headers[k.lowercase()] = v
    }
    val payloadHash = request.headers["x-amz-content-sha256"] ?: "UNSIGNED-PAYLOAD"
    return SignedHttpRequest(
        method = request.httpMethod.value,
        path = request.path(),
        queryString = request.queryString(),
        headers = headers,
        payloadHash = payloadHash,
    )
}

object SigV4CanonicalBuilder {
    fun canonicalUri(path: String): String {
        val sb = StringBuilder(path.length + 8)
        val decoded = sigV4Decode(path)
        var i = 0
        while (i < decoded.length) {
            val cp = decoded.codePointAt(i)
            when {
                cp == '/'.code -> sb.append('/')
                cp.isUnreservedCodePoint() -> sb.appendCodePoint(cp)
                else -> {
                    String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).forEach { b ->
                        sb.append('%').append("%02X".format(b))
                    }
                }
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    fun canonicalQueryString(query: String): String {
        if (query.isBlank()) return ""
        // Parse "k1=v1&k2=v2" preserving multi-values per key.
        // Each pair is decoded (to handle percent-encoding from the client),
        // then re-encoded with the strict SigV4 encoder below.
        //
        // Gap #6: use SigV4-specific percent decoding (NOT URLDecoder.decode,
        // which treats '+' as a space — that's form-url-encoding behavior,
        // not strict SigV4 URI encoding). Reject malformed percent-encoding
        // deterministically instead of silently skipping.
        val pairs =
            query.split('&').mapIndexedNotNull { idx, kv ->
                if (kv.isEmpty()) return@mapIndexedNotNull null
                val eq = kv.indexOf('=')
                try {
                    if (eq < 0) {
                        sigV4Decode(kv) to ""
                    } else {
                        sigV4Decode(kv.substring(0, eq)) to sigV4Decode(kv.substring(eq + 1))
                    }
                } catch (e: IllegalArgumentException) {
                    // Malformed percent-encoding — reject the request.
                    throw S3Errors.authorizationHeaderMalformed(
                        "Malformed percent-encoding in query string at position $idx: ${e.message}",
                    )
                }
            }
        // Sort by key, then by value, both using byte-wise comparison
        // (which is what SigV4 requires). Kotlin's default String sort is
        // UTF-16 code-unit comparison, which matches byte-wise for ASCII
        // and is what AWS actually does in practice.
        return pairs
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
    }

    fun canonicalHeaders(
        headers: Map<String, List<String>>,
        signedHeaders: List<String>,
    ): String {
        // Gap #7: verify every signed header is present. If a header listed
        // in SignedHeaders is absent from the request, the signature cannot
        // be valid — reject deterministically rather than emitting an empty
        // value that would produce a different canonical request.
        for (h in signedHeaders) {
            if (!headers.containsKey(h)) {
                throw S3Errors.authorizationHeaderMalformed(
                    "Signed header '$h' is not present in the request",
                )
            }
        }
        return signedHeaders.joinToString("") { h ->
            val values = headers[h].orEmpty()
            val joined = values.joinToString(",") { trimAll(it) }
            "$h:$joined\n"
        }
    }

    fun signedHeadersString(signedHeaders: List<String>): String = signedHeaders.joinToString(";")

    private fun trimAll(v: String): String = v.trim().replace(Regex("\\s+"), " ")

    private fun Char.isUnreserved(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_' || this == '-' || this == '~' || this == '.'

    private fun Int.isUnreservedCodePoint(): Boolean =
        this in 'a'.code..'z'.code ||
            this in 'A'.code..'Z'.code ||
            this in '0'.code..'9'.code ||
            this == '_'.code ||
            this == '-'.code ||
            this == '~'.code ||
            this == '.'.code

    private fun decode(s: String): String = sigV4Decode(s)

    /**
     * SigV4-specific percent decoder.
     *
     * Unlike `URLDecoder.decode`, this does NOT treat `+` as a space.
     * SigV4 uses strict URI encoding where only `%XX` sequences are decoded.
     *
     * Throws `IllegalArgumentException` on malformed sequences (e.g. `%GG`
     * or a trailing `%` with no hex digits), so the caller can reject the
     * request deterministically.
     */
    private fun sigV4Decode(s: String): String {
        if (s.isEmpty()) return s
        val bytes = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' -> {
                    if (i + 2 >= s.length) {
                        throw IllegalArgumentException("Incomplete percent-encoding at position $i in '$s'")
                    }
                    val hi =
                        hexValue(s[i + 1])
                            ?: throw IllegalArgumentException("Invalid hex digit '${s[i + 1]}' at position ${i + 1} in '$s'")
                    val lo =
                        hexValue(s[i + 2])
                            ?: throw IllegalArgumentException("Invalid hex digit '${s[i + 2]}' at position ${i + 2} in '$s'")
                    bytes.add(((hi shl 4) or lo).toByte())
                    i += 3
                }
                c == '+' -> {
                    // SigV4 does NOT decode '+' as space. Pass through literally.
                    bytes.add('+'.code.toByte())
                    i++
                }
                c.code < 128 -> {
                    bytes.add(c.code.toByte())
                    i++
                }
                else -> {
                    // Multi-byte UTF-8 code point — encode to bytes.
                    val cp = s.codePointAt(i)
                    for (b in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) {
                        bytes.add(b)
                    }
                    i += Character.charCount(cp)
                }
            }
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun hexValue(c: Char): Int? =
        when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> null
        }

    private fun encode(s: String): String {
        val sb = StringBuilder(s.length + 4)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            when {
                cp.isUnreservedCodePoint() -> sb.appendCodePoint(cp)
                else -> {
                    val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
                    for (b in bytes) {
                        sb.append('%').append("%02X".format(b))
                    }
                }
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    fun build(
        req: SignedHttpRequest,
        auth: SigV4Authorization,
    ): CanonicalRequest {
        val canonHeaders = canonicalHeaders(req.headers, auth.signedHeaders)
        val signedHeadersStr = signedHeadersString(auth.signedHeaders)
        return CanonicalRequest(
            method = req.method,
            canonicalUri = canonicalUri(req.path),
            canonicalQueryString = canonicalQueryString(req.queryString),
            canonicalHeaders = canonHeaders,
            signedHeaders = signedHeadersStr,
            payloadHash = req.payloadHash,
        )
    }
}

/**
 * Ktor plugin that runs SigV4 verification on every request unless it is on the
 * `publicPaths` allowlist.
 */
class SigV4AuthConfig {
    var credentialProvider: CredentialProvider = StaticCredentialProvider(emptyMap())
    var region: String = "us-east-1"
    var publicPaths: Set<String> = setOf("/healthz", "/metricsz")
    var enabled: Boolean = true

    /** Maximum allowed clock skew in seconds (default 15 min, same as AWS). */
    var maxClockSkewSeconds: Long = 900L
    var rateLimiter: ((String) -> Boolean)? = null
}

val AuthenticatedAccessKeyIdKey: AttributeKey<String> = AttributeKey("SilofsAuthenticatedAccessKeyId")
val AuthenticatedSigV4RequestKey: AttributeKey<AuthenticatedSigV4Request> =
    AttributeKey("SilofsAuthenticatedSigV4Request")

val SigV4Auth: ApplicationPlugin<SigV4AuthConfig> =
    createApplicationPlugin(
        name = "SigV4Auth",
        createConfiguration = { SigV4AuthConfig() },
    ) {
        val config = pluginConfig
        if (!config.enabled) return@createApplicationPlugin

        application.intercept(ApplicationCallPipeline.Setup) {
            val path = context.request.path()
            if (path in config.publicPaths) return@intercept

            // ---- Presigned URL detection: query params contain X-Amz-Algorithm ----
            val algorithm = context.request.queryParameters["X-Amz-Algorithm"]
            if (algorithm != null) {
                verifyPresignedUrl(context, config)
                return@intercept
            }

            // ---- Header-based SigV4 ----
            val auth = SigV4Authorization.parse(context.request.headers["Authorization"])

            val amzDate =
                context.request.headers["x-amz-date"]
                    ?: context.request.headers["Date"]
                    ?: throw S3Errors.authorizationHeaderMalformed("Missing x-amz-date header")

            // ---- Clock-skew validation (±maxClockSkewSeconds) ----
            validateClockSkew(amzDate, config.maxClockSkewSeconds)

            val creds =
                config.credentialProvider.lookup(auth.accessKeyId)
                    ?: throw S3Errors.invalidAccessKeyId(auth.accessKeyId)
            enforceRateLimit(config, auth.accessKeyId)

            if (auth.region != config.region) {
                throw S3Errors.authorizationHeaderMalformed(
                    "Credential region '${auth.region}' does not match server region '${config.region}'",
                )
            }

            // ---- Strict signed-headers validation ----
            // AWS requires that security-relevant headers are always signed.
            // We reject requests where host is not in the signed-headers list
            // (host is mandatory per SigV4 spec).
            if ("host" !in auth.signedHeaders) {
                throw S3Errors.authorizationHeaderMalformed(
                    "SignedHeaders must include 'host'",
                )
            }

            val signedRequest = context.toSignedRequest()
            val canonical = SigV4CanonicalBuilder.build(signedRequest, auth)
            val ok = SigV4Verifier.verify(canonical, amzDate, auth, creds)
            if (!ok) {
                // Do NOT expose the canonical request in the error message — that
                // leaks signing internals to attackers. Return only the standard
                // AWS error text.
                throw S3Errors.signatureDoesNotMatch(
                    "The request signature we calculated does not match the signature you provided. " +
                        "Check your AWS Secret Access Key and signing method.",
                )
            }
            context.attributes.put(AuthenticatedAccessKeyIdKey, auth.accessKeyId)
            context.attributes.put(
                AuthenticatedSigV4RequestKey,
                AuthenticatedSigV4Request(
                    amzDate = amzDate,
                    date = auth.date,
                    region = auth.region,
                    service = auth.service,
                    seedSignature = auth.signature,
                    signingKey =
                        SigV4Verifier.signingKey(
                            secretAccessKey = creds.secretAccessKey,
                            dateStamp = auth.date,
                            region = auth.region,
                            service = auth.service,
                        ),
                ),
            )
        }
    }

private fun enforceRateLimit(
    config: SigV4AuthConfig,
    accessKeyId: String,
) {
    if (config.rateLimiter?.invoke(accessKeyId) == false) {
        throw S3Exception(S3ErrorCode.SlowDown, "Reduce your request rate")
    }
}

/**
 * Validate that the request timestamp is within [maxSkewSeconds] of the
 * server's clock. AWS rejects requests outside a ±15 minute window.
 */
private fun validateClockSkew(
    amzDate: String,
    maxSkewSeconds: Long,
) {
    val signedInstant =
        try {
            parseAmzDate(amzDate)
        } catch (e: Exception) {
            throw S3Errors.authorizationHeaderMalformed("Invalid x-amz-date format: $amzDate")
        }
    val now = java.time.Instant.now()
    val skew =
        java.time.Duration
            .between(signedInstant, now)
            .abs()
            .seconds
    if (skew > maxSkewSeconds) {
        throw S3Exception(
            S3ErrorCode.RequestTimeTooSkewed,
            "The difference between the request time and the server's time is too large. " +
                "skew=${skew}s max=$maxSkewSeconds",
        )
    }
}

/**
 * Verify a presigned URL request.
 *
 * Presigned URLs carry the SigV4 parameters in the query string instead of
 * the Authorization header. We reconstruct the canonical request from the
 * query params, recompute the signature, and compare.
 */
private fun verifyPresignedUrl(
    context: ApplicationCall,
    config: SigV4AuthConfig,
) {
    val q = context.request.queryParameters
    val algorithm =
        q["X-Amz-Algorithm"]
            ?: throw S3Errors.authorizationHeaderMalformed("Missing X-Amz-Algorithm query parameter")
    if (algorithm != "AWS4-HMAC-SHA256") {
        throw S3Errors.authorizationHeaderMalformed("Unsupported algorithm: $algorithm")
    }
    val credential =
        q["X-Amz-Credential"]
            ?: throw S3Errors.authorizationHeaderMalformed("Missing X-Amz-Credential query parameter")
    val credParts = credential.split("/")
    if (credParts.size != 5) {
        throw S3Errors.authorizationHeaderMalformed("Invalid X-Amz-Credential: $credential")
    }
    val (akid, dateStamp, region, service, _) = credParts
    val amzDate =
        q["X-Amz-Date"]
            ?: throw S3Errors.authorizationHeaderMalformed("Missing X-Amz-Date query parameter")
    val expiresStr =
        q["X-Amz-Expires"]
            ?: throw S3Errors.authorizationHeaderMalformed("Missing X-Amz-Expires query parameter")
    val expires =
        expiresStr.toLongOrNull()
            ?: throw S3Errors.authorizationHeaderMalformed("Invalid X-Amz-Expires: $expiresStr")
    if (expires < 0 || expires > 7 * 24 * 3600) {
        throw S3Errors.authorizationHeaderMalformed("X-Amz-Expires must be 0..604800, got $expires")
    }
    val signedHeaders =
        q["X-Amz-SignedHeaders"]
            ?: throw S3Errors.authorizationHeaderMalformed("Missing X-Amz-SignedHeaders query parameter")
    val signature =
        q["X-Amz-Signature"]
            ?: throw S3Errors.authorizationHeaderMalformed("Missing X-Amz-Signature query parameter")

    // Check expiry.
    val signedInstant = parseAmzDate(amzDate)
    val now = java.time.Instant.now()
    if (now.isAfter(signedInstant.plusSeconds(expires))) {
        throw S3Exception(
            S3ErrorCode.AccessDenied,
            "Request has expired. signedAt=$amzDate expires=$expires now=$now",
        )
    }

    val creds =
        config.credentialProvider.lookup(akid)
            ?: throw S3Errors.invalidAccessKeyId(akid)
    enforceRateLimit(config, akid)

    if (region != config.region) {
        throw S3Errors.authorizationHeaderMalformed(
            "Credential region '$region' does not match server region '${config.region}'",
        )
    }

    // Build the canonical request from the query string.
    // The signed query params are all params EXCEPT X-Amz-Signature.
    val signedHeadersList = signedHeaders.split(";").map { it.trim().lowercase() }
    val queryStringWithoutSig =
        context.request
            .queryString()
            .split("&")
            .filter { it.isNotEmpty() && !it.startsWith("X-Amz-Signature=") }
            .joinToString("&")

    val canonicalUri = SigV4CanonicalBuilder.canonicalUri(context.request.path())
    val canonicalQueryString = SigV4CanonicalBuilder.canonicalQueryString(queryStringWithoutSig)
    val headersMap = mutableMapOf<String, List<String>>()
    context.request.headers.entries().forEach { (k, v) ->
        headersMap[k.lowercase()] = v
    }
    val canonicalHeaders = SigV4CanonicalBuilder.canonicalHeaders(headersMap, signedHeadersList)

    val canonicalRequest =
        CanonicalRequest(
            method = context.request.httpMethod.value,
            canonicalUri = canonicalUri,
            canonicalQueryString = canonicalQueryString,
            canonicalHeaders = canonicalHeaders,
            signedHeaders = SigV4CanonicalBuilder.signedHeadersString(signedHeadersList),
            payloadHash = "UNSIGNED-PAYLOAD",
        )

    val ok =
        SigV4Verifier.verify(
            canonicalRequest,
            amzDate,
            SigV4Authorization(
                algorithm = algorithm,
                accessKeyId = akid,
                date = dateStamp,
                region = region,
                service = service,
                signedHeaders = signedHeadersList,
                signature = signature,
            ),
            creds,
        )
    if (!ok) {
        // Do NOT leak the canonical request — return only the standard error.
        throw S3Errors.signatureDoesNotMatch(
            "The request signature we calculated does not match the signature you provided. " +
                "Check your AWS Secret Access Key and signing method.",
        )
    }
    context.attributes.put(AuthenticatedAccessKeyIdKey, akid)
    context.attributes.put(
        AuthenticatedSigV4RequestKey,
        AuthenticatedSigV4Request(
            amzDate = amzDate,
            date = dateStamp,
            region = region,
            service = service,
            seedSignature = signature,
            signingKey =
                SigV4Verifier.signingKey(
                    secretAccessKey = creds.secretAccessKey,
                    dateStamp = dateStamp,
                    region = region,
                    service = service,
                ),
        ),
    )
}

private fun parseAmzDate(raw: String): java.time.Instant {
    val fmt =
        java.time.format.DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(java.time.ZoneOffset.UTC)
    return java.time.Instant.from(fmt.parse(raw))
}
