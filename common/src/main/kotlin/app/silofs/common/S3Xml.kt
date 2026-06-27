package app.silofs.common

/*
 * Minimal XML writer for the subset of S3 responses. We avoid pulling in a full
 * XML library because the response shapes are tiny and the AWS SDK is strict
 * about whitespace and casing — hand-rolling is easier to verify.
 *
 * Every writer produces UTF-8 XML with the `<?xml version="1.0"
 * encoding="UTF-8"?>` prolog and no DOCTYPE.
 */

/** Top-level entry point — produces a complete XML document. */
fun s3XmlDocument(
    rootTag: String,
    body: StringBuilder.() -> Unit,
): String {
    val sb = StringBuilder(256)
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
    sb.append('<').append(rootTag).append('>')
    sb.body()
    sb.append("</").append(rootTag).append('>')
    return sb.toString()
}

/** Compatibility alias for callers that prefer the object-qualified name. */
object S3Xml {
    fun document(
        rootTag: String,
        body: StringBuilder.() -> Unit,
    ): String = s3XmlDocument(rootTag, body)

    fun escape(s: String): String = s3XmlEscape(s)

    fun escapeAttr(s: String): String = s3XmlEscape(s)
}

fun StringBuilder.s3Tag(
    name: String,
    value: String?,
) {
    if (value == null) return
    append('<').append(name).append('>')
    append(s3XmlEscape(value))
    append("</").append(name).append('>')
}

fun StringBuilder.s3TagLong(
    name: String,
    value: Long?,
) {
    if (value == null) return
    append('<')
        .append(name)
        .append('>')
        .append(value)
        .append("</")
        .append(name)
        .append('>')
}

fun StringBuilder.s3TagBool(
    name: String,
    value: Boolean,
) {
    append('<')
        .append(name)
        .append('>')
        .append(if (value) "true" else "false")
        .append("</")
        .append(name)
        .append('>')
}

fun StringBuilder.s3Open(
    name: String,
    attrs: Map<String, String> = emptyMap(),
) {
    append('<').append(name)
    attrs.forEach { (k, v) ->
        append(' ')
            .append(k)
            .append("=\"")
            .append(s3XmlEscape(v))
            .append('"')
    }
    append('>')
}

fun StringBuilder.s3Close(name: String) {
    append("</").append(name).append('>')
}

fun s3XmlEscape(s: String): String {
    if (s.isEmpty()) return s
    val sb = StringBuilder(s.length + 8)
    for (c in s) {
        when (c) {
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '&' -> sb.append("&amp;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&apos;")
            '\r' -> sb.append("&#13;")
            '\n' -> sb.append("&#10;")
            '\t' -> sb.append("&#9;")
            else ->
                if (c.code < 0x20) {
                    sb.append("&#").append(c.code).append(';')
                } else {
                    sb.append(c)
                }
        }
    }
    return sb.toString()
}
