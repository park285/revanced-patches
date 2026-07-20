package app.revanced.patches.kakaotalk.chatlog

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** No-dependency executable BKP-001 verifier for preflight, merge, and first-mutation ordering. */
object ReadReceiptBackupRulesVerifier {
    private val modernComplete = """
        <data-extraction-rules>
          <cloud-backup disableIfNoEncryptionCapabilities="true">
            <include domain="file" path="kept"/>
            ${requiredExcludes()}
          </cloud-backup>
          <device-transfer>${requiredExcludes()}</device-transfer>
        </data-extraction-rules>
    """.trimIndent().toByteArray()
    private val legacyComplete = """
        <full-backup-content>
          <include domain="file" path="kept" requireFlags="clientSideEncryption|deviceToDeviceTransfer"/>
          ${requiredExcludes()}
        </full-backup-content>
    """.trimIndent().toByteArray()

    @JvmStatic
    fun main(args: Array<String>) {
        absentAttributesAreAddedOnlyToApplication()
        existingVariantsAreAllMerged()
        generatedNamesAvoidEveryConfigurationCollision()
        modernAndLegacyGrammarIsStrict()
        unsupportedReferencesFailClosed()
        mutationStartsOnlyAfterCompletePreflightAndWritesManifestLast()
        println("BKP-001 verifier passed")
    }

    private fun absentAttributesAreAddedOnlyToApplication() {
        val plan = planBackupRules(manifest(), emptyMap())
        check(plan.resources.size == 2)
        val output = parse(plan.manifest ?: error("manifest mutation missing")).documentElement
        check(!output.hasAttributeNS(ANDROID_NAMESPACE, "dataExtractionRules"))
        check(!output.hasAttributeNS(ANDROID_NAMESPACE, "fullBackupContent"))
        val application = output.getElementsByTagName("application").item(0) as Element
        check(application.getAttributeNS(ANDROID_NAMESPACE, "dataExtractionRules").startsWith("@xml/"))
        check(application.getAttributeNS(ANDROID_NAMESPACE, "fullBackupContent").startsWith("@xml/"))
        assertNineExclusions(plan.resources)

        checkRejects { planBackupRules(manifest("android:dataExtractionRules=\"\""), emptyMap()) }
        checkRejects { planBackupRules("<manifest xmlns:android=\"$ANDROID_NAMESPACE\"/>".toByteArray(), emptyMap()) }
        checkRejects { planBackupRules("<manifest xmlns:android=\"$ANDROID_NAMESPACE\"><application/><application/></manifest>".toByteArray(), emptyMap()) }
        checkRejects {
            planBackupRules(
                "<manifest xmlns:android=\"$ANDROID_NAMESPACE\" xmlns:x=\"urn:x\"><application x:dataExtractionRules=\"@xml/rules\"/></manifest>".toByteArray(),
                emptyMap(),
            )
        }
    }

    private fun existingVariantsAreAllMerged() {
        val resources = mapOf(
            "res/xml/modern.xml" to "<data-extraction-rules/>".toByteArray(),
            "res/xml-v31/modern.xml" to "<data-extraction-rules><cloud-backup/></data-extraction-rules>".toByteArray(),
            "res/xml-b+sr+Latn/modern.xml" to "<data-extraction-rules><device-transfer/></data-extraction-rules>".toByteArray(),
            "res/xml/legacy.xml" to "<full-backup-content/>".toByteArray(),
            "res/xml-night/legacy.xml" to "<full-backup-content><include domain=\"file\" path=\"kept\" requireFlags=\"clientSideEncryption\"/></full-backup-content>".toByteArray(),
        )
        val plan = planBackupRules(referencedManifest(), resources)
        check(plan.manifest == null)
        check(plan.resources.map { it.path }.toSet() == resources.keys)
        plan.resources.forEach { resource ->
            DATABASE_PATHS.forEach { path -> check(resource.content.contains("path=\"$path\"")) }
        }
        check(plan.resources.single { it.path == "res/xml-night/legacy.xml" }.content.contains("requireFlags=\"clientSideEncryption\""))

        checkRejects {
            planBackupRules(referencedManifest(), resources - "res/xml/modern.xml")
        }
        checkRejects {
            planBackupRules(referencedManifest(), resources + ("res/xml-v31/nested/modern.xml" to modernComplete))
        }
        checkRejects {
            planBackupRules(referencedManifest(), resources + ("res/xml-/modern.xml" to modernComplete))
        }
    }

    private fun generatedNamesAvoidEveryConfigurationCollision() {
        val collisions = mapOf(
            "res/xml-b+sr+Latn/revanced_read_receipt_data_extraction_rules.xml" to "<opaque/>".toByteArray(),
            "res/xml/revanced_read_receipt_full_backup_content.xml" to "<opaque/>".toByteArray(),
        )
        val plan = planBackupRules(manifest(), collisions)
        check(plan.resources.none { it.path in collisions })
        check(plan.resources.any { it.path.endsWith("revanced_read_receipt_data_extraction_rules_1.xml") })
        check(plan.resources.any { it.path.endsWith("revanced_read_receipt_full_backup_content_1.xml") })
    }

    private fun modernAndLegacyGrammarIsStrict() {
        val missingSections = rulesPlan("<data-extraction-rules/>", "<full-backup-content/>")
        val modern = missingSections.resources.single { it.path == "res/xml/modern.xml" }.content
        check(modern.contains("<cloud-backup>"))
        check(modern.contains("<device-transfer>"))

        rulesPlan(String(modernComplete), String(legacyComplete)).also { check(it.resources.isEmpty()) }
        checkRejects { rulesPlan("<data-extraction-rules><cloud-backup/><cloud-backup/></data-extraction-rules>", String(legacyComplete)) }
        checkRejects { rulesPlan("<data-extraction-rules><unknown/></data-extraction-rules>", String(legacyComplete)) }
        checkRejects { rulesPlan("<data-extraction-rules xmlns:x=\"urn:x\"><cloud-backup><x:exclude domain=\"database\" path=\"iris_read_receipts.db\"/></cloud-backup></data-extraction-rules>", String(legacyComplete)) }
        checkRejects { rulesPlan("<data-extraction-rules><cloud-backup><disableIfNoEncryptionCapabilities/></cloud-backup></data-extraction-rules>", String(legacyComplete)) }
        checkRejects { rulesPlan("<data-extraction-rules><cloud-backup disableIfNoEncryptionCapabilities=\"sometimes\"/></data-extraction-rules>", String(legacyComplete)) }
        listOf("device_root", "device_file", "device_database", "device_sharedpref").forEach { domain ->
            val plan = rulesPlan(
                "<data-extraction-rules><cloud-backup><include domain=\"$domain\" path=\"kept\"/>${requiredExcludes()}</cloud-backup><device-transfer>${requiredExcludes()}</device-transfer></data-extraction-rules>",
                String(legacyComplete),
            )
            check(plan.resources.isEmpty())
        }
        checkRejects { rulesPlan("<data-extraction-rules><cloud-backup><include domain=\"unknown\" path=\"kept\"/></cloud-backup></data-extraction-rules>", String(legacyComplete)) }
        checkRejects { rulesPlan(String(modernComplete), "<full-backup-content><include domain=\"unknown\" path=\"kept\"/></full-backup-content>") }
        checkRejects { rulesPlan(String(modernComplete), "<full-backup-content><unknown/></full-backup-content>") }
        checkRejects { rulesPlan(String(modernComplete), "<full-backup-content xmlns:x=\"urn:x\"><x:include domain=\"file\" path=\"kept\"/></full-backup-content>") }

        listOf(
            "clientSideEncryption",
            "deviceToDeviceTransfer",
            "clientSideEncryption|deviceToDeviceTransfer",
            "deviceToDeviceTransfer|clientSideEncryption",
        ).forEach { flags ->
            val plan = rulesPlan(
                String(modernComplete),
                "<full-backup-content><include domain=\"device_file\" path=\"kept\" requireFlags=\"$flags\"/>${requiredExcludes()}</full-backup-content>",
            )
            check(plan.resources.isEmpty())
        }
        listOf(
            "",
            "unknown",
            "clientSideEncryption|clientSideEncryption",
            "clientSideEncryption|",
            "|deviceToDeviceTransfer",
            "clientSideEncryption |deviceToDeviceTransfer",
            "clientSideEncryption| deviceToDeviceTransfer",
        ).forEach { flags ->
            checkRejects {
                rulesPlan(
                    String(modernComplete),
                    "<full-backup-content><include domain=\"file\" path=\"kept\" requireFlags=\"$flags\"/></full-backup-content>",
                )
            }
        }
    }

    private fun unsupportedReferencesFailClosed() {
        checkRejects { planBackupRules(referencedManifest(), emptyMap()) }
        checkRejects {
            planBackupRules(
                manifest("android:dataExtractionRules=\"@layout/not_xml\" android:fullBackupContent=\"@xml/legacy\""),
                mapOf("res/xml/legacy.xml" to legacyComplete),
            )
        }
        checkRejects {
            planBackupRules(
                referencedManifest(),
                mapOf("res/xml/modern.xml" to "<opaque/>".toByteArray(), "res/xml/legacy.xml" to legacyComplete),
            )
        }
        checkRejects {
            planBackupRules(
                referencedManifest(),
                mapOf("res/xml/modern.xml" to "<data-extraction-rules>".toByteArray(), "res/xml/legacy.xml" to legacyComplete),
            )
        }
    }

    private fun mutationStartsOnlyAfterCompletePreflightAndWritesManifestLast() {
        val invalid = RecordingFileSystem(manifest = referencedManifest(), resources = emptyMap())
        checkRejects { executeBackupRulesPatch(invalid) }
        check(invalid.events == listOf("read-manifest", "read-resources"))

        val valid = RecordingFileSystem(manifest = manifest(), resources = emptyMap())
        executeBackupRulesPatch(valid)
        check(valid.events.take(2) == listOf("read-manifest", "read-resources"))
        check(valid.events.drop(2).all { it.startsWith("write:") })
        check(valid.events.last() == "write:AndroidManifest.xml")
    }

    private fun rulesPlan(modern: String, legacy: String): BackupRulesPlan = planBackupRules(
        referencedManifest(),
        mapOf("res/xml/modern.xml" to modern.toByteArray(), "res/xml/legacy.xml" to legacy.toByteArray()),
    )

    private fun manifest(applicationAttributes: String = ""): ByteArray = """
        <manifest xmlns:android="$ANDROID_NAMESPACE">
          <application $applicationAttributes/>
        </manifest>
    """.trimIndent().toByteArray()

    private fun referencedManifest(): ByteArray = manifest(
        "android:dataExtractionRules=\"@xml/modern\" android:fullBackupContent=\"@xml/legacy\"",
    )

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))

    private fun assertNineExclusions(resources: List<BackupResourcePlan>) {
        val all = resources.joinToString("\n") { it.content }
        DATABASE_PATHS.forEach { path ->
            check(Regex("path=\"${Regex.escape(path)}\"").findAll(all).count() == 3)
        }
    }

    private fun checkRejects(block: () -> Unit) {
        try {
            block()
            error("expected preflight rejection")
        } catch (_: BackupRulesException) {
            Unit
        }
    }

    private class RecordingFileSystem(
        private val manifest: ByteArray,
        private val resources: Map<String, ByteArray>,
    ) : BackupRulesFileSystem {
        val events = mutableListOf<String>()
        override fun readManifest(): ByteArray = manifest.also { events += "read-manifest" }
        override fun readXmlResources(): Map<String, ByteArray> = resources.also { events += "read-resources" }
        override fun write(path: String, content: String) {
            events += "write:$path"
        }
    }

    private fun requiredExcludes(): String = DATABASE_PATHS.joinToString("") {
        "<exclude domain=\"$DATABASE_DOMAIN\" path=\"$it\"/>"
    }
}
