package app.silofs.server

import app.silofs.common.S3Errors
import app.silofs.common.S3Exception
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import javax.xml.parsers.SAXParserFactory

internal fun parseDeleteObjectsXml(body: String): DeleteObjectsRequest {
    if (body.isBlank()) {
        throw S3Errors.malformedXML("Delete body is empty")
    }

    val objects = ArrayList<DeleteObjectsRequest.Entry>()
    var quiet = false
    var inObject = false
    var currentKey: String? = null
    var currentElement: String? = null
    val charBuf = StringBuilder()

    val handler =
        object : DefaultHandler() {
            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String?,
                attributes: org.xml.sax.Attributes?,
            ) {
                val name = localName?.takeIf { it.isNotEmpty() } ?: qName
                currentElement = name
                charBuf.clear()
                if (name == "Object") {
                    inObject = true
                    currentKey = null
                }
            }

            override fun characters(
                ch: CharArray?,
                start: Int,
                length: Int,
            ) {
                if (currentElement != null) {
                    charBuf.append(ch, start, length)
                }
            }

            override fun endElement(
                uri: String?,
                localName: String?,
                qName: String?,
            ) {
                val name = localName?.takeIf { it.isNotEmpty() } ?: qName
                when (name) {
                    "Quiet" -> quiet = charBuf.toString().trim().equals("true", ignoreCase = true)
                    "Key" -> if (inObject) currentKey = charBuf.toString()
                    "Object" -> {
                        val key = currentKey ?: throw S3Errors.malformedXML("Delete Object is missing Key")
                        objects += DeleteObjectsRequest.Entry(key)
                        inObject = false
                        currentKey = null
                    }
                }
                currentElement = null
            }
        }

    try {
        val reader = SAXParserFactory.newInstance().newSAXParser().xmlReader
        reader.contentHandler = handler
        reader.errorHandler =
            object : DefaultHandler() {
                override fun error(e: SAXParseException): Unit =
                    throw S3Errors.malformedXML("XML parse error at line ${e.lineNumber}: ${e.message}")

                override fun fatalError(e: SAXParseException): Unit =
                    throw S3Errors.malformedXML("XML parse error at line ${e.lineNumber}: ${e.message}")
            }
        reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        reader.parse(InputSource(body.reader()))
    } catch (e: S3Exception) {
        throw e
    } catch (e: SAXException) {
        throw S3Errors.malformedXML("XML parse error: ${e.message}")
    } catch (e: Exception) {
        throw S3Errors.malformedXML("XML parse error: ${e.message}")
    }

    if (objects.isEmpty()) {
        throw S3Errors.malformedXML("Delete body has no <Object> elements")
    }
    return DeleteObjectsRequest(quiet = quiet, objects = objects)
}
