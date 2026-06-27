package app.silofs.auth

import app.silofs.common.S3Errors

/**
 * Parsed form of an `Authorization: AWS4-HMAC-SHA256 Credential=.../SignedHeaders=.../Signature=...`
 * header.
 */
data class SigV4Authorization(
    val algorithm: String,
    val accessKeyId: String,
    val date: String, // yyyyMMdd
    val region: String,
    val service: String,
    val signedHeaders: List<String>,
    val signature: String,
) {
    companion object {
        fun parse(header: String?): SigV4Authorization {
            if (header.isNullOrBlank()) {
                throw S3Errors.missingSecurityHeader("Authorization")
            }
            val parts = header.trim().split(" ", limit = 2)
            if (parts.size != 2) {
                throw S3Errors.authorizationHeaderMalformed("Expected '<algo> <creds>'")
            }
            val algorithm = parts[0]
            if (algorithm != "AWS4-HMAC-SHA256") {
                throw S3Errors.authorizationHeaderMalformed(
                    "Unsupported algorithm '$algorithm' (only AWS4-HMAC-SHA256 is implemented)",
                )
            }
            val fields = mutableMapOf<String, String>()
            parts[1].split(",").forEach { kv ->
                val eq = kv.indexOf('=')
                if (eq < 0) {
                    throw S3Errors.authorizationHeaderMalformed("Malformed field '$kv'")
                }
                val k = kv.substring(0, eq).trim()
                val v = kv.substring(eq + 1).trim()
                fields[k] = v
            }
            val credential =
                fields["Credential"]
                    ?: throw S3Errors.authorizationHeaderMalformed("Missing Credential field")
            val signedHeadersRaw =
                fields["SignedHeaders"]
                    ?: throw S3Errors.authorizationHeaderMalformed("Missing SignedHeaders field")
            val signature =
                fields["Signature"]
                    ?: throw S3Errors.authorizationHeaderMalformed("Missing Signature field")

            val credParts = credential.split("/")
            if (credParts.size != 5) {
                throw S3Errors.authorizationHeaderMalformed(
                    "Credential must be <akid>/<date>/<region>/<service>/<request-type>, got: $credential",
                )
            }
            val (akid, date, region, service, _) = credParts
            if (date.length != 8 || !date.all { it.isDigit() }) {
                throw S3Errors.authorizationHeaderMalformed("Invalid date in credential: $date")
            }
            if (signature.length != 64 || !signature.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                throw S3Errors.authorizationHeaderMalformed("Invalid signature: $signature")
            }
            return SigV4Authorization(
                algorithm = algorithm,
                accessKeyId = akid,
                date = date,
                region = region,
                service = service,
                signedHeaders = signedHeadersRaw.split(";").map { it.trim().lowercase() },
                signature = signature.lowercase(),
            )
        }
    }
}

/**
 * The canonical request as defined by SigV4. We build this from the parsed
 * HTTP request and the SHA-256 of the body (or the literal `UNSIGNED-PAYLOAD`).
 */
data class CanonicalRequest(
    val method: String,
    val canonicalUri: String,
    val canonicalQueryString: String,
    val canonicalHeaders: String,
    val signedHeaders: String,
    val payloadHash: String,
) {
    fun canonicalString(): String =
        listOf(method, canonicalUri, canonicalQueryString, canonicalHeaders, signedHeaders, payloadHash)
            .joinToString("\n")

    fun stringToSign(
        amzDate: String,
        dateStamp: String,
        region: String,
        service: String,
    ): String {
        val scope = "$dateStamp/$region/$service/aws4_request"
        val crHash = Sha256.canonicalHash(canonicalString())
        return "AWS4-HMAC-SHA256\n$amzDate\n$scope\n$crHash"
    }
}

/**
 * Pure SigV4 verifier — no HTTP framework dependency. Takes a fully-formed
 * [CanonicalRequest] and a credential pair, returns true iff the computed
 * signature matches the one supplied by the client.
 */
object SigV4Verifier {
    fun signingKey(
        secretAccessKey: String,
        dateStamp: String,
        region: String,
        service: String,
    ): ByteArray {
        val kSecret = ("AWS4" + secretAccessKey).toByteArray(Charsets.UTF_8)
        val kDate = HmacSha256.hmac(kSecret, dateStamp.toByteArray(Charsets.UTF_8))
        val kRegion = HmacSha256.hmac(kDate, region.toByteArray(Charsets.UTF_8))
        val kService = HmacSha256.hmac(kRegion, service.toByteArray(Charsets.UTF_8))
        return HmacSha256.hmac(kService, "aws4_request".toByteArray(Charsets.UTF_8))
    }

    fun computeSignature(
        canonicalRequest: CanonicalRequest,
        amzDate: String,
        dateStamp: String,
        region: String,
        service: String,
        secretAccessKey: String,
    ): String {
        val stringToSign = canonicalRequest.stringToSign(amzDate, dateStamp, region, service)
        val key = signingKey(secretAccessKey, dateStamp, region, service)
        val sig = HmacSha256.hmac(key, stringToSign.toByteArray(Charsets.UTF_8))
        return sig.joinToString("") { "%02x".format(it) }
    }

    fun verify(
        canonicalRequest: CanonicalRequest,
        amzDate: String,
        auth: SigV4Authorization,
        credentials: AwsCredentials,
    ): Boolean {
        val computed =
            computeSignature(
                canonicalRequest = canonicalRequest,
                amzDate = amzDate,
                dateStamp = auth.date,
                region = auth.region,
                service = auth.service,
                secretAccessKey = credentials.secretAccessKey,
            )
        return constantTimeEquals(computed, auth.signature)
    }

    private fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }
}

object HmacSha256 {
    fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}

object Sha256 {
    fun canonicalHash(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val out = md.digest(s.toByteArray(Charsets.UTF_8))
        return out.joinToString("") { "%02x".format(it) }
    }

    fun hashBytes(b: ByteArray): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(b)
    }

    fun hexOf(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
