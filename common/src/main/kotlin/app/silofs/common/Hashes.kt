package app.silofs.common

/**
 * ETag helper. S3 ETags are quoted MD5 hex strings for non-multipart uploads.
 * Multipart ETags are the MD5 of the concatenated MD5 bytes (raw, not hex)
 * followed by `-N` where N is the part count; we follow the same convention
 * in M3.
 *
 * Example: a 3-part upload where each part has MD5 `aa`, `bb`, `cc` (16 bytes
 * each, raw) produces an ETag of `"<md5(aabbcc)>-3"`.
 */
object ETag {
    fun fromMd5Hex(md5Hex: String): String {
        val lower = md5Hex.lowercase()
        return "\"$lower\""
    }

    fun fromMd5Bytes(md5: ByteArray): String = fromMd5Hex(md5.joinToString("") { "%02x".format(it) })

    /** Returns the hex without the surrounding quotes, or null if not an MD5 ETag. */
    fun toMd5Hex(etag: String): String? {
        if (!etag.startsWith("\"") || !etag.endsWith("\"")) return null
        val inner = etag.substring(1, etag.length - 1)
        if (inner.contains("-")) return null
        return inner
    }

    /**
     * Compute the multipart ETag from the raw MD5 bytes of each part, in order.
     *
     * AWS S3 calculates this as:
     *   1. Concatenate the raw 16-byte MD5 of each part (NOT the hex string).
     *   2. Compute the MD5 of that concatenation.
     *   3. Hex-encode the result and append `-N` where N is the part count.
     *   4. Wrap in double quotes.
     *
     * The result is distinct from a regular MD5 ETag (it always contains a `-`),
     * which lets clients detect multipart objects and avoid using the ETag as a
     * direct content hash.
     */
    fun fromMultipart(partMd5s: List<ByteArray>): String {
        require(partMd5s.isNotEmpty()) { "multipart ETag requires at least one part" }
        val concatenated = ByteArray(partMd5s.size * 16)
        var offset = 0
        for (md5 in partMd5s) {
            require(md5.size == 16) { "MD5 must be 16 bytes, got ${md5.size}" }
            System.arraycopy(md5, 0, concatenated, offset, 16)
            offset += 16
        }
        val digest =
            java.security.MessageDigest
                .getInstance("MD5")
                .digest(concatenated)
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "\"$hex-${partMd5s.size}\""
    }

    /** Returns true if the ETag looks like a multipart ETag (contains `-N`). */
    fun isMultipart(etag: String): Boolean {
        if (!etag.startsWith("\"") || !etag.endsWith("\"")) return false
        val inner = etag.substring(1, etag.length - 1)
        val dash = inner.lastIndexOf('-')
        if (dash <= 0) return false
        return inner.substring(dash + 1).toIntOrNull() != null
    }
}

/**
 * SHA-256 helper used by the auth and blob modules. Returns lowercase hex.
 */
object Sha256 {
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }.lowercase()

    fun hexOfString(s: String): String = hex(s.toByteArray(Charsets.UTF_8))
}

/**
 * Base64 helpers used for `Content-MD5` validation. The header is the base64
 * encoding of the raw MD5 bytes (not the hex), per RFC 1864.
 */
object Md5Base64 {
    fun encode(md5: ByteArray): String =
        java.util.Base64
            .getEncoder()
            .encodeToString(md5)

    fun decode(b64: String): ByteArray =
        java.util.Base64
            .getDecoder()
            .decode(b64)
}
