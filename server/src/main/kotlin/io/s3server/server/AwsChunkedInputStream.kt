package app.silofs.server

import java.io.EOFException
import java.io.InputStream
import kotlin.math.min

class AwsChunkedInputStream(
    private val source: InputStream
) : InputStream() {
    private var remainingInChunk = 0L
    private var consumeChunkTerminator = false
    private var done = false

    override fun read(): Int {
        val one = ByteArray(1)
        val read = read(one, 0, 1)
        return if (read == -1) -1 else one[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!prepareChunk()) return -1
        val toRead = min(len.toLong(), remainingInChunk).toInt()
        val read = source.read(buffer, off, toRead)
        if (read == -1) throw EOFException("unexpected EOF inside aws-chunked payload")
        remainingInChunk -= read.toLong()
        if (remainingInChunk == 0L) {
            consumeChunkTerminator = true
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
        if (remainingInChunk == 0L) {
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
}
