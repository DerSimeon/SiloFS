package app.silofs.common

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ETagMatcherTest {

    @Test
    fun `if-match missing header always satisfied`() {
        assertTrue(ETagMatcher.ifMatchSatisfied(null, "\"abc\""))
        assertTrue(ETagMatcher.ifMatchSatisfied(null, null))
    }

    @Test
    fun `if-match star satisfied when object exists`() {
        assertTrue(ETagMatcher.ifMatchSatisfied("*", "\"abc\""))
    }

    @Test
    fun `if-match star not satisfied when object missing`() {
        assertFalse(ETagMatcher.ifMatchSatisfied("*", null))
    }

    @Test
    fun `if-match exact etag satisfied`() {
        assertTrue(ETagMatcher.ifMatchSatisfied("\"abc\"", "\"abc\""))
    }

    @Test
    fun `if-match etag list satisfied when any matches`() {
        assertTrue(ETagMatcher.ifMatchSatisfied("\"a\", \"b\", \"c\"", "\"b\""))
    }

    @Test
    fun `if-match etag list not satisfied when none match`() {
        assertFalse(ETagMatcher.ifMatchSatisfied("\"a\", \"b\"", "\"c\""))
    }

    @Test
    fun `if-match weak prefix accepted`() {
        assertTrue(ETagMatcher.ifMatchSatisfied("W/\"abc\"", "\"abc\""))
    }

    @Test
    fun `if-none-match missing header always satisfied`() {
        assertTrue(ETagMatcher.ifNoneMatchSatisfied(null, "\"abc\""))
        assertTrue(ETagMatcher.ifNoneMatchSatisfied(null, null))
    }

    @Test
    fun `if-none-match star satisfied when object missing`() {
        assertTrue(ETagMatcher.ifNoneMatchSatisfied("*", null))
    }

    @Test
    fun `if-none-match star not satisfied when object exists`() {
        assertFalse(ETagMatcher.ifNoneMatchSatisfied("*", "\"abc\""))
    }

    @Test
    fun `if-none-match exact etag not satisfied when matches`() {
        assertFalse(ETagMatcher.ifNoneMatchSatisfied("\"abc\"", "\"abc\""))
    }

    @Test
    fun `if-none-match etag list not satisfied when any matches`() {
        assertFalse(ETagMatcher.ifNoneMatchSatisfied("\"a\", \"b\"", "\"b\""))
    }

    @Test
    fun `if-none-match etag list satisfied when none match`() {
        assertTrue(ETagMatcher.ifNoneMatchSatisfied("\"a\", \"b\"", "\"c\""))
    }
}

class S3ErrorsM2Test {
    @Test
    fun `missingContentLength has correct code`() {
        val e = S3Errors.missingContentLength()
        assertTrue(e.errorCode == S3ErrorCode.MissingContentLength)
        assertTrue(e.errorCode.httpStatus == 411)
    }

    @Test
    fun `entityTooLarge has correct code`() {
        val e = S3Errors.entityTooLarge(1000L, 500L)
        assertTrue(e.errorCode == S3ErrorCode.EntityTooLarge)
        assertTrue(e.errorCode.httpStatus == 400)
    }

    @Test
    fun `preconditionFailed has correct code`() {
        val e = S3Errors.preconditionFailed("If-Match", "\"abc\"")
        assertTrue(e.errorCode == S3ErrorCode.PreconditionFailed)
        assertTrue(e.errorCode.httpStatus == 412)
    }

    @Test
    fun `notModified has correct code`() {
        val e = S3Errors.notModified()
        assertTrue(e.errorCode == S3ErrorCode.NotModified)
        assertTrue(e.errorCode.httpStatus == 304)
    }

    @Test
    fun `requestRangeNotSatisfiable has correct code`() {
        val e = S3Errors.requestRangeNotSatisfiable(100L, "bytes=200-300")
        assertTrue(e.errorCode == S3ErrorCode.RequestRangeNotSatisfiable)
        assertTrue(e.errorCode.httpStatus == 416)
    }

    @Test
    fun `malformedXML has correct code`() {
        val e = S3Errors.malformedXML("missing root")
        assertTrue(e.errorCode == S3ErrorCode.MalformedXML)
        assertTrue(e.errorCode.httpStatus == 400)
    }

    @Test
    fun `methodNotAllowed has correct code`() {
        val e = S3Errors.methodNotAllowed("TRACE", "/bucket")
        assertTrue(e.errorCode == S3ErrorCode.MethodNotAllowed)
        assertTrue(e.errorCode.httpStatus == 405)
    }

    private fun assertTrue(b: Boolean) = org.junit.jupiter.api.Assertions.assertTrue(b)
}
