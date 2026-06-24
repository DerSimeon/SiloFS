package app.silofs.server

import app.silofs.common.ETagMatcher
import app.silofs.common.S3Errors
import app.silofs.common.s3Tag
import app.silofs.common.s3XmlDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit-style tests for the M2 server module that don't require a running
 * server. The black-box integration tests in
 * [app.silofs.test.S3ServerM2Test] exercise the full pipeline.
 */
class S3ServerRoutesTest {
    @Test
    fun `error xml body shape`() {
        val ex = S3Errors.noSuchBucket("b")
        val body =
            s3XmlDocument("Error") {
                s3Tag("Code", ex.errorCode.code)
                s3Tag("Message", ex.message)
                s3Tag("Resource", ex.resource)
                s3Tag("RequestId", "fixed")
            }
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<Error><Code>NoSuchBucket</Code><Message>The specified bucket does not exist</Message>" +
                "<Resource>/b</Resource><RequestId>fixed</RequestId></Error>",
            body,
        )
    }

    @Test
    fun `error xml for precondition failed carries correct code`() {
        val ex = S3Errors.preconditionFailed("If-Match", "\"abc\"")
        val body =
            s3XmlDocument("Error") {
                s3Tag("Code", ex.errorCode.code)
                s3Tag("Message", ex.message)
                s3Tag("RequestId", "fixed")
            }
        assertTrue(body.contains("<Code>PreconditionFailed</Code>"))
        assertTrue(body.contains("<Message>At least one of the preconditions"))
    }

    @Test
    fun `recovery config defaults`() {
        val rc =
            RecoveryConfig(
                tempMaxAgeSeconds = 3600L,
                multipartMaxAgeSeconds = 86400L,
                sweepIntervalSeconds = 60L,
                blobSweepIntervalSeconds = 600L,
                enabled = true,
            )
        assertTrue(rc.enabled)
        assertEquals(3600L, rc.tempMaxAgeSeconds)
        assertEquals(86400L, rc.multipartMaxAgeSeconds)
    }

    @Test
    fun `s3 errors m2 carry correct status codes`() {
        assertEquals(411, S3Errors.missingContentLength().errorCode.httpStatus)
        assertEquals(400, S3Errors.entityTooLarge(1, 2).errorCode.httpStatus)
        assertEquals(412, S3Errors.preconditionFailed("If-Match", "\"x\"").errorCode.httpStatus)
        assertEquals(304, S3Errors.notModified().errorCode.httpStatus)
        assertEquals(416, S3Errors.requestRangeNotSatisfiable(100, "x").errorCode.httpStatus)
        assertEquals(400, S3Errors.malformedXML("x").errorCode.httpStatus)
        assertEquals(405, S3Errors.methodNotAllowed("TRACE", "/b").errorCode.httpStatus)
    }

    @Test
    fun `etag matcher handles star`() {
        assertTrue(ETagMatcher.ifMatchSatisfied("*", "\"abc\""))
        assertFalse(ETagMatcher.ifMatchSatisfied("*", null))
        assertTrue(ETagMatcher.ifNoneMatchSatisfied("*", null))
        assertFalse(ETagMatcher.ifNoneMatchSatisfied("*", "\"abc\""))
    }

    @Test
    fun `etag matcher handles list`() {
        assertTrue(ETagMatcher.ifMatchSatisfied("\"a\", \"b\"", "\"b\""))
        assertFalse(ETagMatcher.ifMatchSatisfied("\"a\", \"b\"", "\"c\""))
        assertFalse(ETagMatcher.ifNoneMatchSatisfied("\"a\", \"b\"", "\"b\""))
        assertTrue(ETagMatcher.ifNoneMatchSatisfied("\"a\", \"b\"", "\"c\""))
    }

    @Test
    fun `etag matcher handles weak prefix`() {
        assertTrue(ETagMatcher.ifMatchSatisfied("W/\"abc\"", "\"abc\""))
        assertFalse(ETagMatcher.ifNoneMatchSatisfied("W/\"abc\"", "\"abc\""))
    }

    @Test
    fun `etag matcher handles missing header`() {
        assertTrue(ETagMatcher.ifMatchSatisfied(null, "\"abc\""))
        assertTrue(ETagMatcher.ifNoneMatchSatisfied(null, "\"abc\""))
    }

    @Test
    fun `readiness rendering reports every check`() {
        val body =
            renderReadiness(
                listOf(
                    ReadinessCheck("database", ok = true, "ok"),
                    ReadinessCheck("data_dir", ok = false, "disk full\nreadonly"),
                ),
            )

        assertTrue(body.startsWith("not ready\n"))
        assertTrue(body.contains("database=ok\n"))
        assertTrue(body.contains("data_dir=fail disk full readonly\n"))
    }

    @Test
    fun `readiness rendering is ready when every check passes`() {
        val body =
            renderReadiness(
                listOf(
                    ReadinessCheck("database", ok = true, "ok"),
                    ReadinessCheck("data_dir", ok = true, "ok"),
                ),
            )

        assertTrue(body.startsWith("ready\n"))
        assertFalse(body.contains("fail"))
    }

    @Test
    fun `metrics rendering exposes operational gauges`() {
        val body =
            renderMetrics(
                MetricsSnapshot(
                    activeMultipartUploads = 2,
                    orphanTempFiles = 3,
                    quarantinedBlobs = 4,
                    blobDiskBytes = 5,
                ),
            )

        assertTrue(body.contains("# TYPE silofs_active_multipart_uploads gauge\n"))
        assertTrue(body.contains("silofs_active_multipart_uploads 2\n"))
        assertTrue(body.contains("silofs_orphan_temp_files 3\n"))
        assertTrue(body.contains("silofs_quarantined_blobs 4\n"))
        assertTrue(body.contains("silofs_blob_disk_bytes 5\n"))
    }

    @Test
    fun `allowed storage classes include standard`() {
        assertTrue(S3Handlers.ALLOWED_STORAGE_CLASSES.contains("STANDARD"))
        assertTrue(S3Handlers.ALLOWED_STORAGE_CLASSES.contains("GLACIER"))
        assertTrue(S3Handlers.ALLOWED_STORAGE_CLASSES.contains("STANDARD_IA"))
        assertFalse(S3Handlers.ALLOWED_STORAGE_CLASSES.contains("NONEXISTENT"))
    }

    private fun assertTrue(b: Boolean) =
        org.junit.jupiter.api.Assertions
            .assertTrue(b)

    private fun assertFalse(b: Boolean) =
        org.junit.jupiter.api.Assertions
            .assertFalse(b)
}

class ServerConfigTest {
    @Test
    fun `recovery config has sensible defaults`() {
        val rc =
            RecoveryConfig(
                tempMaxAgeSeconds = 3600L,
                multipartMaxAgeSeconds = 86400L,
                sweepIntervalSeconds = 60L,
                blobSweepIntervalSeconds = 600L,
                enabled = true,
            )
        assertTrue(rc.enabled)
        assertEquals(3600L, rc.tempMaxAgeSeconds)
        assertEquals(86400L, rc.multipartMaxAgeSeconds)
    }

    private fun assertTrue(b: Boolean) =
        org.junit.jupiter.api.Assertions
            .assertTrue(b)
}
