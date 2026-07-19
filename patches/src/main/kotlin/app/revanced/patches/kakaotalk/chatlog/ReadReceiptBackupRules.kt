package app.revanced.patches.kakaotalk.chatlog

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

internal const val DATABASE_DOMAIN = "database"
internal const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
internal val DATABASE_PATHS = listOf("iris_read_receipts.db", "iris_read_receipts.db-wal", "iris_read_receipts.db-shm")
private val BACKUP_DOMAINS = setOf(
    "root",
    "file",
    "database",
    "sharedpref",
    "external",
    "device_root",
    "device_file",
    "device_database",
    "device_sharedpref",
)
private val REQUIRE_FLAGS = setOf("clientSideEncryption", "deviceToDeviceTransfer")

internal data class BackupResourcePlan(val path: String, val content: String)
internal data class BackupRulesPlan(
    val manifest: String?,
    val resources: List<BackupResourcePlan>,
)

internal class BackupRulesException(message: String) : IllegalArgumentException(message)

private fun parse(bytes: ByteArray): Document = try {
    DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().apply {
        setErrorHandler(object : DefaultHandler() {
            override fun warning(exception: SAXParseException) = throw exception
            override fun error(exception: SAXParseException) = throw exception
            override fun fatalError(exception: SAXParseException) = throw exception
        })
    }.parse(ByteArrayInputStream(bytes))
} catch (exception: Exception) {
    throw BackupRulesException("malformed XML: ${exception.javaClass.simpleName}")
}

private fun serialize(document: Document): String = ByteArrayOutputStream().use { output ->
    TransformerFactory.newInstance().newTransformer().apply {
        setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        setOutputProperty(OutputKeys.INDENT, "yes")
    }.transform(DOMSource(document), StreamResult(output))
    output.toString(Charsets.UTF_8)
}

private fun Element.directElements(): List<Element> = (0 until childNodes.length)
    .mapNotNull { childNodes.item(it) as? Element }

private fun Element.requireUnnamespaced(name: String) {
    if (!namespaceURI.isNullOrEmpty() || tagName != name) throw BackupRulesException("unsupported namespaced or unknown element")
}

private fun Element.validateEmptyContent() {
    for (index in 0 until childNodes.length) {
        val child = childNodes.item(index)
        when (child.nodeType) {
            Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> if (!child.nodeValue.isNullOrBlank()) {
                throw BackupRulesException("rule element has content")
            }
            Node.COMMENT_NODE -> Unit
            else -> throw BackupRulesException("unsupported rule content")
        }
    }
}

private fun Element.attributesByLocalName(allowed: Set<String>): Map<String, String> {
    val values = linkedMapOf<String, String>()
    for (index in 0 until attributes.length) {
        val attribute = attributes.item(index)
        if (attribute.namespaceURI == XMLConstants.XMLNS_ATTRIBUTE_NS_URI) continue
        if (!attribute.namespaceURI.isNullOrEmpty() || attribute.nodeName !in allowed || values.put(attribute.nodeName, attribute.nodeValue) != null) {
            throw BackupRulesException("unsupported namespaced or unknown attribute")
        }
    }
    return values
}

private fun validateRule(rule: Element, legacy: Boolean): Pair<String, String>? {
    val name = rule.tagName
    if (name != "include" && name != "exclude") throw BackupRulesException("unsupported backup rule")
    rule.requireUnnamespaced(name)
    val allowed = if (legacy && name == "include") setOf("domain", "path", "requireFlags") else setOf("domain", "path")
    val attributes = rule.attributesByLocalName(allowed)
    val domain = attributes["domain"]?.takeIf(String::isNotEmpty) ?: throw BackupRulesException("backup rule has no domain")
    val path = attributes["path"]?.takeIf(String::isNotEmpty) ?: throw BackupRulesException("backup rule has no path")
    if (domain !in BACKUP_DOMAINS) throw BackupRulesException("unsupported backup domain")
    attributes["requireFlags"]?.let { value ->
        val tokens = value.split('|')
        if (value.any(Char::isWhitespace) || tokens.size !in 1..2 || tokens.any { it !in REQUIRE_FLAGS } || tokens.toSet().size != tokens.size) {
            throw BackupRulesException("unsupported legacy requireFlags")
        }
    }
    rule.validateEmptyContent()
    return if (name == "exclude") domain to path else null
}

private fun validateRuleContainer(parent: Element, legacy: Boolean): Set<Pair<String, String>> {
    val exclusions = linkedSetOf<Pair<String, String>>()
    parent.directElements().forEach { rule ->
        validateRule(rule, legacy)?.let { exclusion ->
            if (!exclusions.add(exclusion)) throw BackupRulesException("duplicate backup exclusion")
        }
    }
    return exclusions
}

private fun addMissing(parent: Element, existing: Set<Pair<String, String>>): Boolean {
    val missing = DATABASE_PATHS.filterNot { DATABASE_DOMAIN to it in existing }
    missing.forEach { path ->
        parent.appendChild(parent.ownerDocument.createElement("exclude").apply {
            setAttribute("domain", DATABASE_DOMAIN)
            setAttribute("path", path)
        })
    }
    return missing.isNotEmpty()
}

private fun prepareModern(document: Document): Boolean {
    val root = document.documentElement
    root.requireUnnamespaced("data-extraction-rules")
    root.attributesByLocalName(emptySet())
    val sections = root.directElements()
    if (sections.any { it.tagName !in setOf("cloud-backup", "device-transfer") || !it.namespaceURI.isNullOrEmpty() }) {
        throw BackupRulesException("unsupported data extraction rules child")
    }
    if (sections.groupingBy { it.tagName }.eachCount().values.any { it > 1 }) {
        throw BackupRulesException("duplicate data extraction rules section")
    }

    fun section(name: String): Element = sections.singleOrNull { it.tagName == name }
        ?: root.appendChild(document.createElement(name)) as Element

    val cloud = section("cloud-backup")
    val transfer = section("device-transfer")
    val cloudAttributes = cloud.attributesByLocalName(setOf("disableIfNoEncryptionCapabilities"))
    cloudAttributes["disableIfNoEncryptionCapabilities"]?.let {
        if (it != "true" && it != "false") throw BackupRulesException("invalid encryption capability attribute")
    }
    transfer.attributesByLocalName(emptySet())
    val cloudChanged = addMissing(cloud, validateRuleContainer(cloud, legacy = false))
    val transferChanged = addMissing(transfer, validateRuleContainer(transfer, legacy = false))
    return cloudChanged || transferChanged || sections.size != 2
}

private fun prepareLegacy(document: Document): Boolean {
    val root = document.documentElement
    root.requireUnnamespaced("full-backup-content")
    root.attributesByLocalName(emptySet())
    return addMissing(root, validateRuleContainer(root, legacy = true))
}

private fun resourceName(reference: String): String {
    if (!reference.startsWith("@xml/") || !reference.substring(5).matches(Regex("[a-z0-9_]+"))) {
        throw BackupRulesException("unsupported backup resource reference")
    }
    return reference.substring(5)
}

private fun matchingXmlVariants(resources: Map<String, *>, name: String): List<String> {
    val basename = "$name.xml"
    val matches = resources.keys.mapNotNull { path ->
        val parts = path.split('/')
        if (parts.lastOrNull() != basename || parts.firstOrNull() != "res" || parts.getOrNull(1)?.startsWith("xml") != true) {
            return@mapNotNull null
        }
        val directory = parts[1]
        val validDirectory = directory == "xml" || (directory.startsWith("xml-") && directory.length > 4)
        if (parts.size != 3 || !validDirectory) throw BackupRulesException("malformed XML resource path")
        path
    }.sorted()
    return matches
}

private fun resourceVariants(resources: Map<String, ByteArray>, name: String): List<String> {
    val matches = matchingXmlVariants(resources, name)
    if ("res/xml/$name.xml" !in matches) throw BackupRulesException("backup resource has no base configuration")
    return matches
}

private fun unusedResourceName(resources: Map<String, ByteArray>, base: String): String {
    var suffix = 0
    while (true) {
        val candidate = if (suffix == 0) base else "${base}_$suffix"
        val collision = matchingXmlVariants(resources, candidate).isNotEmpty()
        if (!collision) return candidate
        suffix++
    }
}

private fun Element.androidAttribute(localName: String): Pair<Boolean, String?> {
    for (index in 0 until attributes.length) {
        val attribute = attributes.item(index)
        if (attribute.localName == localName && attribute.namespaceURI != ANDROID_NAMESPACE) {
            throw BackupRulesException("backup manifest attribute uses the wrong namespace")
        }
    }
    val present = hasAttributeNS(ANDROID_NAMESPACE, localName)
    return present to if (present) getAttributeNS(ANDROID_NAMESPACE, localName) else null
}

/** Builds the complete backup mutation plan without mutating any input bytes. */
internal fun planBackupRules(manifestBytes: ByteArray, resources: Map<String, ByteArray>): BackupRulesPlan {
    val manifest = parse(manifestBytes)
    val root = manifest.documentElement
    root.requireUnnamespaced("manifest")
    val applications = root.directElements().filter { it.tagName == "application" && it.namespaceURI.isNullOrEmpty() }
    if (applications.size != 1) throw BackupRulesException("manifest must contain exactly one direct application")
    if (root.directElements().any { it.localName == "application" && (it.tagName != "application" || !it.namespaceURI.isNullOrEmpty()) }) {
        throw BackupRulesException("application uses an unsupported namespace")
    }
    val application = applications.single()
    val (modernPresent, modernValue) = application.androidAttribute("dataExtractionRules")
    val (legacyPresent, legacyValue) = application.androidAttribute("fullBackupContent")
    if (modernPresent && modernValue.isNullOrEmpty()) throw BackupRulesException("empty dataExtractionRules reference")
    if (legacyPresent && legacyValue.isNullOrEmpty()) throw BackupRulesException("empty fullBackupContent reference")

    val resourcePlans = mutableListOf<BackupResourcePlan>()
    fun prepareExisting(reference: String, modern: Boolean) {
        val name = resourceName(reference)
        resourceVariants(resources, name).forEach { path ->
            val document = parse(resources.getValue(path))
            val changed = if (modern) prepareModern(document) else prepareLegacy(document)
            if (changed) resourcePlans += BackupResourcePlan(path, serialize(document))
        }
    }

    var manifestChanged = false
    if (modernPresent) {
        prepareExisting(modernValue!!, modern = true)
    } else {
        val name = unusedResourceName(resources, "revanced_read_receipt_data_extraction_rules")
        val document = parse("<data-extraction-rules/>".toByteArray())
        prepareModern(document)
        resourcePlans += BackupResourcePlan("res/xml/$name.xml", serialize(document))
        application.setAttributeNS(ANDROID_NAMESPACE, "android:dataExtractionRules", "@xml/$name")
        manifestChanged = true
    }
    if (legacyPresent) {
        prepareExisting(legacyValue!!, modern = false)
    } else {
        val occupied = resources + resourcePlans.associate { it.path to it.content.toByteArray() }
        val name = unusedResourceName(occupied, "revanced_read_receipt_full_backup_content")
        val document = parse("<full-backup-content/>".toByteArray())
        prepareLegacy(document)
        resourcePlans += BackupResourcePlan("res/xml/$name.xml", serialize(document))
        application.setAttributeNS(ANDROID_NAMESPACE, "android:fullBackupContent", "@xml/$name")
        manifestChanged = true
    }
    return BackupRulesPlan(if (manifestChanged) serialize(manifest) else null, resourcePlans.toList())
}
