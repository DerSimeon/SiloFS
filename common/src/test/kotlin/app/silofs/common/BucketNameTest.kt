package app.silofs.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BucketNameTest {
    @Test
    fun `accepts well-formed names`() {
        BucketName.validate("my-bucket")
        BucketName.validate("my.bucket.1")
        BucketName.validate("abc")
        BucketName.validate("a".repeat(63))
        BucketName.validate("1ab")
    }

    @Test
    fun `rejects too short`() {
        val ex = assertThrows<S3Exception> { BucketName.validate("ab") }
        assertEquals(S3ErrorCode.InvalidBucketName, ex.errorCode)
        assertFalse(BucketName.isValid("ab"))
    }

    @Test
    fun `rejects too long`() {
        val ex = assertThrows<S3Exception> { BucketName.validate("a".repeat(64)) }
        assertEquals(S3ErrorCode.InvalidBucketName, ex.errorCode)
    }

    @Test
    fun `rejects uppercase`() {
        assertThrows<S3Exception> { BucketName.validate("MyBucket") }
    }

    @Test
    fun `rejects underscores`() {
        assertThrows<S3Exception> { BucketName.validate("my_bucket") }
    }

    @Test
    fun `rejects leading hyphen`() {
        assertThrows<S3Exception> { BucketName.validate("-leading") }
    }

    @Test
    fun `rejects trailing hyphen`() {
        assertThrows<S3Exception> { BucketName.validate("trailing-") }
    }

    @Test
    fun `rejects consecutive dots`() {
        assertThrows<S3Exception> { BucketName.validate("a..b") }
    }

    @Test
    fun `rejects IP address`() {
        assertThrows<S3Exception> { BucketName.validate("192.168.0.1") }
    }

    @Test
    fun `rejects reserved prefixes`() {
        assertThrows<S3Exception> { BucketName.validate("xn--prefix") }
        assertThrows<S3Exception> { BucketName.validate("sthree-reserved") }
    }

    @Test
    fun `rejects reserved suffixes`() {
        assertThrows<S3Exception> { BucketName.validate("my-bucket-s3alias") }
        assertThrows<S3Exception> { BucketName.validate("my-bucket--ol-s3") }
    }

    @Test
    fun `isValid returns false without throwing`() {
        assertFalse(BucketName.isValid(""))
        assertFalse(BucketName.isValid("UPPER"))
        assertTrue(BucketName.isValid("good-name"))
    }
}
