package app.silofs.server

import app.silofs.auth.AuthenticatedSigV4Request
import app.silofs.auth.HmacSha256
import app.silofs.auth.Sha256
import app.silofs.auth.SigV4Verifier
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.EOFException

class AwsChunkedInputStreamTest {
    @Test
    fun `decodes aws chunked payload with signatures`() {
        val encoded =
            (
                "5;chunk-signature=abc\r\nhello\r\n" +
                    "6;chunk-signature=def\r\n world\r\n" +
                    "0;chunk-signature=done\r\n\r\n"
            ).toByteArray()

        val decoded = AwsChunkedInputStream(ByteArrayInputStream(encoded)).readBytes()

        assertArrayEquals("hello world".toByteArray(), decoded)
    }

    @Test
    fun `consumes trailing headers after final chunk`() {
        val encoded =
            (
                "3;chunk-signature=abc\r\nhey\r\n" +
                    "0;chunk-signature=done\r\n" +
                    "x-amz-checksum-crc32:AAAAAA==\r\n" +
                    "\r\n"
            ).toByteArray()

        val stream = AwsChunkedInputStream(ByteArrayInputStream(encoded))

        assertEquals("hey", stream.readBytes().decodeToString())
    }

    @Test
    fun `rejects truncated chunk data`() {
        val encoded = "5;chunk-signature=abc\r\nhe".toByteArray()

        assertThrows<EOFException> {
            AwsChunkedInputStream(ByteArrayInputStream(encoded)).readBytes()
        }
    }

    @Test
    fun `verifies signed aws chunked payload`() {
        val signer = ChunkSigner()
        val first = signer.sign("hello".toByteArray())
        val second = signer.sign(" world".toByteArray())
        val final = signer.sign(ByteArray(0))
        val encoded =
            (
                "5;chunk-signature=$first\r\nhello\r\n" +
                    "6;chunk-signature=$second\r\n world\r\n" +
                    "0;chunk-signature=$final\r\n\r\n"
            ).toByteArray()

        val decoded =
            AwsChunkedInputStream(
                source = ByteArrayInputStream(encoded),
                verifier = signer.context,
            ).readBytes()

        assertArrayEquals("hello world".toByteArray(), decoded)
    }

    @Test
    fun `rejects tampered signed aws chunked payload`() {
        val signer = ChunkSigner()
        val first = signer.sign("hello".toByteArray())
        val final = signer.sign(ByteArray(0))
        val encoded =
            (
                "5;chunk-signature=$first\r\nhullo\r\n" +
                    "0;chunk-signature=$final\r\n\r\n"
            ).toByteArray()

        assertThrows<EOFException> {
            AwsChunkedInputStream(
                source = ByteArrayInputStream(encoded),
                verifier = signer.context,
            ).readBytes()
        }
    }

    private class ChunkSigner {
        private val signingKey =
            SigV4Verifier.signingKey(
                secretAccessKey = "secret",
                dateStamp = "20260627",
                region = "us-east-1",
                service = "s3",
            )
        private var previous = "0".repeat(64)

        val context =
            AuthenticatedSigV4Request(
                amzDate = "20260627T120000Z",
                date = "20260627",
                region = "us-east-1",
                service = "s3",
                seedSignature = previous,
                signingKey = signingKey,
            )

        fun sign(bytes: ByteArray): String {
            val stringToSign =
                listOf(
                    "AWS4-HMAC-SHA256-PAYLOAD",
                    context.amzDate,
                    "${context.date}/${context.region}/${context.service}/aws4_request",
                    previous,
                    EMPTY_SHA256,
                    Sha256.hexOf(Sha256.hashBytes(bytes)),
                ).joinToString("\n")
            previous =
                HmacSha256
                    .hmac(signingKey, stringToSign.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            return previous
        }
    }

    companion object {
        private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
