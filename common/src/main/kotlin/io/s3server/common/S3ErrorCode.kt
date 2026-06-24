package app.silofs.common

/**
 * S3 error codes mapped to HTTP status.
 *
 * See https://docs.aws.amazon.com/AmazonS3/latest/API/ErrorResponses.html
 */
enum class S3ErrorCode(
    val httpStatus: Int,
    val code: String,
    val defaultMessage: String
) {
    NoSuchBucket(404, "NoSuchBucket", "The specified bucket does not exist"),
    NoSuchKey(404, "NoSuchKey", "The specified key does not exist"),
    BucketAlreadyExists(409, "BucketAlreadyExists", "The requested bucket name is not available"),
    BucketAlreadyOwnedByYou(409, "BucketAlreadyOwnedByYou", "Your previous request to create the named bucket succeeded and you already own it"),
    BucketNotEmpty(409, "BucketNotEmpty", "The bucket you tried to delete is not empty"),
    InvalidBucketName(400, "InvalidBucketName", "The specified bucket is not valid"),
    InvalidObjectName(400, "InvalidObjectName", "The specified object key is not valid"),
    InvalidRange(416, "InvalidRange", "The requested range is not satisfiable"),
    InvalidPartOrder(400, "InvalidPartOrder", "The list of parts was not in ascending order"),
    InvalidArgument(400, "InvalidArgument", "Invalid Argument"),
    SignatureDoesNotMatch(403, "SignatureDoesNotMatch", "The request signature we calculated does not match the signature you provided"),
    AccessDenied(403, "AccessDenied", "Access Denied"),
    AuthorizationHeaderMalformed(400, "AuthorizationHeaderMalformed", "The authorization header you provided is invalid"),
    InvalidAccessKeyId(403, "InvalidAccessKeyId", "The AWS Access Key Id you provided does not exist in our records"),
    MissingContentLength(411, "MissingContentLength", "You must provide the Content-Length HTTP header"),
    MissingSecurityHeader(400, "MissingSecurityHeader", "Your request was missing a required header"),
    BadDigest(400, "BadDigest", "The Content-MD5 you specified did not match what we received"),
    XAmzContentSHA256Mismatch(400, "XAmzContentSHA256Mismatch", "The provided 'x-amz-content-sha256' header does not match what was computed"),
    NotImplemented(501, "NotImplemented", "A header you provided implies functionality that is not implemented"),
    MethodNotAllowed(405, "MethodNotAllowed", "The specified method is not allowed against this resource"),
    PreconditionFailed(412, "PreconditionFailed", "At least one of the preconditions you specified did not hold"),
    InternalError(500, "InternalError", "We encountered an internal error. Please try again"),
    RequestTimeout(408, "RequestTimeout", "Your socket connection to the server was not read from or written to within the timeout period"),
    RequestTimeTooSkewed(403, "RequestTimeTooSkewed", "The difference between the request time and the server's time is too large"),
    IncompleteBody(400, "IncompleteBody", "You did not provide the number of bytes specified by the Content-Length HTTP header"),
    EntityTooLarge(400, "EntityTooLarge", "Your proposed upload exceeds the maximum allowed object size"),
    ServiceUnavailable(503, "ServiceUnavailable", "Reduce your request rate"),
    SlowDown(503, "SlowDown", "Reduce your request rate"),
    // M2 additions
    RequestRangeNotSatisfiable(416, "InvalidRange", "The requested range is not satisfiable"),
    NotModified(304, "NotModified", "Not Modified"),
    MissingRequestBodyError(400, "MissingRequestBodyError", "Request body is empty"),
    InvalidStorageClass(400, "InvalidStorageClass", "The storage class you specified is not valid"),
    MaxMessageLengthExceeded(400, "MaxMessageLengthExceeded", "Your request was too big"),
    MaxPostPreDataLengthExceededError(400, "MaxPostPreDataLengthExceededError", "Your POST request was too big"),
    MalformedXML(400, "MalformedXML", "The XML you provided was not well-formed or did not validate against our published schema"),
    UnexpectedContent(400, "UnexpectedContent", "This request does not support content"),
    UnresolvableGrantByEmailAddress(400, "UnresolvableGrantByEmailAddress", "The email address you provided does not match any account on record"),
    InvalidLocationConstraint(400, "InvalidLocationConstraint", "The specified location constraint is not valid"),
    // M3 additions: multipart upload errors
    NoSuchUpload(404, "NoSuchUpload", "The specified multipart upload does not exist"),
    NoSuchPart(400, "NoSuchPart", "The specified part does not exist"),
    InvalidPart(400, "InvalidPart", "One or more of the specified parts could not be found"),
    EntityTooSmall(400, "EntityTooSmall", "Your proposed upload is smaller than the minimum allowed object size")
}
