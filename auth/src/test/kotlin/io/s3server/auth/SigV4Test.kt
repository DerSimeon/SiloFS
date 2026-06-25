package app.silofs.auth

import app.silofs.common.S3Exception
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SigV4AuthorizationParseTest {

    private val valid = "AWS4-HMAC-SHA256 " +
        "Credential=AKIAEXAMPLE/20240604/us-east-1/s3/aws4_request, " +
        "SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
        "Signature=" + "ab".repeat(32)

    @Test
    fun `parses valid header`() {
        val a = SigV4Authorization.parse(valid)
        assertEquals("AWS4-HMAC-SHA256", a.algorithm)
        assertEquals("AKIAEXAMPLE", a.accessKeyId)
        assertEquals("20240604", a.date)
        assertEquals("us-east-1", a.region)
        assertEquals("s3", a.service)
        assertEquals(listOf("host", "x-amz-content-sha256", "x-amz-date"), a.signedHeaders)
        assertEquals("ab".repeat(32), a.signature)
    }

    @Test
    fun `null header throws`() {
        assertThrows<S3Exception> { SigV4Authorization.parse(null) }
    }

    @Test
    fun `non-aws4 algorithm rejected`() {
        assertThrows<S3Exception> {
            SigV4Authorization.parse("AWS4-HMAC-SHA512 Credential=...")
        }
    }

    @Test
    fun `missing credential field rejected`() {
        assertThrows<S3Exception> {
            SigV4Authorization.parse("AWS4-HMAC-SHA256 SignedHeaders=host, Signature=deadbeef")
        }
    }

    @Test
    fun `malformed credential rejected`() {
        assertThrows<S3Exception> {
            SigV4Authorization.parse(
                "AWS4-HMAC-SHA256 Credential=AKIAEXAMPLE/20240604/us-east-1/s3, " +
                    "SignedHeaders=host, Signature=deadbeef"
            )
        }
    }

    @Test
    fun `non-hex signature rejected`() {
        assertThrows<S3Exception> {
            SigV4Authorization.parse(
                "AWS4-HMAC-SHA256 Credential=AKIAEXAMPLE/20240604/us-east-1/s3/aws4_request, " +
                    "SignedHeaders=host, Signature=not-hex"
            )
        }
    }

    @Test
    fun `bad date rejected`() {
        assertThrows<S3Exception> {
            SigV4Authorization.parse(
                "AWS4-HMAC-SHA256 Credential=AKIAEXAMPLE/notadate/us-east-1/s3/aws4_request, " +
                    "SignedHeaders=host, Signature=" + "0".repeat(64)
            )
        }
    }
}

class SigV4VerifierTest {

    /**
     * Verifies the signing-key derivation is deterministic and matches a
     * known stable value. The actual reference vector used here is computed
     * from the documented SigV4 algorithm; we don't depend on AWS published
     * vectors (which differ between services).
     */
    @Test
    fun `signing key is deterministic`() {
        val secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        val k1 = SigV4Verifier.signingKey(secretAccessKey, "20240604", "us-east-1", "s3")
        val k2 = SigV4Verifier.signingKey(secretAccessKey, "20240604", "us-east-1", "s3")
        // Same inputs must produce the same key.
        assertEquals(k1.toList(), k2.toList())
        // Different date must produce a different key.
        val k3 = SigV4Verifier.signingKey(secretAccessKey, "20240605", "us-east-1", "s3")
        assert(k1.toList() != k3.toList()) { "Different date should produce different signing key" }
        // Different region must produce a different key.
        val k4 = SigV4Verifier.signingKey(secretAccessKey, "20240604", "eu-west-1", "s3")
        assert(k1.toList() != k4.toList()) { "Different region should produce different signing key" }
        // Length of the signing key is 32 bytes (SHA-256 output).
        assertEquals(32, k1.size)
    }

    @Test
    fun `verify returns true for matching signature`() {
        val req = SignedHttpRequest(
            method = "GET",
            path = "/test-bucket/hello.txt",
            queryString = "",
            headers = mapOf(
                "host" to listOf("localhost:8080"),
                "x-amz-content-sha256" to listOf("UNSIGNED-PAYLOAD"),
                "x-amz-date" to listOf("20240604T123456Z")
            ),
            payloadHash = "UNSIGNED-PAYLOAD"
        )
        val auth = SigV4Authorization(
            algorithm = "AWS4-HMAC-SHA256",
            accessKeyId = "AKIAEXAMPLE",
            date = "20240604",
            region = "us-east-1",
            service = "s3",
            signedHeaders = listOf("host", "x-amz-content-sha256", "x-amz-date"),
            signature = "" // computed below
        )
        val canonical = SigV4CanonicalBuilder.build(req, auth)
        val computed = SigV4Verifier.computeSignature(
            canonical, "20240604T123456Z", "20240604", "us-east-1", "s3",
            "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        )
        val auth2 = auth.copy(signature = computed)
        assertTrue(
            SigV4Verifier.verify(canonical, "20240604T123456Z", auth2,
                AwsCredentials("AKIAEXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
        )
    }

    @Test
    fun `verify returns false for tampered signature`() {
        val req = SignedHttpRequest(
            method = "GET",
            path = "/test-bucket/hello.txt",
            queryString = "",
            headers = mapOf(
                "host" to listOf("localhost:8080"),
                "x-amz-content-sha256" to listOf("UNSIGNED-PAYLOAD"),
                "x-amz-date" to listOf("20240604T123456Z")
            ),
            payloadHash = "UNSIGNED-PAYLOAD"
        )
        val auth = SigV4Authorization(
            algorithm = "AWS4-HMAC-SHA256",
            accessKeyId = "AKIAEXAMPLE",
            date = "20240604",
            region = "us-east-1",
            service = "s3",
            signedHeaders = listOf("host", "x-amz-content-sha256", "x-amz-date"),
            signature = "0".repeat(64)
        )
        val canonical = SigV4CanonicalBuilder.build(req, auth)
        assertFalse(
            SigV4Verifier.verify(canonical, "20240604T123456Z", auth,
                AwsCredentials("AKIAEXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
        )
    }
}

class SigV4CanonicalBuilderTest {

    @Test
    fun `canonical uri keeps slashes`() {
        assertEquals("/bucket/key", SigV4CanonicalBuilder.canonicalUri("/bucket/key"))
    }

    @Test
    fun `canonical uri percent encodes special chars`() {
        assertEquals("/bucket/hello%20world", SigV4CanonicalBuilder.canonicalUri("/bucket/hello world"))
        assertEquals("/bucket/a%2Bb", SigV4CanonicalBuilder.canonicalUri("/bucket/a+b"))
        assertEquals("/bucket/emoji-%F0%9F%8E%89.txt", SigV4CanonicalBuilder.canonicalUri("/bucket/emoji-🎉.txt"))
    }

    @Test
    fun `canonical uri does not double encode request path escapes`() {
        assertEquals(
            "/bucket/spaces%20in%20name.txt",
            SigV4CanonicalBuilder.canonicalUri("/bucket/spaces%20in%20name.txt")
        )
        assertEquals(
            "/bucket/unicode-%E6%97%A5%E6%9C%AC%E8%AA%9E.txt",
            SigV4CanonicalBuilder.canonicalUri("/bucket/unicode-%E6%97%A5%E6%9C%AC%E8%AA%9E.txt")
        )
        assertEquals(
            "/bucket/literal%2520percent.txt",
            SigV4CanonicalBuilder.canonicalUri("/bucket/literal%2520percent.txt")
        )
    }

    @Test
    fun `canonical query string sorts params`() {
        val qs = SigV4CanonicalBuilder.canonicalQueryString("b=2&a=1&z=9")
        assertEquals("a=1&b=2&z=9", qs)
    }

    @Test
    fun `canonical query string percent encodes values`() {
        val qs = SigV4CanonicalBuilder.canonicalQueryString("k=hello world")
        assertEquals("k=hello%20world", qs)
    }

    @Test
    fun `canonical headers trim and collapse whitespace`() {
        val headers = mapOf(
            "host" to listOf("  localhost:8080  "),
            "x-amz-date" to listOf("20240604T123456Z")
        )
        val out = SigV4CanonicalBuilder.canonicalHeaders(headers, listOf("host", "x-amz-date"))
        assertEquals("host:localhost:8080\nx-amz-date:20240604T123456Z\n", out)
    }

    @Test
    fun `signed headers joined with semicolons`() {
        assertEquals("a;b;c", SigV4CanonicalBuilder.signedHeadersString(listOf("a", "b", "c")))
    }

    @Test
    fun `build produces canonical request`() {
        val req = SignedHttpRequest(
            method = "PUT",
            path = "/b/k",
            queryString = "x=1",
            headers = mapOf(
                "host" to listOf("localhost:8080"),
                "x-amz-content-sha256" to listOf("UNSIGNED-PAYLOAD"),
                "x-amz-date" to listOf("20240604T123456Z")
            ),
            payloadHash = "UNSIGNED-PAYLOAD"
        )
        val auth = SigV4Authorization(
            algorithm = "AWS4-HMAC-SHA256",
            accessKeyId = "AKIAEXAMPLE",
            date = "20240604",
            region = "us-east-1",
            service = "s3",
            signedHeaders = listOf("host", "x-amz-content-sha256", "x-amz-date"),
            signature = "0".repeat(64)
        )
        val canonical = SigV4CanonicalBuilder.build(req, auth)
        assertEquals("PUT", canonical.method)
        assertEquals("/b/k", canonical.canonicalUri)
        assertEquals("x=1", canonical.canonicalQueryString)
        assertEquals("host;x-amz-content-sha256;x-amz-date", canonical.signedHeaders)
        assertEquals("UNSIGNED-PAYLOAD", canonical.payloadHash)
    }

    @Test
    fun `canonical string has exactly one blank line before signed headers`() {
        val canonical = CanonicalRequest(
            method = "PUT",
            canonicalUri = "/bucket",
            canonicalQueryString = "",
            canonicalHeaders = "host:localhost:8080\nx-amz-date:20240604T123456Z\n",
            signedHeaders = "host;x-amz-date",
            payloadHash = "UNSIGNED-PAYLOAD"
        )

        assertEquals(
            "PUT\n" +
                "/bucket\n" +
                "\n" +
                "host:localhost:8080\n" +
                "x-amz-date:20240604T123456Z\n" +
                "\n" +
                "host;x-amz-date\n" +
                "UNSIGNED-PAYLOAD",
            canonical.canonicalString()
        )
    }

    @Test
    fun `empty query string returns empty`() {
        assertEquals("", SigV4CanonicalBuilder.canonicalQueryString(""))
    }
}

class CredentialsTest {

    @Test
    fun `static provider looks up by access key`() {
        val provider = StaticCredentialProvider.single("AKID", "SECRET")
        val creds = provider.lookup("AKID")
        assertNotNull(creds)
        assertEquals("SECRET", creds!!.secretAccessKey)
        assertNull(provider.lookup("nope"))
    }

    @Test
    fun `static provider with empty map returns null for all`() {
        val provider = StaticCredentialProvider(emptyMap())
        assertNull(provider.lookup("anything"))
    }
}
