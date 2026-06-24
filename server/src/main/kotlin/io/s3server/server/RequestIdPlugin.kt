package app.silofs.server

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import app.silofs.common.RequestIds
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-request ID and standard S3 response headers.
 *
 * AWS S3 attaches the following headers to every response:
 *
 *   - `x-amz-request-id` — a 16-char hex identifier (lowercase).
 *   - `x-amz-id-2` — an extended ID used by AWS support; we synthesise a
 *     longer base64-ish string that embeds the request ID + a counter so it
 *     is unique per request but traceable.
 *   - `Date` — RFC 1123, in UTC.
 *   - `Server` — `AmazonS3` to keep SDK parsers happy (some clients inspect it).
 *
 * On errors these headers are added by [StatusPages][installPlugins] so the
 * failure response carries the same request ID as the originating call.
 */
class RequestIdConfig {
    /** The server identifier returned in the `Server` header. */
    var serverHeader: String = "AmazonS3"
}

private val callRequestIdKey = io.ktor.util.AttributeKey<String>("S3RequestId")
private val callExtendedIdKey = io.ktor.util.AttributeKey<String>("S3ExtendedId")

val RequestIdPlugin: ApplicationPlugin<RequestIdConfig> = createApplicationPlugin(
    name = "RequestIdPlugin",
    createConfiguration = { RequestIdConfig() }
) {
    val config = pluginConfig
    val counter = AtomicLong(0)

    application.intercept(ApplicationCallPipeline.Setup) {
        val requestId = RequestIds.newRequestId()
        val sequence = counter.incrementAndGet()
        val extended = buildExtendedId(requestId, sequence)
        context.attributes.put(callRequestIdKey, requestId)
        context.attributes.put(callExtendedIdKey, extended)

        // Attach headers BEFORE the handler runs so they appear on success and
        // error responses alike. Headers added later by handlers (e.g. ETag)
        // are appended after these.
        context.response.headers.apply {
            append(HttpHeaders.Server, config.serverHeader)
            append(HttpHeaders.Date, app.silofs.common.S3Time.formatHttpDate(Instant.now()))
            append("x-amz-request-id", requestId)
            append("x-amz-id-2", extended)
        }
    }
}

/** Build an extended ID like `m2s3server/AAAAAAAB/0123456789abcdef`. */
private fun buildExtendedId(requestId: String, sequence: Long): String {
    val seqHex = "%08x".format(sequence)
    return "m2s3server/$seqHex/$requestId"
}

/** Read the per-call request ID, or null if the plugin didn't run. */
fun ApplicationCall.requestId(): String? =
    attributes.getOrNull(callRequestIdKey)

/** Read the per-call extended ID, or null. */
fun ApplicationCall.extendedRequestId(): String? =
    attributes.getOrNull(callExtendedIdKey)
