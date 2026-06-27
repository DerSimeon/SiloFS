package app.silofs.server

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveStream
import java.io.InputStream

private const val StreamingAws4HmacSha256Payload = "STREAMING-AWS4-HMAC-SHA256-PAYLOAD"
private const val StreamingUnsignedPayloadTrailer = "STREAMING-UNSIGNED-PAYLOAD-TRAILER"

fun isAwsChunkedPayload(value: String?): Boolean =
    value == StreamingAws4HmacSha256Payload || value == StreamingUnsignedPayloadTrailer

fun decodedContentLength(headers: io.ktor.http.Headers, encodedContentLength: Long?): Long? =
    if (isAwsChunkedPayload(headers["x-amz-content-sha256"])) {
        headers["x-amz-decoded-content-length"]?.toLongOrNull()
    } else {
        encodedContentLength
    }

fun payloadSha256Expectation(value: String?): String? =
    value?.takeIf { it != "UNSIGNED-PAYLOAD" && !isAwsChunkedPayload(it) }

suspend fun requestPayloadStream(call: ApplicationCall): InputStream {
    val input = call.receiveStream()
    return if (isAwsChunkedPayload(call.request.headers["x-amz-content-sha256"])) {
        AwsChunkedInputStream(input)
    } else {
        input
    }
}
