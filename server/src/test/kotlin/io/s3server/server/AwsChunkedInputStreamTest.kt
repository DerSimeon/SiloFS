package app.silofs.server

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.EOFException

class AwsChunkedInputStreamTest {
    @Test
    fun `decodes aws chunked payload with signatures`() {
        val encoded = (
            "5;chunk-signature=abc\r\nhello\r\n" +
                "6;chunk-signature=def\r\n world\r\n" +
                "0;chunk-signature=done\r\n\r\n"
        ).toByteArray()

        val decoded = AwsChunkedInputStream(ByteArrayInputStream(encoded)).readBytes()

        assertArrayEquals("hello world".toByteArray(), decoded)
    }

    @Test
    fun `consumes trailing headers after final chunk`() {
        val encoded = (
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
}
