package app.silofs.server

import app.silofs.common.S3ErrorCode
import app.silofs.common.S3Exception
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder

data class OperationalConfig(
    val maxConcurrentUploads: Int,
    val maxConcurrentMultipartCompletions: Int,
    val completeXmlMaxBytes: Long,
    val minFreeDiskBytes: Long,
    val shutdownQuietPeriodMs: Long,
    val shutdownTimeoutMs: Long,
) {
    init {
        require(maxConcurrentUploads > 0) { "maxConcurrentUploads must be > 0" }
        require(maxConcurrentMultipartCompletions > 0) { "maxConcurrentMultipartCompletions must be > 0" }
        require(completeXmlMaxBytes > 0) { "completeXmlMaxBytes must be > 0" }
        require(minFreeDiskBytes >= 0) { "minFreeDiskBytes must be >= 0" }
        require(shutdownQuietPeriodMs >= 0) { "shutdownQuietPeriodMs must be >= 0" }
        require(shutdownTimeoutMs >= 0) { "shutdownTimeoutMs must be >= 0" }
    }

    companion object {
        fun fromEnv(): OperationalConfig =
            OperationalConfig(
                maxConcurrentUploads = envInt("S3_MAX_CONCURRENT_UPLOADS", 64),
                maxConcurrentMultipartCompletions = envInt("S3_MAX_CONCURRENT_MULTIPART_COMPLETIONS", 8),
                completeXmlMaxBytes = envLong("S3_COMPLETE_XML_MAX_BYTES", 1_048_576L),
                minFreeDiskBytes = envLong("S3_MIN_FREE_DISK_BYTES", 0L),
                shutdownQuietPeriodMs = envLong("S3_SHUTDOWN_QUIET_PERIOD_MS", 1_000L),
                shutdownTimeoutMs = envLong("S3_SHUTDOWN_TIMEOUT_MS", 5_000L),
            )

        private fun envInt(
            name: String,
            default: Int,
        ): Int = System.getenv(name)?.toIntOrNull() ?: default

        private fun envLong(
            name: String,
            default: Long,
        ): Long = System.getenv(name)?.toLongOrNull() ?: default
    }
}

class OperationalState(
    private val config: OperationalConfig,
) {
    private val acceptingRequests = AtomicBoolean(true)
    private val uploadPermits = Semaphore(config.maxConcurrentUploads)
    private val multipartCompletionPermits = Semaphore(config.maxConcurrentMultipartCompletions)

    private val inFlightRequestsCounter = AtomicInteger(0)
    private val inFlightUploadsCounter = AtomicInteger(0)
    private val inFlightMultipartCompletionsCounter = AtomicInteger(0)
    private val rejectedUploadsCounter = LongAdder()
    private val rejectedMultipartCompletionsCounter = LongAdder()
    private val rejectedRequestsCounter = LongAdder()
    private val rejectedRateLimitedRequestsCounter = LongAdder()
    private val blobStoreErrorsCounter = LongAdder()
    private val recoverySweepsCounter = LongAdder()
    private val recoverySweepFailuresCounter = LongAdder()

    val inFlightRequests: Int get() = inFlightRequestsCounter.get()
    val inFlightUploads: Int get() = inFlightUploadsCounter.get()
    val inFlightMultipartCompletions: Int get() = inFlightMultipartCompletionsCounter.get()
    val rejectedUploads: Long get() = rejectedUploadsCounter.sum()
    val rejectedMultipartCompletions: Long get() = rejectedMultipartCompletionsCounter.sum()
    val rejectedRequests: Long get() = rejectedRequestsCounter.sum()
    val rejectedRateLimitedRequests: Long get() = rejectedRateLimitedRequestsCounter.sum()
    val blobStoreErrors: Long get() = blobStoreErrorsCounter.sum()
    val recoverySweeps: Long get() = recoverySweepsCounter.sum()
    val recoverySweepFailures: Long get() = recoverySweepFailuresCounter.sum()

    fun beginRequest(): Boolean {
        if (!acceptingRequests.get()) {
            rejectedRequestsCounter.increment()
            return false
        }
        inFlightRequestsCounter.incrementAndGet()
        return true
    }

    fun finishRequest() {
        inFlightRequestsCounter.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    fun beginShutdown() {
        acceptingRequests.set(false)
    }

    fun awaitRequestDrain(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(0L) * 1_000_000L
        while (inFlightRequests > 0 && System.nanoTime() < deadline) {
            Thread.sleep(25L)
        }
        return inFlightRequests == 0
    }

    suspend fun <T> withUploadPermit(block: suspend () -> T): T {
        if (!uploadPermits.tryAcquire()) {
            rejectedUploadsCounter.increment()
            throw S3Exception(S3ErrorCode.SlowDown, "Upload concurrency limit exceeded")
        }
        inFlightUploadsCounter.incrementAndGet()
        try {
            return block()
        } finally {
            inFlightUploadsCounter.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
            uploadPermits.release()
        }
    }

    suspend fun <T> withMultipartCompletionPermit(block: suspend () -> T): T {
        if (!multipartCompletionPermits.tryAcquire()) {
            rejectedMultipartCompletionsCounter.increment()
            throw S3Exception(S3ErrorCode.SlowDown, "Multipart completion concurrency limit exceeded")
        }
        inFlightMultipartCompletionsCounter.incrementAndGet()
        try {
            return block()
        } finally {
            inFlightMultipartCompletionsCounter.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
            multipartCompletionPermits.release()
        }
    }

    fun recordBlobStoreError() {
        blobStoreErrorsCounter.increment()
    }

    fun recordRecoverySweep(success: Boolean) {
        recoverySweepsCounter.increment()
        if (!success) recoverySweepFailuresCounter.increment()
    }

    fun recordRateLimitRejection() {
        rejectedRateLimitedRequestsCounter.increment()
    }
}
