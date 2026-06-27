package app.silofs.common

/**
 * Helpers for matching ETags as used by S3 conditional request headers
 * (`If-Match`, `If-None-Match`).
 *
 * S3 accepts ETags in three forms in conditional headers:
 *
 *   - `"<etag>"`           — exact match
 *   - `*`                  — wildcard: matches any existing object (or none)
 *   - `"<etag1>", "<etag2>"` — comma-separated list (any-match)
 *
 * Weak validators (the `W/` prefix) are not produced by S3 itself, but we
 * accept them on read for client compatibility.
 */
object ETagMatcher {
    /**
     * Returns `true` if the [headerValue] matches the supplied [etag].
     *
     * `If-Match` semantics:
     *   - `*` matches iff [etag] is non-null (object exists).
     *   - Any entry in the list matches iff it equals [etag] (after stripping
     *     surrounding quotes and ignoring a `W/` prefix).
     *
     * `If-None-Match` semantics:
     *   - `*` matches iff [etag] is `null` (object does not exist).
     *   - Any entry in the list matches iff it equals [etag].
     *
     * The caller is responsible for interpreting the boolean result against the
     * header semantics — see [ifMatchSatisfied] / [ifNoneMatchSatisfied].
     */
    fun anyEntryMatches(
        headerValue: String,
        etag: String?,
    ): Boolean {
        val parts = headerValue.split(",").map { it.trim() }
        for (raw in parts) {
            if (raw == "*") {
                if (etag != null) return true
                continue
            }
            val cleaned = raw.removePrefix("W/").trim()
            if (cleaned == etag) return true
        }
        return false
    }

    /**
     * For `If-Match`: returns `true` iff the request should proceed (i.e. the
     * precondition is satisfied).
     *
     * - Missing header → no precondition, always satisfied.
     * - `*` → satisfied iff object exists (etag != null).
     * - List of etags → satisfied iff any entry matches.
     */
    fun ifMatchSatisfied(
        headerValue: String?,
        etag: String?,
    ): Boolean {
        if (headerValue == null) return true
        return anyEntryMatches(headerValue, etag)
    }

    /**
     * For `If-None-Match`: returns `true` iff the request should proceed.
     *
     * - Missing header → no precondition, always satisfied.
     * - `*` → satisfied iff object does NOT exist (etag == null).
     * - List of etags → satisfied iff no entry matches.
     */
    fun ifNoneMatchSatisfied(
        headerValue: String?,
        etag: String?,
    ): Boolean {
        if (headerValue == null) return true
        return !anyEntryMatches(headerValue, etag)
    }
}
