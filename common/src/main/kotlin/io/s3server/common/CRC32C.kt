package app.silofs.common

import java.util.zip.Checksum

/**
 * CRC32C (Castagnoli) implementation.
 *
 * Uses the CRC32C polynomial 0x1EDC6F41 in reversed (reflected) form
 * (0x82F63B78), which is the form AWS uses for `x-amz-checksum-crc32c`.
 *
 * The JDK's `java.util.zip.CRC32` implements CRC-32 (Ethernet polynomial),
 * NOT CRC32C, so we need our own implementation.
 */
class CRC32C : Checksum {

    private var crc: Long = 0xFFFFFFFFL

    override fun update(b: Int) {
        crc = crc xor (b.toLong() and 0xFF)
        repeat(8) {
            crc = if (crc and 1L != 0L) {
                (crc ushr 1) xor POLY
            } else {
                crc ushr 1
            }
        }
    }

    override fun update(b: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) {
            update(b[i].toInt())
        }
    }

    override fun getValue(): Long = crc xor 0xFFFFFFFFL

    override fun reset() {
        crc = 0xFFFFFFFFL
    }

    companion object {
        private const val POLY = 0x82F63B78L
    }
}
