package app.silofs.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CRC32CTest {

    @Test
    fun `known test vector for 123456789`() {
        // RFC 3720 test vector: CRC32C of "123456789" = 0xE3069283
        val c = CRC32C()
        c.update("123456789".toByteArray())
        assertEquals(0xE3069283L, c.getValue())
    }

    @Test
    fun `empty input returns initial value`() {
        val c = CRC32C()
        assertEquals(0L, c.getValue())
    }

    @Test
    fun `reset restores initial state`() {
        val c = CRC32C()
        c.update("hello".toByteArray())
        c.reset()
        assertEquals(0L, c.getValue())
    }

    @Test
    fun `single byte update`() {
        val c = CRC32C()
        c.update(0x00)
        val v1 = c.getValue()
        c.reset()
        c.update(0xFF)
        val v2 = c.getValue()
        // Different inputs produce different checksums
        assert(v1 != v2)
    }

    @Test
    fun `byte array offset and length`() {
        val c1 = CRC32C()
        c1.update("hello world".toByteArray())
        val v1 = c1.getValue()

        val c2 = CRC32C()
        c2.update("XXhello worldXX".toByteArray(), 2, 11)
        val v2 = c2.getValue()

        assertEquals(v1, v2)
    }
}
