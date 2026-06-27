package app.silofs.common

/**
 * Thrown by any handler to short-circuit the request and produce an S3 XML error
 * response. The [Ktor StatusPages plugin][app.silofs.server.plugins.ErrorHandling]
 * maps this to the standard `<Error>...</Error>` body.
 */
class S3Exception(
    val errorCode: S3ErrorCode,
    message: String? = errorCode.defaultMessage,
    val resource: String? = null,
    val requestId: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message ?: errorCode.defaultMessage, cause)

/** Convenience constructors for the most common codes. */
object S3Errors {
    fun noSuchBucket(bucket: String): S3Exception = S3Exception(S3ErrorCode.NoSuchBucket, resource = "/$bucket")

    fun noSuchKey(
        bucket: String,
        key: String,
    ): S3Exception = S3Exception(S3ErrorCode.NoSuchKey, resource = "/$bucket/$key")

    fun invalidBucketName(bucket: String): S3Exception =
        S3Exception(S3ErrorCode.InvalidBucketName, "The specified bucket is not valid: $bucket")

    fun invalidObjectName(key: String): S3Exception =
        S3Exception(S3ErrorCode.InvalidObjectName, "The specified object key is not valid: $key")

    fun bucketAlreadyExists(bucket: String): S3Exception =
        S3Exception(S3ErrorCode.BucketAlreadyExists, "The requested bucket name is not available: $bucket")

    fun bucketNotEmpty(bucket: String): S3Exception =
        S3Exception(S3ErrorCode.BucketNotEmpty, "The bucket you tried to delete is not empty: $bucket")

    fun invalidRange(
        size: Long,
        rangeHeader: String,
    ): S3Exception =
        S3Exception(
            S3ErrorCode.InvalidRange,
            "The requested range is not satisfiable: $rangeHeader (object size: $size)",
        )

    fun signatureDoesNotMatch(reason: String): S3Exception =
        S3Exception(
            S3ErrorCode.SignatureDoesNotMatch,
            "The request signature we calculated does not match the signature you provided. $reason",
        )

    fun notImplemented(what: String): S3Exception =
        S3Exception(S3ErrorCode.NotImplemented, "A header you provided implies functionality that is not implemented: $what")

    fun invalidArgument(
        name: String,
        value: String?,
        reason: String,
    ): S3Exception = S3Exception(S3ErrorCode.InvalidArgument, "Invalid argument: $name='$value' — $reason")

    fun badDigest(
        expected: String,
        actual: String,
    ): S3Exception =
        S3Exception(
            S3ErrorCode.BadDigest,
            "The Content-MD5 you specified did not match what we received. expected=$expected actual=$actual",
        )

    fun sha256Mismatch(
        expected: String,
        actual: String,
    ): S3Exception =
        S3Exception(
            S3ErrorCode.XAmzContentSHA256Mismatch,
            "The provided 'x-amz-content-sha256' header does not match what was computed. expected=$expected actual=$actual",
        )

    fun authorizationHeaderMalformed(reason: String): S3Exception =
        S3Exception(S3ErrorCode.AuthorizationHeaderMalformed, "The authorization header you provided is invalid. $reason")

    fun invalidAccessKeyId(accessKeyId: String): S3Exception =
        S3Exception(
            S3ErrorCode.InvalidAccessKeyId,
            "The AWS Access Key Id you provided does not exist in our records. accessKeyId=$accessKeyId",
        )

    fun missingSecurityHeader(name: String): S3Exception =
        S3Exception(S3ErrorCode.MissingSecurityHeader, "Your request was missing a required header: $name")

    fun internalError(cause: Throwable): S3Exception =
        S3Exception(S3ErrorCode.InternalError, "We encountered an internal error. Please try again.", cause = cause)

    // M2 additions

    fun missingContentLength(): S3Exception =
        S3Exception(S3ErrorCode.MissingContentLength, "You must provide the Content-Length HTTP header.")

    fun entityTooLarge(
        actual: Long,
        max: Long,
    ): S3Exception =
        S3Exception(S3ErrorCode.EntityTooLarge, "Your proposed upload exceeds the maximum allowed object size. actual=$actual max=$max")

    fun maxMessageLengthExceeded(
        actual: Long,
        max: Long,
    ): S3Exception = S3Exception(S3ErrorCode.MaxMessageLengthExceeded, "Your request body was too large. actual=$actual max=$max")

    fun preconditionFailed(
        headerName: String,
        etag: String?,
    ): S3Exception =
        S3Exception(
            S3ErrorCode.PreconditionFailed,
            "At least one of the preconditions you specified did not hold. $headerName with $etag",
        )

    fun notModified(): S3Exception = S3Exception(S3ErrorCode.NotModified, "Not Modified")

    fun requestRangeNotSatisfiable(
        size: Long,
        rangeHeader: String,
    ): S3Exception =
        S3Exception(
            S3ErrorCode.RequestRangeNotSatisfiable,
            "The requested range is not satisfiable. actualContentLength=$size requestedRange=$rangeHeader",
        )

    fun malformedXML(reason: String): S3Exception =
        S3Exception(
            S3ErrorCode.MalformedXML,
            "The XML you provided was not well-formed or did not validate against our published schema. $reason",
        )

    fun invalidArgumentList(
        name: String,
        value: String?,
        reason: String,
    ): S3Exception = S3Exception(S3ErrorCode.InvalidArgument, "Invalid argument: $name='$value' — $reason")

    fun methodNotAllowed(
        method: String,
        resource: String,
    ): S3Exception =
        S3Exception(
            S3ErrorCode.MethodNotAllowed,
            "The specified method is not allowed against this resource. method=$method resource=$resource",
        )

    // M3: multipart upload errors

    fun noSuchUpload(uploadId: String): S3Exception =
        S3Exception(
            S3ErrorCode.NoSuchUpload,
            "The specified multipart upload does not exist. The upload ID might be invalid, or the upload might have been aborted or completed. uploadId=$uploadId",
        )

    fun noSuchPart(
        uploadId: String,
        partNumber: Int,
    ): S3Exception =
        S3Exception(
            S3ErrorCode.NoSuchPart,
            "The specified part does not exist. uploadId=$uploadId partNumber=$partNumber",
        )

    fun invalidPartETag(
        partNumber: Int,
        expected: String,
        actual: String,
    ): S3Exception =
        S3Exception(
            S3ErrorCode.InvalidPart,
            "The ETag for part $partNumber does not match the stored ETag. expected=$expected actual=$actual",
        )

    fun entityTooSmall(
        partNumber: Int,
        actual: Long,
        min: Long,
    ): S3Exception =
        S3Exception(
            S3ErrorCode.EntityTooSmall,
            "Your proposed upload is smaller than the minimum allowed object size. partNumber=$partNumber actual=$actual minimum=$min",
        )

    fun invalidPart(
        partNumber: Int,
        reason: String,
    ): S3Exception =
        S3Exception(
            S3ErrorCode.InvalidPart,
            "One or more of the specified parts could not be found. partNumber=$partNumber reason=$reason",
        )

    /** The streamed body did not match the declared Content-Length. */
    fun incompleteBody(
        declared: Long,
        actual: Long,
    ): S3Exception =
        S3Exception(
            S3ErrorCode.IncompleteBody,
            "You did not provide the number of bytes specified by the Content-Length HTTP header. " +
                "declared=$declared actual=$actual",
        )
}
