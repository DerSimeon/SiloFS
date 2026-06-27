package app.silofs.server

import app.silofs.common.S3Errors
import app.silofs.metadata.LifecycleRuleRecord
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.time.Instant
import javax.xml.parsers.SAXParserFactory

internal object GovernanceXml {
    fun parseVersioningStatus(body: String): String {
        if (body.isBlank()) return "DISABLED"
        val values = parseText(body)
        val status = values["Status"]?.trim().orEmpty()
        return when {
            status.equals("Enabled", ignoreCase = true) -> "ENABLED"
            status.equals("Suspended", ignoreCase = true) -> "SUSPENDED"
            status.isBlank() -> "DISABLED"
            else -> throw S3Errors.invalidArgument("Status", status, "must be Enabled or Suspended")
        }
    }

    fun parseObjectLockDefault(body: String): Pair<String?, Int?> {
        if (body.isBlank()) return null to null
        val values = parseText(body)
        val mode = values["Mode"]?.trim()?.uppercase()
        val days = values["Days"]?.trim()?.toIntOrNull()
        if (mode != null && mode !in setOf("GOVERNANCE", "COMPLIANCE")) {
            throw S3Errors.invalidArgument("Mode", mode, "must be GOVERNANCE or COMPLIANCE")
        }
        if (days != null && days <= 0) {
            throw S3Errors.invalidArgument("Days", days.toString(), "must be positive")
        }
        return mode to days
    }

    fun parseRetention(body: String): Pair<String, Instant> {
        val values = parseText(body)
        val mode =
            values["Mode"]?.trim()?.uppercase()
                ?: throw S3Errors.malformedXML("Retention is missing Mode")
        if (mode !in setOf("GOVERNANCE", "COMPLIANCE")) {
            throw S3Errors.invalidArgument("Mode", mode, "must be GOVERNANCE or COMPLIANCE")
        }
        val retainUntil =
            values["RetainUntilDate"]?.trim()
                ?: throw S3Errors.malformedXML("Retention is missing RetainUntilDate")
        return mode to Instant.parse(retainUntil)
    }

    fun parseLegalHold(body: String): Boolean {
        val status =
            parseText(body)["Status"]?.trim()
                ?: throw S3Errors.malformedXML("LegalHold is missing Status")
        return when {
            status.equals("ON", ignoreCase = true) -> true
            status.equals("OFF", ignoreCase = true) -> false
            else -> throw S3Errors.invalidArgument("Status", status, "must be ON or OFF")
        }
    }

    fun parseLifecycleRules(
        body: String,
        bucket: String,
    ): List<LifecycleRuleRecord> {
        if (body.isBlank()) return emptyList()
        val rules = ArrayList<LifecycleRuleRecord>()
        var inRule = false
        var inFilter = false
        var inExpiration = false
        var inNoncurrent = false
        var inAbort = false
        var currentElement: String? = null
        val text = StringBuilder()
        var id: String? = null
        var enabled = true
        var prefix: String? = null
        var currentDays: Int? = null
        var noncurrentDays: Int? = null
        var abortDays: Int? = null

        parse(
            body,
            object : DefaultHandler() {
                override fun startElement(
                    uri: String?,
                    localName: String?,
                    qName: String?,
                    attributes: Attributes?,
                ) {
                    val name = elementName(localName, qName)
                    currentElement = name
                    text.clear()
                    when (name) {
                        "Rule" -> {
                            inRule = true
                            id = null
                            enabled = true
                            prefix = null
                            currentDays = null
                            noncurrentDays = null
                            abortDays = null
                        }
                        "Filter" -> inFilter = true
                        "Expiration" -> inExpiration = true
                        "NoncurrentVersionExpiration" -> inNoncurrent = true
                        "AbortIncompleteMultipartUpload" -> inAbort = true
                    }
                }

                override fun characters(
                    ch: CharArray?,
                    start: Int,
                    length: Int,
                ) {
                    if (currentElement != null) text.append(ch, start, length)
                }

                override fun endElement(
                    uri: String?,
                    localName: String?,
                    qName: String?,
                ) {
                    val name = elementName(localName, qName)
                    val value = text.toString().trim()
                    if (inRule) {
                        when (name) {
                            "ID" -> id = value.ifBlank { null }
                            "Status" -> enabled = value.equals("Enabled", ignoreCase = true)
                            "Prefix" -> if (inFilter || prefix == null) prefix = value
                            "Days" -> {
                                val days =
                                    value.toIntOrNull()
                                        ?: throw S3Errors.malformedXML("Lifecycle Days must be an integer")
                                when {
                                    inExpiration -> currentDays = days
                                    inNoncurrent -> noncurrentDays = days
                                }
                            }
                            "DaysAfterInitiation" -> {
                                abortDays = value.toIntOrNull()
                                    ?: throw S3Errors.malformedXML("DaysAfterInitiation must be an integer")
                            }
                            "Expiration" -> inExpiration = false
                            "NoncurrentVersionExpiration" -> inNoncurrent = false
                            "AbortIncompleteMultipartUpload" -> inAbort = false
                            "Filter" -> inFilter = false
                            "Rule" -> {
                                rules +=
                                    LifecycleRuleRecord(
                                        bucket = bucket,
                                        ruleId = id ?: "rule-${rules.size + 1}",
                                        enabled = enabled,
                                        prefix = prefix,
                                        currentVersionExpirationDays = currentDays,
                                        noncurrentVersionExpirationDays = noncurrentDays,
                                        abortIncompleteMultipartDays = abortDays,
                                    )
                                inRule = false
                            }
                        }
                    }
                    currentElement = null
                }
            },
        )
        return rules
    }

    private fun parseText(body: String): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        var currentElement: String? = null
        val text = StringBuilder()
        parse(
            body,
            object : DefaultHandler() {
                override fun startElement(
                    uri: String?,
                    localName: String?,
                    qName: String?,
                    attributes: Attributes?,
                ) {
                    currentElement = elementName(localName, qName)
                    text.clear()
                }

                override fun characters(
                    ch: CharArray?,
                    start: Int,
                    length: Int,
                ) {
                    if (currentElement != null) text.append(ch, start, length)
                }

                override fun endElement(
                    uri: String?,
                    localName: String?,
                    qName: String?,
                ) {
                    currentElement?.let { values[it] = text.toString().trim() }
                    currentElement = null
                }
            },
        )
        return values
    }

    private fun parse(
        body: String,
        handler: DefaultHandler,
    ) {
        try {
            val reader = SAXParserFactory.newInstance().newSAXParser().xmlReader
            reader.contentHandler = handler
            reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            reader.parse(InputSource(body.reader()))
        } catch (e: app.silofs.common.S3Exception) {
            throw e
        } catch (e: Exception) {
            throw S3Errors.malformedXML("XML parse error: ${e.message}")
        }
    }

    private fun elementName(
        localName: String?,
        qName: String?,
    ): String = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty()
}
