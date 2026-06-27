package app.silofs.common

/**
 * A parsed `Range: bytes=...` header. Only a single range is supported in M1.
 *
 * Examples accepted:
 *   bytes=0-499     -> start=0, endInclusive=499
 *   bytes=500-      -> start=500, endInclusive=size-1
 *   bytes=-500      -> last 500 bytes; start=size-500, endInclusive=size-1
 *
 * Multi-range requests (`bytes=0-1,3-4`) are rejected with [S3ErrorCode.NotImplemented]
 * because S3 itself only returns the first range and we prefer to fail loudly.
 */
data class ByteRange(
    val start: Long,
    val endInclusive: Long,
) {
    val length: Long get() = endInclusive - start + 1

    companion object {
        fun parse(
            header: String?,
            size: Long,
        ): ByteRange? {
            if (header == null) return null
            val trimmed = header.trim()
            if (!trimmed.startsWith("bytes=", ignoreCase = true)) {
                throw S3Exception(
                    S3ErrorCode.InvalidRange,
                    "Only 'bytes' range units are supported: $header",
                )
            }
            val spec = trimmed.substring("bytes=".length).trim()
            if (spec.contains(",")) {
                throw S3Exception(
                    S3ErrorCode.NotImplemented,
                    "Multi-range requests are not supported: $header",
                )
            }
            val dash = spec.indexOf('-')
            if (dash < 0) {
                throw S3Exception(S3ErrorCode.InvalidRange, "Invalid range spec: $header")
            }
            val left = spec.substring(0, dash).trim()
            val right = spec.substring(dash + 1).trim()
            val range =
                when {
                    left.isEmpty() && right.isEmpty() ->
                        throw S3Exception(S3ErrorCode.InvalidRange, "Invalid range spec: $header")
                    left.isEmpty() -> {
                        val suffix =
                            right.toLongOrNull()
                                ?: throw S3Exception(S3ErrorCode.InvalidRange, "Invalid range suffix: $header")
                        if (suffix <= 0) {
                            throw S3Exception(S3ErrorCode.InvalidRange, "Suffix must be > 0: $header")
                        }
                        val start = (size - suffix).coerceAtLeast(0)
                        ByteRange(start, size - 1)
                    }
                    right.isEmpty() -> {
                        val start =
                            left.toLongOrNull()
                                ?: throw S3Exception(S3ErrorCode.InvalidRange, "Invalid range start: $header")
                        if (start < 0 || start >= size) {
                            throw S3Exception(S3ErrorCode.InvalidRange, "Range start out of bounds: $header (size=$size)")
                        }
                        ByteRange(start, size - 1)
                    }
                    else -> {
                        val start =
                            left.toLongOrNull()
                                ?: throw S3Exception(S3ErrorCode.InvalidRange, "Invalid range start: $header")
                        val end =
                            right.toLongOrNull()
                                ?: throw S3Exception(S3ErrorCode.InvalidRange, "Invalid range end: $header")
                        if (start < 0 || end < start || start >= size) {
                            throw S3Exception(S3ErrorCode.InvalidRange, "Range out of bounds: $header (size=$size)")
                        }
                        ByteRange(start, end.coerceAtMost(size - 1))
                    }
                }
            return range
        }
    }
}
