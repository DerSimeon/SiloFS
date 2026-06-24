package app.silofs.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ObjectKeyTest {

    @Test
    fun `accepts simple keys`() {
        ObjectKey.validate("a")
        ObjectKey.validate("hello.txt")
        ObjectKey.validate("path/to/file.bin")
        ObjectKey.validate("日本語.key")
        ObjectKey.validate("a".repeat(1024))
    }

    @Test
    fun `rejects empty key`() {
        assertThrows<S3Exception> { ObjectKey.validate("") }
    }

    @Test
    fun `rejects key with null byte`() {
        assertThrows<S3Exception> { ObjectKey.validate("a\u0000b") }
    }

    @Test
    fun `rejects key with newline`() {
        assertThrows<S3Exception> { ObjectKey.validate("a\nb") }
    }

    @Test
    fun `rejects key too long`() {
        assertThrows<S3Exception> { ObjectKey.validate("a".repeat(1025)) }
    }

    @Test
    fun `rejects path traversal`() {
        assertThrows<S3Exception> { ObjectKey.validate("../etc/passwd") }
        assertThrows<S3Exception> { ObjectKey.validate("dir/..\\windows") }
    }

    @Test
    fun `rejects trailing dot`() {
        assertThrows<S3Exception> { ObjectKey.validate("file.") }
    }

    @Test
    fun `rejects trailing slash`() {
        assertThrows<S3Exception> { ObjectKey.validate("dir/") }
    }

    @Test
    fun `isValid wraps exception`() {
        assertFalse(ObjectKey.isValid(""))
        assertTrue(ObjectKey.isValid("good"))
    }

    @Test
    fun `fromPathSegment percent decodes`() {
        assertEquals("hello world", ObjectKey.fromPathSegment("hello%20world"))
        assertEquals("日本語", ObjectKey.fromPathSegment("%E6%97%A5%E6%9C%AC%E8%AA%9E"))
    }
}
