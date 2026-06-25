package app.silofs.server

import app.silofs.blob.BlobConsistencyReport
import app.silofs.blob.BlobReference
import app.silofs.blob.MissingBlob
import app.silofs.blob.OrphanBlob
import app.silofs.common.ETagMatcher
import app.silofs.common.S3Exception
import app.silofs.common.S3Errors
import app.silofs.common.s3Tag
import app.silofs.common.s3XmlDocument
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

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
                    inFlightRequests = 6,
                    inFlightUploads = 7,
                    inFlightMultipartCompletions = 8,
                    rejectedRequests = 9,
                    rejectedRateLimitedRequests = 19,
                    rejectedUploads = 10,
                    rejectedMultipartCompletions = 11,
                    dbPoolActiveConnections = 12,
                    dbPoolIdleConnections = 13,
                    dbPoolTotalConnections = 14,
                    dbPoolThreadsAwaitingConnection = 15,
                    recoverySweeps = 16,
                    recoverySweepFailures = 17,
                    blobStoreErrors = 18,
                ),
            )

        assertTrue(body.contains("# TYPE silofs_active_multipart_uploads gauge\n"))
        assertTrue(body.contains("silofs_active_multipart_uploads 2\n"))
        assertTrue(body.contains("silofs_orphan_temp_files 3\n"))
        assertTrue(body.contains("silofs_quarantined_blobs 4\n"))
        assertTrue(body.contains("silofs_blob_disk_bytes 5\n"))
        assertTrue(body.contains("silofs_inflight_requests 6\n"))
        assertTrue(body.contains("silofs_inflight_uploads 7\n"))
        assertTrue(body.contains("silofs_inflight_multipart_completions 8\n"))
        assertTrue(body.contains("silofs_rejected_requests_total 9\n"))
        assertTrue(body.contains("silofs_rejected_rate_limited_requests_total 19\n"))
        assertTrue(body.contains("silofs_rejected_uploads_total 10\n"))
        assertTrue(body.contains("silofs_rejected_multipart_completions_total 11\n"))
        assertTrue(body.contains("silofs_db_pool_active_connections 12\n"))
        assertTrue(body.contains("silofs_db_pool_idle_connections 13\n"))
        assertTrue(body.contains("silofs_db_pool_total_connections 14\n"))
        assertTrue(body.contains("silofs_db_pool_threads_awaiting_connection 15\n"))
        assertTrue(body.contains("silofs_recovery_sweeps_total 16\n"))
        assertTrue(body.contains("silofs_recovery_sweep_failures_total 17\n"))
        assertTrue(body.contains("silofs_blob_store_errors_total 18\n"))
    }

    @Test
    fun `operational upload limiter rejects saturated uploads and records counters`() = runBlocking {
        val state = OperationalState(testOperationalConfig(maxConcurrentUploads = 1))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first =
            async {
                state.withUploadPermit {
                    entered.complete(Unit)
                    release.await()
                }
            }
        entered.await()

        val ex =
            try {
                state.withUploadPermit { }
                null
            } catch (e: S3Exception) {
                e
            }

        assertEquals("SlowDown", ex?.errorCode?.code)
        assertEquals(1, state.inFlightUploads)
        assertEquals(1, state.rejectedUploads)
        release.complete(Unit)
        first.await()
        assertEquals(0, state.inFlightUploads)
    }

    @Test
    fun `operational completion limiter rejects saturated completions and records counters`() = runBlocking {
        val state = OperationalState(testOperationalConfig(maxConcurrentMultipartCompletions = 1))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first =
            async {
                state.withMultipartCompletionPermit {
                    entered.complete(Unit)
                    release.await()
                }
            }
        entered.await()

        val ex =
            try {
                state.withMultipartCompletionPermit { }
                null
            } catch (e: S3Exception) {
                e
            }

        assertEquals("SlowDown", ex?.errorCode?.code)
        assertEquals(1, state.inFlightMultipartCompletions)
        assertEquals(1, state.rejectedMultipartCompletions)
        release.complete(Unit)
        first.await()
        assertEquals(0, state.inFlightMultipartCompletions)
    }

    @Test
    fun `operational request tracking rejects new requests after shutdown begins`() {
        val state = OperationalState(testOperationalConfig())
        assertTrue(state.beginRequest())
        assertEquals(1, state.inFlightRequests)
        state.beginShutdown()
        assertFalse(state.beginRequest())
        assertEquals(1, state.rejectedRequests)
        state.finishRequest()
        assertTrue(state.awaitRequestDrain(timeoutMillis = 100))
    }

    @Test
    fun `access key secret codec encrypts and decrypts with aad`() {
        val key = ByteArray(32) { it.toByte() }
        val codec = AccessKeySecretCodec(key, "test")
        val encrypted = codec.encrypt("AKID", "secret")
        assertFalse("secret".toByteArray().contentEquals(encrypted.ciphertext))
        assertEquals("secret", codec.decrypt("AKID", encrypted.ciphertext, encrypted.nonce))
        org.junit.jupiter.api.assertThrows<Exception> {
            codec.decrypt("OTHER", encrypted.ciphertext, encrypted.nonce)
        }
    }

    @Test
    fun `security config validates encryption key size`() {
        SecurityConfig(
            secretEncryptionKey = ByteArray(32),
            requireEncryptedSecrets = true,
            corsAllowedOrigins = emptyList(),
            rateLimitPerAccessKeyRps = 0,
            rateLimitPerAccessKeyBurst = 64,
        )
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            SecurityConfig(
                secretEncryptionKey = ByteArray(16),
                requireEncryptedSecrets = false,
                corsAllowedOrigins = emptyList(),
                rateLimitPerAccessKeyRps = 0,
                rateLimitPerAccessKeyBurst = 64,
            )
        }
    }

    @Test
    fun `access key rate limiter rejects over burst`() {
        val limiter = AccessKeyRateLimiter(rps = 1, burst = 1)
        assertTrue(limiter.allow("AKID"))
        assertFalse(limiter.allow("AKID"))
        assertTrue(limiter.allow("OTHER"))
    }

    @Test
    fun `sanitized request resource redacts presigned secrets`() {
        val sanitized = sanitizedRequestResource(
            "/bucket/key?X-Amz-Credential=AKID%2Fscope&X-Amz-Signature=abcdef&prefix=ok&token=secret"
        )
        assertEquals(
            "/bucket/key?X-Amz-Credential=REDACTED&X-Amz-Signature=REDACTED&prefix=ok&token=REDACTED",
            sanitized,
        )
    }

    @Test
    fun `bounded complete xml reader rejects declared oversized body`() = testApplication {
        application {
            routing {
                post("/bounded") {
                    try {
                        call.respondText(call.receiveBoundedText(maxBytes = 4))
                    } catch (e: S3Exception) {
                        call.respondText(e.errorCode.code, ContentType.Text.Plain, HttpStatusCode.fromValue(e.errorCode.httpStatus))
                    }
                }
            }
        }

        val response =
            client.post("/bounded") {
                header(HttpHeaders.ContentLength, "5")
                setBody("abcde")
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `blob consistency report rendering is script friendly`() {
        val body =
            renderBlobConsistencyReport(
                BlobConsistencyReport(
                    referencedBlobCount = 2,
                    contentBlobCount = 3,
                    quarantinedBlobCount = 1,
                    missingBlobs =
                        listOf(
                            MissingBlob(
                                reference =
                                    BlobReference(
                                        kind = "object",
                                        bucket = "b",
                                        key = "k",
                                        blobPath = "objects/aa/aa",
                                        sha256Hex = "aa".repeat(32),
                                    ),
                                expectedPath = Path.of("objects/aa/aa"),
                            ),
                        ),
                    orphanBlobs =
                        listOf(
                            OrphanBlob(
                                sha256Hex = "bb".repeat(32),
                                path = Path.of("objects/bb/bb"),
                            ),
                        ),
                ),
            )

        assertTrue(body.startsWith("consistent=false\n"))
        assertTrue(body.contains("referenced_blobs=2\n"))
        assertTrue(body.contains("missing_blobs=1\n"))
        assertTrue(body.contains("missing kind=object bucket=b key=k sha256=${"aa".repeat(32)}"))
        assertTrue(body.contains("orphan_blobs=1\n"))
        assertTrue(body.contains("orphan sha256=${"bb".repeat(32)}"))
    }

    @Test
    fun `operation classifier names bucket object and multipart requests`() {
        assertEquals("ListBuckets", classifyS3Operation("GET", "/", emptySet(), hasCopySource = false))
        assertEquals("CreateBucket", classifyS3Operation("PUT", "/photos", emptySet(), hasCopySource = false))
        assertEquals("ListMultipartUploads", classifyS3Operation("GET", "/photos", setOf("uploads"), hasCopySource = false))
        assertEquals("PutObject", classifyS3Operation("PUT", "/photos/a/b.txt", emptySet(), hasCopySource = false))
        assertEquals("CopyObject", classifyS3Operation("PUT", "/photos/a/b.txt", emptySet(), hasCopySource = true))
        assertEquals(
            "UploadPartCopy",
            classifyS3Operation("PUT", "/photos/a/b.txt", setOf("uploadId", "partNumber"), hasCopySource = true),
        )
        assertEquals(
            "AbortMultipartUpload",
            classifyS3Operation("DELETE", "/photos/a/b.txt", setOf("uploadId"), hasCopySource = false),
        )
    }

    @Test
    fun `operation classifier excludes internal endpoints from request metrics`() {
        assertEquals(null, classifyS3Operation("GET", "/healthz", emptySet(), hasCopySource = false))
        assertEquals(null, classifyS3Operation("GET", "/readyz", emptySet(), hasCopySource = false))
        assertEquals(null, classifyS3Operation("GET", "/metricsz", emptySet(), hasCopySource = false))
    }

    @Test
    fun `request metrics render counters bytes and latency histogram`() {
        val registry = RequestMetricsRegistry()
        registry.observe(
            operation = "PutObject",
            status = 200,
            requestBytes = 11,
            responseBytes = 0,
            latencyNanos = 7_000_000,
        )
        registry.observe(
            operation = "PutObject",
            status = 200,
            requestBytes = 13,
            responseBytes = 2,
            latencyNanos = 20_000_000,
        )

        val body =
            renderMetrics(
                MetricsSnapshot(
                    activeMultipartUploads = 0,
                    orphanTempFiles = 0,
                    quarantinedBlobs = 0,
                    blobDiskBytes = 0,
                    requestMetrics = registry.snapshot(),
                ),
            )

        assertTrue(body.contains("silofs_http_requests_total{operation=\"PutObject\",status=\"200\"} 2\n"))
        assertTrue(body.contains("silofs_http_request_bytes_total{operation=\"PutObject\",status=\"200\"} 24\n"))
        assertTrue(body.contains("silofs_http_response_bytes_total{operation=\"PutObject\",status=\"200\"} 2\n"))
        assertTrue(body.contains("silofs_http_request_duration_seconds_bucket{operation=\"PutObject\",status=\"200\",le=\"0.005\"} 0\n"))
        assertTrue(body.contains("silofs_http_request_duration_seconds_bucket{operation=\"PutObject\",status=\"200\",le=\"0.01\"} 1\n"))
        assertTrue(body.contains("silofs_http_request_duration_seconds_bucket{operation=\"PutObject\",status=\"200\",le=\"+Inf\"} 2\n"))
        assertTrue(body.contains("silofs_http_request_duration_seconds_count{operation=\"PutObject\",status=\"200\"} 2\n"))
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

private fun testOperationalConfig(
    maxConcurrentUploads: Int = 64,
    maxConcurrentMultipartCompletions: Int = 8,
): OperationalConfig =
    OperationalConfig(
        maxConcurrentUploads = maxConcurrentUploads,
        maxConcurrentMultipartCompletions = maxConcurrentMultipartCompletions,
        completeXmlMaxBytes = 1_048_576L,
        minFreeDiskBytes = 0L,
        shutdownQuietPeriodMs = 1_000L,
        shutdownTimeoutMs = 5_000L,
    )
