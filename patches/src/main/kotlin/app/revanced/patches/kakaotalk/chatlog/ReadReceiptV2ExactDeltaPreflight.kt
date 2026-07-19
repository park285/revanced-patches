package app.revanced.patches.kakaotalk.chatlog

import app.morphe.patcher.PackageMetadata
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.apksig.ApkVerifier
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

internal const val EXACT_DELTA_INPUT_SHA256 = "c6e5d6faa527d29c7472db97c4c867f9b8cd575328dfb3786c54aac32a3c39dc"
internal const val FORBIDDEN_LEGACY_ARTIFACT_SHA256 = "9d592cd48bf65649d39f82b2258143a3fdbfd01d2012cee143e288e22a76b9c1"
internal const val EXACT_DELTA_INPUT_SIGNER_SHA256 =
    "9f3f2fcbe3f2df76e181bedad5d1c9af6310851e2be11d8ee566fb45f9c0fca1"
internal const val EXACT_DELTA_FINAL_SIGNER_SHA256 =
    "7bd14d480fd15d67d8fa4abdc008fffec3ff66d3f5b6fbab296a0546bd22258b"
internal const val EXACT_DELTA_PACKAGE = "com.kakao.talk"
internal const val EXACT_DELTA_VERSION_NAME = "26.6.0"
internal const val EXACT_DELTA_VERSION_CODE = "29260600"
internal const val EXACT_DELTA_APPLICATION = "com.kakao.talk.application.App"
internal const val EXACT_DELTA_EXTENSION_MARKER =
    "Lapp/revanced/extension/kakaotalk/patches/ShowMessageReadReceiptsPatch;"
internal const val EXACT_DELTA_V2_ENTRYPOINT =
    "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptV2EntryPoint;"
internal const val EXACT_DELTA_V2_SYNTHETIC_MARKER = "revanced_read_receipt_v2_marker"
internal const val FORBIDDEN_LEGACY_CHAT_ID_ACCESSOR = "revanced_read_receipt_persistence_chat_id"

private const val EXACT_DELTA_ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
private const val MAX_TOOL_OUTPUT_BYTES = 20 * 1024 * 1024
private const val TOOL_TIMEOUT_SECONDS = 30L
private const val LEGACY_PERSISTENCE_PREFIX =
    "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptPersistence"
private const val V2_CLASS_PREFIX =
    "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceipt"
private val EXPECTED_DEX_CLASSES = mapOf(
    "Lcom/kakao/talk/application/App;" to "classes.dex",
    "Ltv/l;" to "classes.dex",
    "Ltv/I;" to "classes.dex",
    EXACT_DELTA_EXTENSION_MARKER to "classes.dex",
)

internal class ExactDeltaPreflightException(message: String) : IllegalArgumentException(message)

internal data class ExactDeltaArtifactEvidence(
    val sha256: String,
    val packageName: String,
    val versionName: String,
    val versionCode: String,
    val applicationName: String,
    val extractNativeLibs: String,
    val signerSha256: Set<String>,
    val entryNames: Set<String>,
    val classEntries: Map<String, String>,
    val legacyDescriptors: Set<String>,
    val v2Descriptors: Set<String>,
    val forbiddenDexStrings: Set<String>,
)

internal data class ExactDeltaDexTargets(
    val applicationOnCreate: Method,
    val watermarkWrapper: Method,
    val watermarkPut: Method,
    val extensionMarker: Method,
)

internal object ReadReceiptV2ExactDeltaPreflight {
    fun verifyBeforePatcher(apk: File, aapt2: String = defaultAapt2()): ExactDeltaArtifactEvidence {
        if (!apk.isFile) throw ExactDeltaPreflightException("exact-delta input is not a regular file")
        val sha256 = apk.inputStream().buffered().use(::sha256)
        if (sha256 == FORBIDDEN_LEGACY_ARTIFACT_SHA256) {
            throw ExactDeltaPreflightException("known legacy evidence APK is forbidden")
        }
        requireExact("input SHA-256", sha256, EXACT_DELTA_INPUT_SHA256)
        val manifest = inspectManifest(apk, aapt2)
        val signerDigests = inspectSignerDigests(apk)

        ZipFile(apk).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val names = linkedSetOf<String>()
            entries.forEach { entry ->
                if (!names.add(entry.name)) throw ExactDeltaPreflightException("duplicate APK entry")
                if (entry.name.startsWith('/') || entry.name.split('/').any { it == ".." }) {
                    throw ExactDeltaPreflightException("unsafe APK entry path")
                }
            }

            val classEntries = linkedMapOf<String, String>()
            val legacyDescriptors = linkedSetOf<String>()
            val v2Descriptors = linkedSetOf<String>()
            val forbiddenStrings = linkedSetOf<String>()
            entries.asSequence()
                .filter { it.name.matches(Regex("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) }
                .sortedBy { dexOrdinal(it.name) }
                .forEach { entry ->
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val dex = DexBackedDexFile.fromInputStream(
                        Opcodes.getDefault(),
                        BufferedInputStream(ByteArrayInputStream(bytes)),
                    )
                    dex.classes.forEach { classDef ->
                        val previous = classEntries.put(classDef.type, entry.name)
                        if (previous != null) throw ExactDeltaPreflightException("duplicate DEX class definition")
                        when {
                            classDef.type.startsWith(LEGACY_PERSISTENCE_PREFIX) -> legacyDescriptors += classDef.type
                            classDef.type.startsWith(V2_CLASS_PREFIX) &&
                                !classDef.type.contains("/MessageReadReceipts") -> v2Descriptors += classDef.type
                        }
                        if (classDef.type == "Ltv/l;" && classDef.methods.any { method ->
                                method.name == EXACT_DELTA_V2_SYNTHETIC_MARKER &&
                                    method.parameterTypes.isEmpty() && method.returnType == "V"
                            }
                        ) {
                            forbiddenStrings += "Ltv/l;->$EXACT_DELTA_V2_SYNTHETIC_MARKER()V"
                        }
                        classDef.methods.forEach { method ->
                            if (method.name == FORBIDDEN_LEGACY_CHAT_ID_ACCESSOR) {
                                forbiddenStrings += "${classDef.type}->${method.name}"
                            }
                        }
                    }
                    dex.stringReferences.asSequence().map { it.string }.forEach { value ->
                        if (value.startsWith("DROP TABLE IF EXISTS read_receipt_") ||
                            value == EXACT_DELTA_V2_ENTRYPOINT ||
                            value == EXACT_DELTA_V2_SYNTHETIC_MARKER ||
                            value == FORBIDDEN_LEGACY_CHAT_ID_ACCESSOR ||
                            value.contains("ReadReceiptCaptureFacade;->")
                        ) {
                            forbiddenStrings += value
                        }
                    }
                }

            val evidence = ExactDeltaArtifactEvidence(
                sha256 = sha256,
                packageName = manifest.packageName,
                versionName = manifest.versionName,
                versionCode = manifest.versionCode,
                applicationName = manifest.applicationName,
                extractNativeLibs = manifest.extractNativeLibs,
                signerSha256 = signerDigests,
                entryNames = names,
                classEntries = classEntries.filterKeys(EXPECTED_DEX_CLASSES::containsKey),
                legacyDescriptors = legacyDescriptors,
                v2Descriptors = v2Descriptors,
                forbiddenDexStrings = forbiddenStrings,
            )
            verifyEvidence(evidence)
            val targets = resolveTargets(
                classLookup = { type ->
                    val entryName = evidence.classEntries[type]
                        ?: throw ExactDeltaPreflightException("missing exact DEX class")
                    val entry = zip.getEntry(entryName)
                        ?: throw ExactDeltaPreflightException("missing exact DEX entry")
                    val dex = DexBackedDexFile.fromInputStream(
                        Opcodes.getDefault(),
                        BufferedInputStream(zip.getInputStream(entry)),
                    )
                    dex.classes.singleOrNull { it.type == type }
                },
            )
            verifyTargetShapes(targets)
            return evidence
        }
    }

    fun verifyEvidence(evidence: ExactDeltaArtifactEvidence) {
        if (evidence.sha256 == FORBIDDEN_LEGACY_ARTIFACT_SHA256) {
            throw ExactDeltaPreflightException("known legacy evidence APK is forbidden")
        }
        requireExact("input SHA-256", evidence.sha256, EXACT_DELTA_INPUT_SHA256)
        requireExact("package", evidence.packageName, EXACT_DELTA_PACKAGE)
        requireExact("version name", evidence.versionName, EXACT_DELTA_VERSION_NAME)
        requireExact("version code", evidence.versionCode, EXACT_DELTA_VERSION_CODE)
        requireExact("application", evidence.applicationName, EXACT_DELTA_APPLICATION)
        requireExact("extractNativeLibs", evidence.extractNativeLibs, "false")
        if (evidence.signerSha256 != setOf(EXACT_DELTA_INPUT_SIGNER_SHA256)) {
            throw ExactDeltaPreflightException("unexpected exact-delta signer set")
        }
        EXPECTED_DEX_CLASSES.forEach { (type, entry) ->
            if (evidence.classEntries[type] != entry) {
                throw ExactDeltaPreflightException("unexpected exact DEX ownership")
            }
        }
        if (evidence.legacyDescriptors.isNotEmpty()) {
            throw ExactDeltaPreflightException("legacy read-receipt persistence is present")
        }
        if (evidence.v2Descriptors.isNotEmpty() || evidence.forbiddenDexStrings.isNotEmpty()) {
            throw ExactDeltaPreflightException("v2 exact-delta is already present")
        }
        val forbiddenEntries = evidence.entryNames.filter { name ->
            name == "lib/arm64-v8a/libreadreceiptfs.so" ||
                name == "lib/armeabi-v7a/libreadreceiptfs.so" ||
                name.startsWith("res/xml/revanced_read_receipt_")
        }
        if (forbiddenEntries.isNotEmpty()) {
            throw ExactDeltaPreflightException("v2 exact-delta resource is already present")
        }
    }

    fun verifyFinalArtifactIdentity(apk: File, expectedSignerSha256: String, aapt2: String = defaultAapt2()) {
        verifyFinalArtifactManifest(apk, aapt2)
        verifyFinalSignerIdentity(expectedSignerSha256, inspectSignerDigests(apk))
    }

    internal fun verifyFinalSignerIdentity(expectedSignerSha256: String, signerSha256: Set<String>) {
        if (expectedSignerSha256 != EXACT_DELTA_FINAL_SIGNER_SHA256) {
            throw ExactDeltaPreflightException("expected final signer must match the pinned final signer")
        }
        if (signerSha256 != setOf(EXACT_DELTA_FINAL_SIGNER_SHA256)) {
            throw ExactDeltaPreflightException("unexpected final APK signer set")
        }
    }

    fun verifyUnsignedFinalArtifactIdentity(apk: File, aapt2: String = defaultAapt2()) {
        verifyFinalArtifactManifest(apk, aapt2)
        requireNoVerifiedSigner(apk)
    }

    internal fun requireNoVerifiedSigner(
        apk: File,
        isVerified: (File) -> Boolean = ::hasVerifiedSigner,
    ) {
        if (isVerified(apk)) {
            throw ExactDeltaPreflightException("unsigned final APK has a verified signer")
        }
    }

    private fun verifyFinalArtifactManifest(apk: File, aapt2: String) {
        val manifest = inspectManifest(apk, aapt2)
        requireExact("package", manifest.packageName, EXACT_DELTA_PACKAGE)
        requireExact("version name", manifest.versionName, EXACT_DELTA_VERSION_NAME)
        requireExact("version code", manifest.versionCode, EXACT_DELTA_VERSION_CODE)
        requireExact("application", manifest.applicationName, EXACT_DELTA_APPLICATION)
        requireExact("extractNativeLibs", manifest.extractNativeLibs, "true")
    }

    fun dumpXmlTree(apk: File, path: String, aapt2: String = defaultAapt2()): String {
        if (!apk.isFile || path.startsWith('/') || path.split('/').any { it == ".." }) {
            throw ExactDeltaPreflightException("invalid APK XML dump target")
        }
        return runBounded(aapt2, "dump", "xmltree", "--file", path, apk.absolutePath)
    }

    fun dumpResourceTable(apk: File, aapt2: String = defaultAapt2()): String {
        if (!apk.isFile) throw ExactDeltaPreflightException("invalid APK resource table target")
        return runBounded(aapt2, "dump", "resources", apk.absolutePath)
    }

    fun resolveTargets(classLookup: (String) -> ClassDef?): ExactDeltaDexTargets {
        fun classDef(type: String) = classLookup(type)
            ?: throw ExactDeltaPreflightException("missing exact DEX class")
        val watermarkOwner = classDef("Ltv/l;")
        if (watermarkOwner.fields.count { field -> field.name == "a" && field.type == "J" } != 1) {
            throw ExactDeltaPreflightException("exact chat id owner field is missing")
        }
        return ExactDeltaDexTargets(
            applicationOnCreate = findMethod(classDef("Lcom/kakao/talk/application/App;"), "onCreate", emptyList(), "V"),
            watermarkWrapper = findMethod(watermarkOwner, "I", listOf("J", "J"), "Z"),
            watermarkPut = findMethod(classDef("Ltv/I;"), "j", listOf("J", "J"), "Z"),
            extensionMarker = findMethod(classDef(EXACT_DELTA_EXTENSION_MARKER), "isPatchIncluded", emptyList(), "Z"),
        )
    }

    fun verifyTargetShapes(targets: ExactDeltaDexTargets) {
        verifyApplicationOnCreate(targets.applicationOnCreate)
        verifyWatermarkWrapper(targets.watermarkWrapper)
        verifyWatermarkPut(targets.watermarkPut)
        verifyExtensionMarker(targets.extensionMarker)
    }
}

private data class ManifestEvidence(
    val packageName: String,
    val versionName: String,
    val versionCode: String,
    val applicationName: String,
    val extractNativeLibs: String,
)

private fun inspectManifest(apk: File, aapt2: String): ManifestEvidence {
    val output = runBounded(aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk.absolutePath)
    fun exact(regex: Regex, label: String): String {
        val matches = regex.findAll(output).map { it.groupValues[1] }.toList()
        if (matches.size != 1) throw ExactDeltaPreflightException("unexpected manifest $label cardinality")
        return matches.single()
    }
    val directApplications = Regex("(?m)^      E: application ").findAll(output).count()
    if (directApplications != 1) throw ExactDeltaPreflightException("manifest must contain one direct application")
    val applicationBlock = output.substringAfter("      E: application ", missingDelimiterValue = "")
        .lineSequence()
        .takeWhile { line -> !line.startsWith("      E: ") }
        .joinToString("\n")
    if (applicationBlock.isEmpty()) throw ExactDeltaPreflightException("manifest application is missing")
    return ManifestEvidence(
        packageName = exact(Regex("(?m)^    A: package=\"([^\"]+)\""), "package"),
        versionName = exact(Regex("(?m)^    A: .*:versionName\\([^)]*\\)=\"([^\"]+)\""), "version name"),
        versionCode = exact(Regex("(?m)^    A: .*:versionCode\\([^)]*\\)=([0-9]+)$"), "version code"),
        applicationName = Regex("(?m)^        A: .*:name\\([^)]*\\)=\"([^\"]+)\"")
            .findAll(applicationBlock)
            .map { it.groupValues[1] }
            .singleOrNull()
            ?: throw ExactDeltaPreflightException("application name is not exact"),
        extractNativeLibs = Regex("(?m)^        A: .*:extractNativeLibs\\([^)]*\\)=([^ ]+)$")
            .findAll(applicationBlock)
            .map { it.groupValues[1] }
            .singleOrNull()
            ?: throw ExactDeltaPreflightException("extractNativeLibs is not exact"),
    )
}

private fun inspectSignerDigests(apk: File): Set<String> {
    val result = verifySignatures(apk)
    if (!result.isVerified || result.containsErrors()) {
        throw ExactDeltaPreflightException("APK signature is not verified")
    }
    return result.signerCertificates.mapTo(linkedSetOf(), ::certificateSha256)
}

private fun hasVerifiedSigner(apk: File): Boolean {
    val result = verifySignatures(apk)
    return result.isVerified && !result.containsErrors()
}

private fun verifySignatures(apk: File): ApkVerifier.Result = try {
    ApkVerifier.Builder(apk).build().verify()
} catch (exception: Exception) {
    throw ExactDeltaPreflightException("APK signature verification failed")
}

private fun certificateSha256(certificate: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded).toHex()

private fun sha256(input: java.io.InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1024 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

private fun defaultAapt2(): String = System.getenv("AAPT2")?.takeIf(String::isNotBlank) ?: "aapt2"

private fun runBounded(vararg command: String): String {
    val process = try {
        ProcessBuilder(*command).redirectErrorStream(true).start()
    } catch (exception: Exception) {
        throw ExactDeltaPreflightException("required preflight tool is unavailable")
    }
    val collector = BoundedOutputCollector()
    val reader = Thread {
        process.inputStream.use { input ->
            val scratch = ByteArray(8192)
            while (true) {
                val count = input.read(scratch)
                if (count < 0) break
                if (!collector.append(scratch, count)) {
                    process.destroyForcibly()
                    return@use
                }
            }
        }
    }.apply { isDaemon = true; start() }
    if (!process.waitFor(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        throw ExactDeltaPreflightException("preflight tool timed out")
    }
    reader.join(TimeUnit.SECONDS.toMillis(2))
    if (reader.isAlive) throw ExactDeltaPreflightException("preflight tool output did not close")
    if (collector.exceeded) throw ExactDeltaPreflightException("preflight tool output exceeded bound")
    if (process.exitValue() != 0) throw ExactDeltaPreflightException("preflight tool failed")
    return collector.bytes().toString(Charsets.UTF_8)
}

private class BoundedOutputCollector {
    private val output = ByteArrayOutputStream()
    @Volatile var exceeded = false
        private set

    @Synchronized
    fun append(bytes: ByteArray, count: Int): Boolean {
        if (output.size() + count > MAX_TOOL_OUTPUT_BYTES) {
            exceeded = true
            return false
        }
        output.write(bytes, 0, count)
        return true
    }

    @Synchronized
    fun bytes(): ByteArray = output.toByteArray()
}

private fun dexOrdinal(name: String): Int = name.removePrefix("classes").removeSuffix(".dex").ifEmpty { "1" }.toInt()

private fun findMethod(classDef: ClassDef, name: String, parameters: List<String>, returnType: String): Method =
    classDef.methods.singleOrNull { method ->
        method.name == name && method.parameterTypes == parameters && method.returnType == returnType
    } ?: throw ExactDeltaPreflightException("missing or ambiguous exact DEX method")

private fun requireExact(label: String, actual: String, expected: String) {
    if (actual != expected) throw ExactDeltaPreflightException("unexpected exact-delta $label")
}

private data class LocatedInstruction(val address: Int, val instruction: Instruction)

private fun Method.locatedInstructions(): List<LocatedInstruction> {
    val implementation = implementation ?: throw ExactDeltaPreflightException("exact DEX method has no implementation")
    var address = 0
    return implementation.instructions.map { instruction ->
        LocatedInstruction(address, instruction).also { address += instruction.codeUnits }
    }
}

private fun verifyApplicationOnCreate(method: Method) {
    val implementation = method.implementation ?: throw ExactDeltaPreflightException("application onCreate has no body")
    if (implementation.registerCount != 3 || method.accessFlags and AccessFlags.STATIC.value != 0) {
        throw ExactDeltaPreflightException("unexpected application onCreate frame")
    }
    val first = implementation.instructions.firstOrNull()
        ?: throw ExactDeltaPreflightException("empty application onCreate")
    requireOpcode(first, Opcode.INVOKE_STATIC_RANGE)
    requireMethodReference(
        first,
        "Lapp/morphe/extension/shared/Utils;",
        "setContext",
        listOf("Landroid/content/Context;"),
        "V",
    )
}

private fun verifyWatermarkWrapper(method: Method) {
    val implementation = method.implementation ?: throw ExactDeltaPreflightException("watermark wrapper has no body")
    val instructions = implementation.instructions.toList()
    if (implementation.registerCount != 5 || instructions.map { it.opcode } != listOf(
            Opcode.IGET_OBJECT, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.RETURN
        )
    ) {
        throw ExactDeltaPreflightException("unexpected watermark wrapper shape")
    }
    val field = (instructions[0] as? ReferenceInstruction)?.reference as? FieldReference
        ?: throw ExactDeltaPreflightException("watermark wrapper field is missing")
    if (field.definingClass != "Ltv/l;" || field.name != "e" || field.type != "Ltv/I;") {
        throw ExactDeltaPreflightException("unexpected watermark wrapper field")
    }
    val get = instructions[0] as? TwoRegisterInstruction
        ?: throw ExactDeltaPreflightException("unexpected watermark wrapper registers")
    if (get.registerA != 0 || get.registerB != 0) throw ExactDeltaPreflightException("unexpected watermark wrapper receiver")
    val invoke = instructions[1] as? FiveRegisterInstruction
        ?: throw ExactDeltaPreflightException("unexpected watermark wrapper invoke")
    if (invoke.registerCount != 5 || listOf(
            invoke.registerC, invoke.registerD, invoke.registerE, invoke.registerF, invoke.registerG
        ) != listOf(0, 1, 2, 3, 4)
    ) {
        throw ExactDeltaPreflightException("unexpected watermark wrapper argument registers")
    }
    requireMethodReference(instructions[1], "Ltv/I;", "j", listOf("J", "J"), "Z")
    if ((instructions[2] as? OneRegisterInstruction)?.registerA != 0 ||
        (instructions[3] as? OneRegisterInstruction)?.registerA != 0
    ) {
        throw ExactDeltaPreflightException("unexpected watermark wrapper result register")
    }
}

private fun verifyWatermarkPut(method: Method) {
    val implementation = method.implementation ?: throw ExactDeltaPreflightException("watermark put has no body")
    if (implementation.registerCount != 8) throw ExactDeltaPreflightException("unexpected watermark put frame")
    val located = method.locatedInstructions()
    val watermarkFields = located.filter { locatedInstruction ->
        val field = (locatedInstruction.instruction as? ReferenceInstruction)?.reference as? FieldReference
        field?.definingClass == "Ltv/I;" && field.name == "d" && field.type == "Ljava/util/Map;"
    }
    if (watermarkFields.size != 3) {
        throw ExactDeltaPreflightException("watermark map field cardinality is not exact")
    }
    val puts = located.filter { locatedInstruction ->
        val reference = (locatedInstruction.instruction as? ReferenceInstruction)?.reference as? MethodReference
        reference?.definingClass == "Ljava/util/Map;" && reference.name == "put" &&
            reference.parameterTypes == listOf("Ljava/lang/Object;", "Ljava/lang/Object;") &&
            reference.returnType == "Ljava/lang/Object;"
    }
    if (puts.size != 1 || puts.single().address != 0x29) {
        throw ExactDeltaPreflightException("unexpected watermark Map.put location")
    }
    val trueReturns = located.windowed(2).count { pair ->
        pair[0].instruction.opcode == Opcode.CONST_4 &&
            pair[1].instruction.opcode == Opcode.RETURN &&
            (pair[0].instruction as? com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction)?.narrowLiteral == 1
    }
    if (trueReturns != 1) throw ExactDeltaPreflightException("watermark success return is not unique")
}

private fun verifyExtensionMarker(method: Method) {
    val implementation = method.implementation ?: throw ExactDeltaPreflightException("extension marker has no body")
    val instructions = implementation.instructions.toList()
    if (implementation.registerCount != 1 || instructions.map { it.opcode } != listOf(
            Opcode.CONST_4,
            Opcode.RETURN,
            Opcode.CONST_4,
            Opcode.RETURN,
        ) ||
        (instructions[0] as? com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction)?.narrowLiteral != 1 ||
        (instructions[2] as? com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction)?.narrowLiteral != 0
    ) {
        throw ExactDeltaPreflightException("patched-app extension marker is not enabled")
    }
}

private fun requireOpcode(instruction: Instruction, expected: Opcode) {
    if (instruction.opcode != expected) throw ExactDeltaPreflightException("unexpected exact DEX opcode")
}

private fun requireMethodReference(
    instruction: Instruction,
    definingClass: String,
    name: String,
    parameters: List<String>,
    returnType: String,
) {
    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        ?: throw ExactDeltaPreflightException("exact DEX method reference is missing")
    if (reference.definingClass != definingClass || reference.name != name ||
        reference.parameterTypes != parameters || reference.returnType != returnType
    ) {
        throw ExactDeltaPreflightException("unexpected exact DEX method reference")
    }
}

internal fun verifyExactDeltaPackageMetadata(metadata: PackageMetadata) {
    requireExact("package", metadata.packageName, EXACT_DELTA_PACKAGE)
    requireExact("version name", metadata.versionName, EXACT_DELTA_VERSION_NAME)
    requireExact("version code", metadata.versionCode, EXACT_DELTA_VERSION_CODE)
    val signers = metadata.signingCertificates.values.flatten().mapTo(linkedSetOf(), ::certificateSha256)
    if (signers != setOf(EXACT_DELTA_INPUT_SIGNER_SHA256)) {
        throw ExactDeltaPreflightException("unexpected exact-delta signer set")
    }
}

internal fun ResourcePatchContext.verifyExactDeltaResources() {
    verifyExactDeltaPackageMetadata(packageMetadata)
    document("AndroidManifest.xml").use { document ->
        val manifest = document.documentElement
        val applications = (0 until manifest.childNodes.length).mapNotNull { manifest.childNodes.item(it) as? Element }
            .filter { it.tagName == "application" && it.namespaceURI.isNullOrEmpty() }
        if (applications.size != 1) throw ExactDeltaPreflightException("manifest must contain one direct application")
        val application = applications.single()
        if (application.decodedAndroidAttribute("name") != EXACT_DELTA_APPLICATION ||
            application.decodedAndroidAttribute("extractNativeLibs") != "false" ||
            application.hasDecodedAndroidAttribute("dataExtractionRules") ||
            application.hasDecodedAndroidAttribute("fullBackupContent")
        ) {
            throw ExactDeltaPreflightException("unexpected exact application manifest")
        }
    }
    listOf(
        "lib/arm64-v8a/libreadreceiptfs.so",
        "lib/armeabi-v7a/libreadreceiptfs.so",
        "res/xml/revanced_read_receipt_data_extraction_rules.xml",
        "res/xml/revanced_read_receipt_full_backup_content.xml",
    ).forEach { path ->
        if (get(path, copy = false).exists()) {
            throw ExactDeltaPreflightException("v2 exact-delta resource is already present")
        }
    }
}

internal fun Element.decodedAndroidAttribute(localName: String): String? {
    val manifest = ownerDocument.documentElement
    if (manifest.getAttribute("xmlns:android") != EXACT_DELTA_ANDROID_NAMESPACE) return null
    val namespaced = if (hasAttributeNS(EXACT_DELTA_ANDROID_NAMESPACE, localName)) {
        getAttributeNS(EXACT_DELTA_ANDROID_NAMESPACE, localName)
    } else {
        null
    }
    val qualifiedName = "android:$localName"
    val qualified = if (hasAttribute(qualifiedName)) getAttribute(qualifiedName) else null
    return if (namespaced != null && qualified != null && namespaced != qualified) null else namespaced ?: qualified
}

internal fun Element.hasDecodedAndroidAttribute(localName: String): Boolean {
    if (ownerDocument.documentElement.getAttribute("xmlns:android") != EXACT_DELTA_ANDROID_NAMESPACE) return false
    return hasAttributeNS(EXACT_DELTA_ANDROID_NAMESPACE, localName) || hasAttribute("android:$localName")
}

internal fun Element.setDecodedAndroidAttribute(localName: String, value: String) {
    check(ownerDocument.documentElement.getAttribute("xmlns:android") == EXACT_DELTA_ANDROID_NAMESPACE) {
        "decoded manifest android namespace changed"
    }
    if (hasAttributeNS(EXACT_DELTA_ANDROID_NAMESPACE, localName)) {
        setAttributeNS(EXACT_DELTA_ANDROID_NAMESPACE, "android:$localName", value)
    } else {
        setAttribute("android:$localName", value)
    }
}

internal fun BytecodePatchContext.verifyExactDeltaBytecode(): ExactDeltaDexTargets {
    verifyExactDeltaPackageMetadata(packageMetadata)
    val legacy = mutableListOf<String>()
    val v2 = mutableListOf<String>()
    var partialInvokePresent = false
    classDefForEach { classDef ->
        when {
            classDef.type.startsWith(LEGACY_PERSISTENCE_PREFIX) -> legacy += classDef.type
            classDef.type.startsWith(V2_CLASS_PREFIX) && !classDef.type.contains("/MessageReadReceipts") -> v2 += classDef.type
        }
        if (classDef.type == "Ltv/l;" && classDef.methods.any { method ->
                method.name == EXACT_DELTA_V2_SYNTHETIC_MARKER &&
                    method.parameterTypes.isEmpty() && method.returnType == "V"
            }
        ) {
            v2 += "Ltv/l;->$EXACT_DELTA_V2_SYNTHETIC_MARKER()V"
        }
        if (classDef.methods.any { method ->
                method.name == FORBIDDEN_LEGACY_CHAT_ID_ACCESSOR
            }
        ) {
            v2 += "${classDef.type}->chat-id-accessor"
        }
        classDef.methods.forEach { method ->
            method.implementation?.instructions?.forEach { instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                if (reference?.definingClass == EXACT_DELTA_V2_ENTRYPOINT ||
                    reference?.definingClass?.contains("ReadReceiptCaptureFacade") == true
                ) {
                    partialInvokePresent = true
                }
            }
        }
    }
    if (legacy.isNotEmpty()) throw ExactDeltaPreflightException("legacy read-receipt persistence is present")
    if (v2.isNotEmpty() || partialInvokePresent) {
        throw ExactDeltaPreflightException("v2 exact-delta is already present")
    }
    if (classDefByOrNull(EXACT_DELTA_EXTENSION_MARKER) == null) {
        throw ExactDeltaPreflightException("required patched-app extension marker is missing")
    }
    return ReadReceiptV2ExactDeltaPreflight.resolveTargets(::classDefByOrNull).also(
        ReadReceiptV2ExactDeltaPreflight::verifyTargetShapes,
    )
}

internal val readReceiptV2ExactDeltaResourcePreflightPatch = resourcePatch {
    execute {
        try {
            verifyExactDeltaResources()
        } catch (exception: ExactDeltaPreflightException) {
            throw PatchException(exception.message ?: "exact-delta resource preflight failed")
        }
    }
}

internal val readReceiptV2ExactDeltaBytecodePreflightPatch = bytecodePatch {
    dependsOn(readReceiptV2ExactDeltaResourcePreflightPatch)
    execute {
        try {
            verifyExactDeltaBytecode()
        } catch (exception: ExactDeltaPreflightException) {
            throw PatchException(exception.message ?: "exact-delta bytecode preflight failed")
        }
    }
}
