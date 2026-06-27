package app.silofs.auth

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Generates presigned URLs for GET and PUT operations.
 *
 * A presigned URL embeds the SigV4 signature in the query string so that
 * the holder can perform the operation without any Authorization header.
 * The URL expires after a caller-specified duration (default 1 hour,
 * maximum 7 days per AWS limits).
 *
 * The query-string form of SigV4 uses these parameters:
 *   - `X-Amz-Algorithm`     = `AWS4-HMAC-SHA256`
 *   - `X-Amz-Credential`    = `<akid>/<date>/<region>/s3/aws4_request`
 *   - `X-Amz-Date`          = `yyyyMMdd'T'HHmmss'Z'`
 *   - `X-Amz-Expires`       = duration in seconds
 *   - `X-Amz-SignedHeaders` = `host` (minimum)
 *   - `X-Amz-Signature`     = the computed signature
 *
 * The canonical request is built the same way as for header-based SigV4,
 * except:
 *   - The payload hash is always `UNSIGNED-PAYLOAD` (the body isn't known
 *     at signing time).
 *   - The signed headers typically include only `host` (the client may add
 *     more, but we keep it minimal for presigned URLs).
 */
class PresignedUrlGenerator(
    private val region: String,
    private val accessKeyId: String,
    private val secretAccessKey: String,
) {
    /**
     * Generate a presigned URL for the given HTTP method and object path.
     *
     * @param method `GET` or `PUT`
     * @param host the host:port the client will connect to (e.g. `localhost:8080`)
     * @param path the object path (e.g. `/bucket/key`)
     * @param expires duration until the URL expires (default 1h, max 7d)
     * @return the full presigned URL string
     */
    fun generate(
        method: String,
        host: String,
        path: String,
        expires: Duration = Duration.ofHours(1),
    ): String {
        require(method == "GET" || method == "PUT") { "presigned URLs only support GET and PUT" }
        require(!expires.isNegative) { "expires must be non-negative" }
        require(expiresSeconds(expires) <= 7 * 24 * 3600) { "presigned URLs cannot expire more than 7 days in the future" }

        val now = Instant.now()
        val amzDate = formatAmzDate(now)
        val dateStamp = formatDateStamp(now)
        val expiresStr = expiresSeconds(expires).toString()

        // Build the canonical query string with all signing params except
        // X-Amz-Signature (which is computed last).
        val credential = "$accessKeyId/$dateStamp/$region/s3/aws4_request"
        val signedHeaders = "host"

        val params =
            linkedMapOf(
                "X-Amz-Algorithm" to "AWS4-HMAC-SHA256",
                "X-Amz-Credential" to credential,
                "X-Amz-Date" to amzDate,
                "X-Amz-Expires" to expiresStr,
                "X-Amz-SignedHeaders" to signedHeaders,
            )

        // Canonical query string: sorted by key, URL-encoded values.
        val canonicalQueryString =
            params.entries
                .sortedBy { it.key }
                .joinToString("&") { (k, v) ->
                    "${encode(k)}=${encode(v)}"
                }

        // Canonical request
        val canonicalUri = encodePath(path)
        val canonicalHeaders = "host:$host\n"
        val payloadHash = "UNSIGNED-PAYLOAD"
        val canonicalRequest =
            listOf(
                method,
                canonicalUri,
                canonicalQueryString,
                canonicalHeaders,
                "",
                signedHeaders,
                payloadHash,
            ).joinToString("\n")

        // String to sign
        val scope = "$dateStamp/$region/s3/aws4_request"
        val crHash = Sha256.canonicalHash(canonicalRequest)
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n$crHash"

        // Compute signature
        val signingKey = SigV4Verifier.signingKey(secretAccessKey, dateStamp, region, "s3")
        val sig =
            HmacSha256
                .hmac(signingKey, stringToSign.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        // Build the final URL
        val finalParams = canonicalQueryString + "&X-Amz-Signature=" + sig
        return "http://$host$path?$finalParams"
    }

    /** Convenience for GET. */
    fun presignedGet(
        host: String,
        path: String,
        expires: Duration = Duration.ofHours(1),
    ): String = generate("GET", host, path, expires)

    /** Convenience for PUT. */
    fun presignedPut(
        host: String,
        path: String,
        expires: Duration = Duration.ofHours(1),
    ): String = generate("PUT", host, path, expires)

    private fun expiresSeconds(d: Duration): Long = d.seconds

    private fun formatAmzDate(instant: Instant): String =
        DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(instant)

    private fun formatDateStamp(instant: Instant): String =
        DateTimeFormatter
            .ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC)
            .format(instant)

    private fun encode(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            when {
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

    private fun encodePath(path: String): String {
        val sb = StringBuilder(path.length + 8)
        for (c in path) {
            when {
                c == '/' -> sb.append('/')
                c in 'a'..'z' ||
                    c in 'A'..'Z' ||
                    c in '0'..'9' ||
                    c == '-' ||
                    c == '_' ||
                    c == '.' ||
                    c == '~' -> sb.append(c)
                else -> {
                    for (b in c.toString().toByteArray(Charsets.UTF_8)) {
                        sb.append('%').append("%02X".format(b))
                    }
                }
            }
        }
        return sb.toString()
    }
}
