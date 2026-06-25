package app.silofs.common

import java.util.Locale

/**
 * S3 bucket naming rules per
 * https://docs.aws.amazon.com/AmazonS3/latest/userguide/bucketnamingrules.html
 *
 * Summary:
 *  - 3 to 63 characters
 *  - lowercase letters, digits, dots, hyphens
 *  - must begin and end with a letter or digit
 *  - must not be IP-address formatted (e.g. 192.168.0.1)
 *  - must not start with `xn--` (punycode) or `sthree-` (reserved prefix)
 *  - must not end with `-s3alias` or `--ol-s3`
 */
object BucketName {

    private val allowedPattern = Regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")
    private val ipAddressPattern = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    fun validate(name: String) {
        if (name.length < 3 || name.length > 63) {
            throw S3Exception(
                S3ErrorCode.InvalidBucketName,
                "The specified bucket is not valid: $name (length must be 3-63)"
            )
        }
        if (!allowedPattern.matches(name)) {
            throw S3Exception(
                S3ErrorCode.InvalidBucketName,
                "The specified bucket is not valid: $name (must match ${allowedPattern.pattern})"
            )
        }
        if (name.contains("..")) {
            throw S3Exception(
                S3ErrorCode.InvalidBucketName,
                "The specified bucket is not valid: $name (consecutive dots not allowed)"
            )
        }
        if (ipAddressPattern.matches(name) &&
            name.split('.').all { it.toInt() in 0..255 }
        ) {
            throw S3Exception(
                S3ErrorCode.InvalidBucketName,
                "The specified bucket is not valid: $name (must not be an IP address)"
            )
        }
        if (name.startsWith("xn--") || name.startsWith("sthree-")) {
            throw S3Exception(
                S3ErrorCode.InvalidBucketName,
                "The specified bucket is not valid: $name (reserved prefix)"
            )
        }
        if (name.endsWith("-s3alias") || name.endsWith("--ol-s3")) {
            throw S3Exception(
                S3ErrorCode.InvalidBucketName,
                "The specified bucket is not valid: $name (reserved suffix)"
            )
        }
    }

    fun isValid(name: String): Boolean = try {
        validate(name); true
    } catch (e: S3Exception) {
        false
    }
}

/**
 * S3 object key rules per
 * https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-keys.html
 *
 * We allow up to 1024 UTF-8 bytes and reject the small set of characters AWS
 * documents as "characters to avoid". The M1 implementation is intentionally
 * permissive about keys with slashes so that pseudo-directory layouts work.
 */
object ObjectKey {

    private const val MAX_BYTES = 1024
    private val forbiddenChars = setOf('\u0000', '\r', '\n')
    private val forbiddenSequences = listOf("../", "..\\")
    private val forbiddenSuffixes = listOf(".", "/")

    fun validate(key: String) {
        val bytes = key.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) {
            throw S3Exception(S3ErrorCode.InvalidObjectName, "Object key must not be empty")
        }
        if (bytes.size > MAX_BYTES) {
            throw S3Exception(
                S3ErrorCode.InvalidObjectName,
                "Object key too long: ${bytes.size} bytes (max $MAX_BYTES)"
            )
        }
        if (key.any { it in forbiddenChars }) {
            throw S3Exception(
                S3ErrorCode.InvalidObjectName,
                "Object key contains a forbidden character: $key"
            )
        }
        forbiddenSequences.forEach { seq ->
            if (key.contains(seq)) {
                throw S3Exception(
                    S3ErrorCode.InvalidObjectName,
                    "Object key contains forbidden sequence '$seq': $key"
                )
            }
        }
        forbiddenSuffixes.forEach { suffix ->
            if (key.endsWith(suffix)) {
                throw S3Exception(
                    S3ErrorCode.InvalidObjectName,
                    "Object key must not end with '$suffix': $key"
                )
            }
        }
    }

    fun isValid(key: String): Boolean = try {
        validate(key); true
    } catch (e: S3Exception) {
        false
    }

    /**
     * Normalise a key parsed from the path component. Ktor route parameters may
     * already be decoded, so literal '%' characters from object keys must remain
     * valid. Decode only well-formed percent escapes and never treat '+' as a
     * space; that is HTML form behaviour, not S3 path behaviour.
     */
    fun fromPathSegment(rawSegment: String): String {
        if ('%' !in rawSegment) return rawSegment
        val bytes = ArrayList<Byte>(rawSegment.length)
        var i = 0
        while (i < rawSegment.length) {
            val c = rawSegment[i]
            if (c == '%' && i + 2 < rawSegment.length) {
                val hi = rawSegment[i + 1].hexValue()
                val lo = rawSegment[i + 2].hexValue()
                if (hi != null && lo != null) {
                    bytes.add(((hi shl 4) or lo).toByte())
                    i += 3
                    continue
                }
            }
            c.toString().toByteArray(Charsets.UTF_8).forEach { bytes.add(it) }
            i++
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun Char.hexValue(): Int? = when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> null
    }
}

/**
 * Request ID format used in S3 error responses. AWS uses an opaque 16-char hex;
 * we match the shape.
 */
object RequestIds {
    fun newRequestId(): String {
        val bytes = ByteArray(8)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
            .lowercase(Locale.US)
    }
}
