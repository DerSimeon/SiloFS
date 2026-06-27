package app.silofs.server

import app.silofs.auth.AuthenticatedSigV4RequestKey
import app.silofs.common.S3Errors
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveStream
import java.io.InputStream

private const val STREAMING_AWS4_HMAC_SHA256_PAYLOAD = "STREAMING-AWS4-HMAC-SHA256-PAYLOAD"
private const val STREAMING_UNSIGNED_PAYLOAD_TRAILER = "STREAMING-UNSIGNED-PAYLOAD-TRAILER"

fun isAwsChunkedPayload(value: String?): Boolean =
    value == STREAMING_AWS4_HMAC_SHA256_PAYLOAD || value == STREAMING_UNSIGNED_PAYLOAD_TRAILER

fun decodedContentLength(
    headers: io.ktor.http.Headers,
    encodedContentLength: Long?,
): Long? =
    if (isAwsChunkedPayload(headers["x-amz-content-sha256"])) {
        headers["x-amz-decoded-content-length"]?.toLongOrNull()
    } else {
        encodedContentLength
    }

fun payloadSha256Expectation(value: String?): String? = value?.takeIf { it != "UNSIGNED-PAYLOAD" && !isAwsChunkedPayload(it) }

suspend fun requestPayloadStream(call: ApplicationCall): InputStream {
    val input = call.receiveStream()
    return when (call.request.headers["x-amz-content-sha256"]) {
        STREAMING_AWS4_HMAC_SHA256_PAYLOAD -> {
            val verifier =
                call.attributes.getOrNull(AuthenticatedSigV4RequestKey)
                    ?: throw S3Errors.signatureDoesNotMatch("Missing SigV4 signing context for aws-chunked payload")
            AwsChunkedInputStream(source = input, verifier = verifier)
        }
        STREAMING_UNSIGNED_PAYLOAD_TRAILER -> AwsChunkedInputStream(input)
        else -> input
    }
}
