package app.silofs.server

import app.silofs.auth.AuthenticatedSigV4Request
import app.silofs.auth.HmacSha256
import app.silofs.auth.Sha256
import java.io.EOFException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.min

class AwsChunkedInputStream(
    private val source: InputStream,
    private val verifier: AuthenticatedSigV4Request? = null,
) : InputStream() {
    private var remainingInChunk = 0L
    private var consumeChunkTerminator = false
    private var done = false
    private var previousSignature = verifier?.seedSignature
    private var currentChunkSignature: String? = null
    private var currentChunkDigest: MessageDigest? = null

    override fun read(): Int {
        val one = ByteArray(1)
        val read = read(one, 0, 1)
        return if (read == -1) -1 else one[0].toInt() and 0xff
    }

    override fun read(
        buffer: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (len == 0) return 0
        if (!prepareChunk()) return -1
        val toRead = min(len.toLong(), remainingInChunk).toInt()
        val read = source.read(buffer, off, toRead)
        if (read == -1) throw EOFException("unexpected EOF inside aws-chunked payload")
        currentChunkDigest?.update(buffer, off, read)
        remainingInChunk -= read.toLong()
        if (remainingInChunk == 0L) {
            consumeChunkTerminator = true
            verifyCurrentChunk()
        }
        return read
    }

    private fun prepareChunk(): Boolean {
        if (done) return false
        if (remainingInChunk > 0L) return true
        if (consumeChunkTerminator) {
            readExpectedCrlf()
            consumeChunkTerminator = false
        }

        val line = readAsciiLine()
        val sizeHex = line.substringBefore(';').trim()
        if (sizeHex.isEmpty()) throw EOFException("missing aws-chunked chunk size")
        remainingInChunk = sizeHex.toLong(16)
        currentChunkSignature = parseChunkSignature(line)
        currentChunkDigest = verifier?.let { MessageDigest.getInstance("SHA-256") }
        if (remainingInChunk == 0L) {
            verifyCurrentChunk()
            consumeTrailers()
            done = true
            return false
        }
        return true
    }

    private fun consumeTrailers() {
        while (true) {
            val line = readAsciiLine()
            if (line.isEmpty()) return
        }
    }

    private fun readExpectedCrlf() {
        val cr = source.read()
        val lf = source.read()
        if (cr != '\r'.code || lf != '\n'.code) {
            throw EOFException("missing aws-chunked chunk terminator")
        }
    }

    private fun readAsciiLine(): String {
        val out = StringBuilder()
        while (true) {
            val next = source.read()
            if (next == -1) throw EOFException("unexpected EOF in aws-chunked payload")
            if (next == '\r'.code) {
                val lf = source.read()
                if (lf != '\n'.code) throw EOFException("malformed aws-chunked line ending")
                return out.toString()
            }
            out.append(next.toChar())
        }
    }

    private fun parseChunkSignature(line: String): String? {
        if (verifier == null) return null
        val signature =
            line
                .split(';')
                .asSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("chunk-signature=") }
                ?.substringAfter('=')
                ?: throw EOFException("missing aws-chunked chunk-signature")
        if (signature.length != 64 || !signature.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            throw EOFException("invalid aws-chunked chunk-signature")
        }
        return signature.lowercase()
    }

    private fun verifyCurrentChunk() {
        val context = verifier ?: return
        val expected = currentChunkSignature ?: throw EOFException("missing aws-chunked chunk-signature")
        val previous = previousSignature ?: throw EOFException("missing previous aws-chunked signature")
        val chunkHash = Sha256.hexOf(currentChunkDigest?.digest() ?: Sha256.hashBytes(ByteArray(0)))
        val stringToSign =
            listOf(
                "AWS4-HMAC-SHA256-PAYLOAD",
                context.amzDate,
                "${context.date}/${context.region}/${context.service}/aws4_request",
                previous,
                EMPTY_SHA256,
                chunkHash,
            ).joinToString("\n")
        val computed =
            HmacSha256
                .hmac(context.signingKey, stringToSign.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        if (!constantTimeEquals(computed, expected)) {
            throw EOFException("aws-chunked chunk signature mismatch")
        }
        previousSignature = expected
        currentChunkSignature = null
        currentChunkDigest = null
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

    companion object {
        private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
