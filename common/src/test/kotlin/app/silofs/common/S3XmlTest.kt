package app.silofs.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class S3XmlTest {
    @Test
    fun `escapes special characters`() {
        val out =
            s3XmlDocument("Root") {
                s3Tag("Key", "a<b>&\"'\n\r\t")
            }
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Root>" +
                "<Key>a&lt;b&gt;&amp;&quot;&apos;&#10;&#13;&#9;</Key>" +
                "</Root>",
            out,
        )
    }

    @Test
    fun `skips null tag value`() {
        val out =
            s3XmlDocument("Root") {
                s3Tag("Present", "yes")
                s3Tag("Absent", null)
            }
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Root><Present>yes</Present></Root>",
            out,
        )
    }

    @Test
    fun `formats long tags`() {
        val out =
            s3XmlDocument("Root") {
                s3TagLong("Size", 42L)
            }
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Root><Size>42</Size></Root>",
            out,
        )
    }

    @Test
    fun `skips null long tag value`() {
        val out =
            s3XmlDocument("Root") {
                s3TagLong("Present", 1L)
                val maybe: Long? = null
                s3TagLong("Absent", maybe)
            }
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Root><Present>1</Present></Root>",
            out,
        )
    }

    @Test
    fun `formats boolean tags`() {
        val out =
            s3XmlDocument("Root") {
                s3TagBool("Truncated", true)
                s3TagBool("NotTruncated", false)
            }
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<Root><Truncated>true</Truncated><NotTruncated>false</NotTruncated></Root>",
            out,
        )
    }

    @Test
    fun `open and close with attributes`() {
        val out =
            s3XmlDocument("List") {
                s3Open("Item", mapOf("id" to "<1>"))
                s3Tag("Name", "x")
                s3Close("Item")
            }
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><List>" +
                "<Item id=\"&lt;1&gt;\"><Name>x</Name></Item></List>",
            out,
        )
    }

    @Test
    fun `control characters become numeric entities`() {
        val out = s3XmlDocument("R") { s3Tag("K", "a\u0001b") }
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><R><K>a&#1;b</K></R>",
            out,
        )
    }

    @Test
    fun `S3Xml object escape helpers work`() {
        assertEquals("a&lt;b", S3Xml.escape("a<b"))
        assertEquals("a&lt;b", S3Xml.escapeAttr("a<b"))
    }

    @Test
    fun `S3Xml document object method delegates`() {
        val out = S3Xml.document("Root") { s3Tag("K", "v") }
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Root><K>v</K></Root>", out)
    }
}

class ETagTest {
    @Test
    fun `from md5 hex wraps in quotes`() {
        assertEquals("\"deadbeef\"", ETag.fromMd5Hex("DEADBEEF"))
    }

    @Test
    fun `from md5 bytes lowercases`() {
        val md5 = byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())
        assertEquals("\"deadbeef\"", ETag.fromMd5Bytes(md5))
    }

    @Test
    fun `to md5 hex strips quotes`() {
        assertEquals("deadbeef", ETag.toMd5Hex("\"deadbeef\""))
    }

    @Test
    fun `to md5 hex returns null for multipart etag`() {
        assertNull(ETag.toMd5Hex("\"deadbeef-3\""))
    }

    @Test
    fun `to md5 hex returns null when unquoted`() {
        assertNull(ETag.toMd5Hex("deadbeef"))
    }

    @Test
    fun `multipart etag is quoted with dash and count`() {
        // Two parts with known MD5s (16 bytes each of 0x00 and 0x01).
        val partMd5s =
            listOf(
                ByteArray(16) { 0x00 },
                ByteArray(16) { 0x01 },
            )
        val etag = ETag.fromMultipart(partMd5s)
        assertTrue(etag.startsWith("\""))
        assertTrue(etag.endsWith("-2\""))
    }

    @Test
    fun `multipart etag is deterministic for same input`() {
        val partMd5s =
            listOf(
                ByteArray(16) { 0xab.toByte() },
                ByteArray(16) { 0xcd.toByte() },
                ByteArray(16) { 0xef.toByte() },
            )
        val e1 = ETag.fromMultipart(partMd5s)
        val e2 = ETag.fromMultipart(partMd5s)
        assertEquals(e1, e2)
    }

    @Test
    fun `multipart etag differs from single-part md5 etag`() {
        val partMd5 = ByteArray(16) { 0x42 }
        val single = ETag.fromMd5Bytes(partMd5)
        val multi = ETag.fromMultipart(listOf(partMd5))
        assertNotEquals(single, multi)
        assertTrue(multi.contains("-1"))
    }

    @Test
    fun `isMultipart detects multipart etags`() {
        assertTrue(ETag.isMultipart("\"deadbeef-3\""))
        assertTrue(ETag.isMultipart("\"abc-1\""))
        assertFalse(ETag.isMultipart("\"deadbeef\""))
        assertFalse(ETag.isMultipart("deadbeef-3"))
        assertFalse(ETag.isMultipart("\"-3\""))
    }

    @Test
    fun `multipart etag rejects empty part list`() {
        org.junit.jupiter.api
            .assertThrows<IllegalArgumentException> { ETag.fromMultipart(emptyList()) }
    }

    @Test
    fun `multipart etag rejects wrong-size md5`() {
        org.junit.jupiter.api
            .assertThrows<IllegalArgumentException> { ETag.fromMultipart(listOf(ByteArray(15))) }
    }

    private fun assertNotEquals(
        a: String,
        b: String,
    ) = org.junit.jupiter.api.Assertions
        .assertNotEquals(a, b)
}

class Sha256Test {
    @Test
    fun `hex of empty byte array is empty`() {
        assertEquals("", Sha256.hex(ByteArray(0)))
    }

    @Test
    fun `hex of string is lowercase utf-8 hex`() {
        val out = Sha256.hexOfString("abc")
        assertEquals("616263", out)
        assertEquals(out, out.lowercase())
    }

    @Test
    fun `hex of bytes with high bits`() {
        val out = Sha256.hex(byteArrayOf(0x00.toByte(), 0xff.toByte(), 0x80.toByte()))
        assertEquals("00ff80", out)
    }
}

class Md5Base64Test {
    @Test
    fun `round trips`() {
        val raw = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        val b64 = Md5Base64.encode(raw)
        assertEquals(raw.toList(), Md5Base64.decode(b64).toList())
    }
}

class S3TimeTest {
    @Test
    fun `formats rfc 1123`() {
        val instant = java.time.Instant.parse("2024-06-04T12:34:56Z")
        assertEquals("Tue, 4 Jun 2024 12:34:56 GMT", S3Time.formatHttpDate(instant))
    }

    @Test
    fun `formats iso 8601 millis`() {
        val instant = java.time.Instant.parse("2024-06-04T12:34:56.789Z")
        assertEquals("2024-06-04T12:34:56.789Z", S3Time.formatIso8601(instant))
    }

    @Test
    fun `parses amz date`() {
        val parsed = S3Time.parseAmzDate("20240604T123456Z")
        assertEquals(java.time.Instant.parse("2024-06-04T12:34:56Z"), parsed)
    }
}

class S3ExceptionTest {
    @Test
    fun `error helpers set code and message`() {
        val e = S3Errors.noSuchBucket("b")
        assertEquals(S3ErrorCode.NoSuchBucket, e.errorCode)
        assertEquals("/b", e.resource)
    }

    @Test
    fun `internalError preserves cause`() {
        val cause = RuntimeException("boom")
        val e = S3Errors.internalError(cause)
        assertEquals(S3ErrorCode.InternalError, e.errorCode)
        assertEquals(cause, e.cause)
    }

    @Test
    fun `requestId is 16 hex chars`() {
        val id = RequestIds.newRequestId()
        assertEquals(16, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
    }

    private fun assertTrue(b: Boolean) =
        org.junit.jupiter.api.Assertions
            .assertTrue(b)
}
