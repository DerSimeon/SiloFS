package app.silofs.common

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * S3 uses RFC 1123 dates for HTTP-level headers (`Last-Modified`, `Date`) and
 * ISO-8601 with milliseconds for XML body fields (`<LastModified>`). These
 * helpers format both correctly without leaking timezone confusion.
 */
object S3Time {
    private val httpDateFormatter: DateTimeFormatter =
        DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)

    private val iso8601Millis: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** Header style: `Tue, 04 Jun 2024 12:34:56 GMT`. */
    fun formatHttpDate(instant: Instant): String = httpDateFormatter.format(instant)

    /** XML style: `2024-06-04T12:34:56.789Z`. */
    fun formatIso8601(instant: Instant): String = iso8601Millis.format(instant)

    /** Parse an `x-amz-date` header in `yyyyMMdd'T'HHmmss'Z'` form. */
    fun parseAmzDate(raw: String): Instant {
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        return Instant.from(fmt.parse(raw))
    }

    /** Parse an HTTP `Date` header in RFC 1123 form. */
    fun parseHttpDate(raw: String): Instant = Instant.from(httpDateFormatter.parse(raw))
}
