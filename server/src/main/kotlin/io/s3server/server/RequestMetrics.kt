package app.silofs.server

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

internal val REQUEST_LATENCY_BUCKET_SECONDS = doubleArrayOf(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)

private val callStartNanosKey = io.ktor.util.AttributeKey<Long>("SilosRequestMetricsStartNanos")

class RequestMetricsConfig {
    lateinit var registry: RequestMetricsRegistry
}

val RequestMetricsPlugin: ApplicationPlugin<RequestMetricsConfig> = createApplicationPlugin(
    name = "RequestMetricsPlugin",
    createConfiguration = { RequestMetricsConfig() },
) {
    val registry = pluginConfig.registry

    application.intercept(ApplicationCallPipeline.Setup) {
        context.attributes.put(callStartNanosKey, System.nanoTime())
    }

    on(ResponseSent) { call ->
        val startNanos = call.attributes.getOrNull(callStartNanosKey) ?: return@on
        val operation = classifyS3Operation(
            method = call.request.httpMethod.value,
            path = call.request.path(),
            queryNames = call.request.queryParameters.names(),
            hasCopySource = call.request.headers["x-amz-copy-source"] != null,
        ) ?: return@on
        val status = call.response.status()?.value ?: 0
        registry.observe(
            operation = operation,
            status = status,
            requestBytes = call.request.headers[HttpHeaders.ContentLength].toNonNegativeLongOrZero(),
            responseBytes = call.response.headers[HttpHeaders.ContentLength].toNonNegativeLongOrZero(),
            latencyNanos = (System.nanoTime() - startNanos).coerceAtLeast(0L),
        )
    }
}

class RequestMetricsRegistry {
    private val counters = ConcurrentHashMap<RequestMetricKey, RequestMetricCounters>()

    fun observe(
        operation: String,
        status: Int,
        requestBytes: Long,
        responseBytes: Long,
        latencyNanos: Long,
    ) {
        val sample = counters.computeIfAbsent(RequestMetricKey(operation, status)) { RequestMetricCounters() }
        sample.count.increment()
        sample.requestBytes.add(requestBytes.coerceAtLeast(0L))
        sample.responseBytes.add(responseBytes.coerceAtLeast(0L))
        sample.latencyNanos.add(latencyNanos.coerceAtLeast(0L))
        val latencySeconds = latencyNanos.toDouble() / 1_000_000_000.0
        REQUEST_LATENCY_BUCKET_SECONDS.forEachIndexed { index, upperBound ->
            if (latencySeconds <= upperBound) {
                sample.latencyBuckets[index].increment()
            }
        }
        sample.latencyBuckets.last().increment()
    }

    fun snapshot(): List<RequestMetricSample> =
        counters.entries
            .map { (key, counters) ->
                RequestMetricSample(
                    operation = key.operation,
                    status = key.status,
                    count = counters.count.sum(),
                    requestBytes = counters.requestBytes.sum(),
                    responseBytes = counters.responseBytes.sum(),
                    latencyNanos = counters.latencyNanos.sum(),
                    latencyBuckets = counters.latencyBuckets.map { it.sum() },
                )
            }
            .sortedWith(compareBy<RequestMetricSample> { it.operation }.thenBy { it.status })
}

data class RequestMetricSample(
    val operation: String,
    val status: Int,
    val count: Long,
    val requestBytes: Long,
    val responseBytes: Long,
    val latencyNanos: Long,
    val latencyBuckets: List<Long>,
)

private data class RequestMetricKey(
    val operation: String,
    val status: Int,
)

private class RequestMetricCounters {
    val count = LongAdder()
    val requestBytes = LongAdder()
    val responseBytes = LongAdder()
    val latencyNanos = LongAdder()
    val latencyBuckets = List(REQUEST_LATENCY_BUCKET_SECONDS.size + 1) { LongAdder() }
}

internal fun classifyS3Operation(
    method: String,
    path: String,
    queryNames: Set<String>,
    hasCopySource: Boolean,
): String? {
    if (path in setOf("/healthz", "/readyz", "/metricsz")) return null

    val normalizedMethod = method.uppercase()
    val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
    return when {
        path == "/" && normalizedMethod == "GET" -> "ListBuckets"
        segments.size == 1 -> classifyBucketOperation(normalizedMethod, queryNames)
        segments.size >= 2 -> classifyObjectOperation(normalizedMethod, queryNames, hasCopySource)
        else -> "Unknown"
    }
}

private fun classifyBucketOperation(method: String, queryNames: Set<String>): String =
    when (method) {
        "PUT" -> "CreateBucket"
        "HEAD" -> "HeadBucket"
        "DELETE" -> "DeleteBucket"
        "GET" -> when {
            "location" in queryNames -> "GetBucketLocation"
            "uploads" in queryNames -> "ListMultipartUploads"
            else -> "ListObjectsV2"
        }
        else -> "Unknown"
    }

private fun classifyObjectOperation(
    method: String,
    queryNames: Set<String>,
    hasCopySource: Boolean,
): String =
    when (method) {
        "POST" -> when {
            "uploads" in queryNames -> "CreateMultipartUpload"
            "uploadId" in queryNames -> "CompleteMultipartUpload"
            else -> "Unknown"
        }
        "PUT" -> when {
            "uploadId" in queryNames && "partNumber" in queryNames && hasCopySource -> "UploadPartCopy"
            "uploadId" in queryNames && "partNumber" in queryNames -> "UploadPart"
            hasCopySource -> "CopyObject"
            else -> "PutObject"
        }
        "GET" -> if ("uploadId" in queryNames) "ListParts" else "GetObject"
        "HEAD" -> "HeadObject"
        "DELETE" -> if ("uploadId" in queryNames) "AbortMultipartUpload" else "DeleteObject"
        else -> "Unknown"
    }

private fun String?.toNonNegativeLongOrZero(): Long =
    this?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
