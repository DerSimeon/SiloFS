package app.silofs.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ByteRangeTest {
    @Test
    fun `null header returns null`() {
        assertNull(ByteRange.parse(null, 100))
    }

    @Test
    fun `explicit range`() {
        val r = ByteRange.parse("bytes=0-499", 1000)
        assertEquals(0L, r!!.start)
        assertEquals(499L, r.endInclusive)
        assertEquals(500L, r.length)
    }

    @Test
    fun `open ended range`() {
        val r = ByteRange.parse("bytes=500-", 1000)!!
        assertEquals(500L, r.start)
        assertEquals(999L, r.endInclusive)
        assertEquals(500L, r.length)
    }

    @Test
    fun `suffix range`() {
        val r = ByteRange.parse("bytes=-500", 1000)!!
        assertEquals(500L, r.start)
        assertEquals(999L, r.endInclusive)
        assertEquals(500L, r.length)
    }

    @Test
    fun `suffix range larger than object`() {
        val r = ByteRange.parse("bytes=-2000", 1000)!!
        assertEquals(0L, r.start)
        assertEquals(999L, r.endInclusive)
    }

    @Test
    fun `end beyond size is clamped`() {
        val r = ByteRange.parse("bytes=900-2000", 1000)!!
        assertEquals(900L, r.start)
        assertEquals(999L, r.endInclusive)
    }

    @Test
    fun `rejects non-bytes unit`() {
        assertThrows<S3Exception> {
            ByteRange.parse("foobar=0-1", 10)
        }
    }

    @Test
    fun `rejects multi-range`() {
        assertThrows<S3Exception> {
            ByteRange.parse("bytes=0-1,3-4", 10)
        }
    }

    @Test
    fun `rejects malformed spec`() {
        assertThrows<S3Exception> { ByteRange.parse("bytes=abc", 10) }
        assertThrows<S3Exception> { ByteRange.parse("bytes=-", 10) }
        assertThrows<S3Exception> { ByteRange.parse("bytes=5-3", 10) }
    }

    @Test
    fun `rejects start beyond size`() {
        assertThrows<S3Exception> { ByteRange.parse("bytes=1000-", 1000) }
        assertThrows<S3Exception> { ByteRange.parse("bytes=1000-1001", 1000) }
    }

    @Test
    fun `rejects suffix of zero`() {
        assertThrows<S3Exception> { ByteRange.parse("bytes=-0", 10) }
    }
}
