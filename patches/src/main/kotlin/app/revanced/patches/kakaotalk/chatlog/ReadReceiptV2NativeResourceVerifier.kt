package app.revanced.patches.kakaotalk.chatlog

import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipFile

object ReadReceiptV2NativeResourceVerifier {
    private const val FINAL_DATABASE_DOMAIN = "database"
    private const val V2_EXTENSION_RESOURCE = "extensions/kakaotalk/read-receipt-v2.mpe"
    private const val V2_EXTENSION_PREFIX =
        "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/"
    private const val V2_EXTENSION_R_CLASS =
        "Lapp/revanced/extension/kakaotalk/readreceipt/v2/R;"
    private const val UI_SENDER_ID_ACCESSOR = "revanced_read_receipt_sender_id"
    private const val UI_CURRENT_USER_ID_ACCESSOR = "revanced_read_receipt_current_user_id"
    private val UI_SYNTHESIS_ACCESSORS = setOf(UI_SENDER_ID_ACCESSOR, UI_CURRENT_USER_ID_ACCESSOR)
    private val FINAL_DATABASE_PATHS = listOf(
        "iris_read_receipts.db",
        "iris_read_receipts.db-wal",
        "iris_read_receipts.db-shm",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "--self-test" -> selfTest()
            "--final-apk" -> {
                if (args.size != 3) {
                    throw IllegalArgumentException("usage: --final-apk <signed-output.apk> <expected-signer-sha256>")
                }
                verifyFinalApk(File(args[1]), args[2])
                println(
                    "RRV2-FINAL-APK-001 final signed APK passed: " +
                        "identity=1 signer=1 nativeAbis=2 backupExclusions=9 captureHooks=1 accessors=0 marker=1",
                )
            }
            "--unsigned-final-apk" -> {
                if (args.size != 2) {
                    throw IllegalArgumentException("usage: --unsigned-final-apk <unsigned-output.apk>")
                }
                verifyUnsignedFinalApk(File(args[1]))
                println(
                    "RRV2-UNSIGNED-APK-001 unsigned APK passed: " +
                        "identity=1 verifiedSigner=0 nativeAbis=2 backupExclusions=9 " +
                        "captureHooks=1 accessors=0 marker=1",
                )
            }
            else -> throw IllegalArgumentException(
                "usage: ReadReceiptV2NativeResourceVerifier --self-test | " +
                    "--unsigned-final-apk <apk> | --final-apk <apk> <signer-sha256>",
            )
        }
    }

    internal fun verifyFinalApk(apk: File, expectedSignerSha256: String) {
        if (!apk.isFile) throw IllegalArgumentException("final APK is not a regular file")
        ReadReceiptV2ExactDeltaPreflight.verifyFinalArtifactIdentity(apk, expectedSignerSha256)
        verifyFinalPayload(apk)
    }

    internal fun verifyUnsignedFinalApk(apk: File) {
        if (!apk.isFile) throw IllegalArgumentException("unsigned APK is not a regular file")
        ReadReceiptV2ExactDeltaPreflight.verifyUnsignedFinalArtifactIdentity(apk)
        verifyFinalPayload(apk)
    }

    private fun verifyFinalPayload(apk: File) {
        verifyFinalBackupRules(apk)
        ZipFile(apk).use { zip ->
            val names = linkedSetOf<String>()
            zip.entries().asSequence().forEach { entry ->
                if (!names.add(entry.name)) throw IllegalArgumentException("duplicate final APK entry")
            }
            val nativeEntries = names.filter { it.endsWith("/$NATIVE_LIBRARY_NAME") }
            val expectedEntries = NATIVE_ABIS.keys.mapTo(linkedSetOf()) { "lib/$it/$NATIVE_LIBRARY_NAME" }
            if (nativeEntries.toSet() != expectedEntries) {
                throw IllegalArgumentException("final APK read-receipt ABI set is not exact")
            }
            NATIVE_ABIS.keys.forEach { abi ->
                val path = "lib/$abi/$NATIVE_LIBRARY_NAME"
                val entry = zip.getEntry(path) ?: throw IllegalArgumentException("missing final APK native resource")
                val bytes = zip.getInputStream(entry).use { it.readNBytes(1024 * 1024 + 1) }
                verifyReadReceiptNativeResource(ReadReceiptNativeResource(abi, bytes))
            }
            verifyFinalDex(zip)
        }
    }

    private fun verifyFinalBackupRules(apk: File) {
        val manifest = ReadReceiptV2ExactDeltaPreflight.dumpXmlTree(apk, "AndroidManifest.xml")
        fun referenceId(attribute: String): String {
            val matches = Regex(
                "(?m)^        A: .*:$attribute\\([^)]*\\)=@(0x[0-9a-f]+)(?: .*|)$",
            ).findAll(manifest).map { it.groupValues[1] }.toList()
            return matches.singleOrNull()
                ?: throw IllegalArgumentException("final backup manifest reference is not exact")
        }
        val resourceTable = ReadReceiptV2ExactDeltaPreflight.dumpResourceTable(apk)
        val resources = Regex("(?m)^    resource (0x[0-9a-f]+) xml/([a-z0-9_]+)$")
            .findAll(resourceTable).associate { match -> match.groupValues[1] to match.groupValues[2] }
        val modernName = resources[referenceId("dataExtractionRules")]
        val legacyName = resources[referenceId("fullBackupContent")]
        if (modernName != "revanced_read_receipt_data_extraction_rules" ||
            legacyName != "revanced_read_receipt_full_backup_content"
        ) {
            throw IllegalArgumentException("final backup resource names are not exact")
        }
        val modern = ReadReceiptV2ExactDeltaPreflight.dumpXmlTree(apk, "res/xml/$modernName.xml")
        val legacy = ReadReceiptV2ExactDeltaPreflight.dumpXmlTree(apk, "res/xml/$legacyName.xml")
        verifyBackupXmlDump(
            modern,
            root = "data-extraction-rules",
            requiredSections = setOf("cloud-backup", "device-transfer"),
            copiesPerPath = 2,
        )
        verifyBackupXmlDump(
            legacy,
            root = "full-backup-content",
            requiredSections = emptySet(),
            copiesPerPath = 1,
        )
    }

    private fun verifyBackupXmlDump(
        output: String,
        root: String,
        requiredSections: Set<String>,
        copiesPerPath: Int,
    ) {
        val roots = mutableListOf<DumpElement>()
        val stack = mutableListOf<DumpElement>()
        output.lineSequence().forEach { line ->
            val element = Regex("^(\\s*)E: ([a-z0-9-]+)(?: \\(line=.*)?$").matchEntire(line)
            if (element != null) {
                val indent = element.groupValues[1].length
                while (stack.lastOrNull()?.indent?.let { it >= indent } == true) stack.removeLast()
                val node = DumpElement(indent, element.groupValues[2])
                stack.lastOrNull()?.children?.add(node) ?: roots.add(node)
                stack.add(node)
                return@forEach
            }
            val attribute = Regex(
                "^\\s*A: ([a-zA-Z0-9_]+)(?:\\([^)]*\\))?=\"([^\"]*)\"(?: .*|)$",
            ).matchEntire(line) ?: return@forEach
            val current = stack.lastOrNull()
                ?: throw IllegalArgumentException("final backup attribute has no element")
            if (current.attributes.put(attribute.groupValues[1], attribute.groupValues[2]) != null) {
                throw IllegalArgumentException("final backup attribute is duplicated")
            }
        }
        val documentRoot = roots.singleOrNull()?.takeIf { it.name == root }
            ?: throw IllegalArgumentException("final backup root is not exact")
        if (documentRoot.attributes.isNotEmpty()) {
            throw IllegalArgumentException("final backup root attributes are not exact")
        }
        val containers = if (requiredSections.isEmpty()) {
            if (copiesPerPath != 1) throw IllegalArgumentException("invalid final backup verifier contract")
            listOf(documentRoot)
        } else {
            if (copiesPerPath != requiredSections.size ||
                documentRoot.children.map { it.name }.toSet() != requiredSections ||
                documentRoot.children.size != requiredSections.size || documentRoot.attributes.isNotEmpty()
            ) {
                throw IllegalArgumentException("final backup sections are not exact")
            }
            documentRoot.children
        }
        containers.forEach { container ->
            val excludes = container.children
            if (container.attributes.isNotEmpty() || excludes.size != FINAL_DATABASE_PATHS.size ||
                excludes.any { it.name != "exclude" }
            ) {
                throw IllegalArgumentException("final backup exclusion count is not exact")
            }
            val paths = excludes.map { exclude ->
                if (exclude.children.isNotEmpty() || exclude.attributes.keys != setOf("domain", "path") ||
                    exclude.attributes["domain"] != FINAL_DATABASE_DOMAIN
                ) {
                    throw IllegalArgumentException("final backup exclusion shape is not exact")
                }
                exclude.attributes.getValue("path")
            }
            if (paths.toSet() != FINAL_DATABASE_PATHS.toSet() || paths.size != paths.toSet().size) {
                throw IllegalArgumentException("final backup exclusion paths are not exact")
            }
        }
    }

    private data class DumpElement(
        val indent: Int,
        val name: String,
        val attributes: MutableMap<String, String> = linkedMapOf(),
        val children: MutableList<DumpElement> = mutableListOf(),
    )

    private fun verifyFinalDex(zip: ZipFile) {
        val classEntries = linkedMapOf<String, ClassDef>()
        val allTypes = hashSetOf<String>()
        val facadeCalls = linkedMapOf<String, Int>()
        val uiAccessorDefinitions = UI_SYNTHESIS_ACCESSORS.associateWithTo(linkedMapOf()) {
            mutableListOf<Method>()
        }
        val uiAccessorDirectCalls = mutableListOf<MethodReference>()
        val expectedFacadeCalls = setOf("captureSuccessfulWatermark")
        val initializer = "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptV2Initializer;"
        val initializerSideEffects =
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptV2Initializer\$DeferredSystemSideEffects;"
        val runtime = "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptV2Runtime;"
        val runtimeParts =
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptV2Runtime\$Parts;"
        val productionParts =
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptV2Runtime\$ProductionParts;"
        val bootstrapActivation =
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptAndroidBootstrapOps\$ActivationContext;"
        var activationTokenCalls = 0
        var runtimePrepareCalls = 0
        val requiredClasses = setOf(
            "Lcom/kakao/talk/application/App;",
            "Ltv/l;",
            "Ltv/I;",
            "LYr/s0;",
            EXACT_DELTA_EXTENSION_MARKER,
            EXACT_DELTA_V2_CAPTURE_FACADE,
            EXACT_DELTA_V2_ENTRYPOINT,
            initializer,
            initializerSideEffects,
            runtime,
            productionParts,
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptAndroidBootstrapOps;",
            bootstrapActivation,
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptAndroidDurableStore;",
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptAndroidCounterJournal;",
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptAndroidPendingBatchJournal;",
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptAndroidMaintenanceWorker;",
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptDurableStream;",
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptPendingBatchSink;",
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptCaptureCoordinator;",
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptV2CaptureRuntime;",
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptBootIdReader;",
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptSourceEpochMarker;",
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptV2OutboxServer;",
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptProtocolV2;",
        )
        zip.entries().asSequence()
            .filter { it.name.matches(Regex("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) }
            .sortedBy { entry ->
                entry.name.removePrefix("classes").removeSuffix(".dex").ifEmpty { "1" }.toInt()
            }
            .forEach { entry ->
                val dex = DexBackedDexFile.fromInputStream(
                    Opcodes.getDefault(),
                    BufferedInputStream(zip.getInputStream(entry)),
                )
                dex.classes.forEach { classDef ->
                    if (!allTypes.add(classDef.type)) {
                        throw IllegalArgumentException("duplicate final DEX class definition")
                    }
                    if (classDef.type.startsWith(
                            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptPersistence",
                        )
                    ) {
                        throw IllegalArgumentException("legacy read-receipt persistence is present in final APK")
                    }
                    if (classDef.type in requiredClasses) classEntries[classDef.type] = classDef
                    classDef.methods.forEach { method ->
                        uiAccessorDefinitions[method.name]?.add(method)
                        if (method.name == FORBIDDEN_LEGACY_CHAT_ID_ACCESSOR) {
                            throw IllegalArgumentException("legacy chat id accessor is present in final APK")
                        }
                        method.implementation?.instructions?.forEach { instruction ->
                            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                            reference?.takeIf { it.name in UI_SYNTHESIS_ACCESSORS }
                                ?.let(uiAccessorDirectCalls::add)
                            if (reference?.definingClass == EXACT_DELTA_V2_CAPTURE_FACADE &&
                                reference.name in expectedFacadeCalls
                            ) {
                                facadeCalls[reference.name] = facadeCalls.getOrDefault(reference.name, 0) + 1
                            }
                            if (reference?.definingClass == bootstrapActivation && reference.name == "token" &&
                                reference.parameterTypes.isEmpty() && reference.returnType == "[B"
                            ) {
                                activationTokenCalls++
                            }
                            if (reference?.definingClass == runtime && reference.name == "prepare" &&
                                reference.parameterTypes == listOf(
                                    "[B",
                                    "Ljava/lang/String;",
                                    "Ljava/lang/String;",
                                    "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptFileOps;",
                                    "I",
                                ) && reference.returnType == runtime
                            ) {
                                runtimePrepareCalls++
                            }
                        }
                    }
                }
                dex.stringReferences.asSequence().map { it.string }.forEach { value ->
                    if (value.startsWith("DROP TABLE IF EXISTS read_receipt_") ||
                        value == FORBIDDEN_LEGACY_CHAT_ID_ACCESSOR
                    ) {
                        throw IllegalArgumentException("forbidden legacy read-receipt DEX string is present")
                    }
                }
            }
        if (facadeCalls != expectedFacadeCalls.associateWith { 1 }) {
            throw IllegalArgumentException("final capture facade call cardinality is not exact")
        }
        val uiIsolationEvidence = verifyUiSynthesisIsolation(
            uiAccessorDefinitions,
            uiAccessorDirectCalls,
        )
        if (classEntries.keys != requiredClasses) {
            throw IllegalArgumentException("final v2 extension runtime owner set is incomplete")
        }
        if ("${initializer.dropLast(1)}\$NoopActivation;" in allTypes ||
            activationTokenCalls != 1 || runtimePrepareCalls != 1
        ) {
            throw IllegalArgumentException("final v2 activation token handoff is not exact")
        }
        try {
            verifyReadReceiptV2ExactDeltaFinalDexShapes(classEntries::get)
        } catch (exception: PatchException) {
            throw IllegalArgumentException(exception.message ?: "final exact-delta DEX verification failed")
        }
        val entrypoint = classEntries.getValue(EXACT_DELTA_V2_ENTRYPOINT).methods.singleOrNull { method ->
            method.name == "initialize" && method.parameterTypes == listOf("Landroid/content/Context;") &&
                method.returnType == "V"
        } ?: throw IllegalArgumentException("final v2 entrypoint is missing")
        val initializerCalls = entrypoint.implementation?.instructions?.count { instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            reference?.definingClass == initializer && reference.name == "initialize" &&
                reference.parameterTypes == listOf("Landroid/content/Context;") && reference.returnType == "Z"
        } ?: 0
        if (initializerCalls != 1) {
            throw IllegalArgumentException("final v2 entrypoint bootstrap call is not exact")
        }
        verifyProductionRuntimeWiring(classEntries, runtime, runtimeParts, productionParts)
        val oldMarker = classEntries[EXACT_DELTA_EXTENSION_MARKER]?.methods?.singleOrNull { method ->
            method.name == "isPatchIncluded" && method.parameterTypes.isEmpty() && method.returnType == "Z"
        } ?: throw IllegalArgumentException("required patched-app spine marker is missing from final APK")
        val oldMarkerInstructions = oldMarker.implementation?.instructions?.toList()
            ?: throw IllegalArgumentException("required patched-app spine marker has no body")
        if (oldMarker.implementation?.registerCount != 1 || oldMarkerInstructions.map { it.opcode } != listOf(
                Opcode.CONST_4,
                Opcode.RETURN,
                Opcode.CONST_4,
                Opcode.RETURN,
            ) ||
            (oldMarkerInstructions[0] as? NarrowLiteralInstruction)?.narrowLiteral != 1 ||
            (oldMarkerInstructions[2] as? NarrowLiteralInstruction)?.narrowLiteral != 0
        ) {
            throw IllegalArgumentException("required patched-app spine marker is not enabled")
        }
        println(uiIsolationEvidence.redactedSummary())
    }

    private fun verifyUiSynthesisIsolation(
        definitions: Map<String, List<Method>>,
        directCalls: List<MethodReference>,
    ): UiSynthesisIsolationEvidence {
        val sender = definitions.getValue(UI_SENDER_ID_ACCESSOR).singleOrNull()
            ?: throw IllegalArgumentException("final sender UI accessor cardinality is not exact")
        val currentUser = definitions.getValue(UI_CURRENT_USER_ID_ACCESSOR).singleOrNull()
            ?: throw IllegalArgumentException("final current-user UI accessor cardinality is not exact")
        verifyUiAccessorContract(sender, UI_SENDER_ID_ACCESSOR)
        verifyUiAccessorContract(currentUser, UI_CURRENT_USER_ID_ACCESSOR)
        verifySenderUiAccessor(sender)
        verifyCurrentUserUiAccessor(currentUser)
        if (directCalls.isNotEmpty()) {
            throw IllegalArgumentException("final UI synthesis accessors gained a direct DEX caller")
        }
        return UiSynthesisIsolationEvidence(
            senderDefinitions = definitions.getValue(UI_SENDER_ID_ACCESSOR).size,
            currentUserDefinitions = definitions.getValue(UI_CURRENT_USER_ID_ACCESSOR).size,
            directDexCalls = directCalls.size,
        )
    }

    private fun verifyUiAccessorContract(method: Method, expectedName: String) {
        val requiredFlags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        if (method.name != expectedName || method.parameterTypes.isNotEmpty() || method.returnType != "J" ||
            method.accessFlags and requiredFlags != requiredFlags ||
            method.accessFlags and AccessFlags.STATIC.value != 0
        ) {
            throw IllegalArgumentException("final UI synthesis accessor contract is not exact")
        }
        val forbiddenCall = method.implementation?.instructions?.any { instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            reference?.definingClass == EXACT_DELTA_V2_CAPTURE_FACADE || reference.isCaptureMutationHook()
        } != false
        if (forbiddenCall) {
            throw IllegalArgumentException("final UI synthesis accessor reaches the capture mutation path")
        }
    }

    private fun verifySenderUiAccessor(method: Method) {
        val instructions = method.implementation?.instructions?.toList()
            ?: throw IllegalArgumentException("final sender UI accessor has no body")
        val field = (instructions.getOrNull(0) as? ReferenceInstruction)?.reference as? FieldReference
        val fieldGet = instructions.getOrNull(0) as? TwoRegisterInstruction
        if (method.implementation?.registerCount != 3 || instructions.map { it.opcode } != listOf(
                Opcode.IGET_WIDE,
                Opcode.RETURN_WIDE,
            ) || field?.definingClass != method.definingClass || field.type != "J" ||
            fieldGet?.registerA != 0 || fieldGet.registerB != 2 ||
            (instructions[1] as? OneRegisterInstruction)?.registerA != 0
        ) {
            throw IllegalArgumentException("final sender UI accessor body is not exact")
        }
    }

    private fun verifyCurrentUserUiAccessor(method: Method) {
        val instructions = method.implementation?.instructions?.toList()
            ?: throw IllegalArgumentException("final current-user UI accessor has no body")
        val singleton = (instructions.getOrNull(0) as? ReferenceInstruction)?.reference as? FieldReference
            ?: throw IllegalArgumentException("final current-user UI singleton is missing")
        val userId = (instructions.getOrNull(1) as? ReferenceInstruction)?.reference as? MethodReference
            ?: throw IllegalArgumentException("final current-user ID getter is missing")
        if (method.implementation?.registerCount != 3 || instructions.map { it.opcode } != listOf(
                Opcode.SGET_OBJECT,
                Opcode.INVOKE_VIRTUAL,
                Opcode.MOVE_RESULT_WIDE,
                Opcode.RETURN_WIDE,
            ) || singleton.definingClass != userId.definingClass || singleton.type != userId.definingClass ||
            userId.name != "getUserId" || userId.parameterTypes.isNotEmpty() || userId.returnType != "J" ||
            (instructions[2] as? OneRegisterInstruction)?.registerA != 0 ||
            (instructions[3] as? OneRegisterInstruction)?.registerA != 0
        ) {
            throw IllegalArgumentException("final current-user UI accessor body is not exact")
        }
    }

    private fun MethodReference?.isCaptureMutationHook(): Boolean = when {
        this == null -> false
        definingClass == "Ltv/l;" && name == "I" &&
            parameterTypes == listOf("J", "J") && returnType == "Z" -> true
        definingClass == "LYr/s0;" && name == "l" &&
            parameterTypes == listOf("Ljava/lang/String;", "Ljava/lang/String;") && returnType == "V" -> true
        definingClass == "LYr/s0;" && name == "b" && parameterTypes.isEmpty() &&
            returnType == "Ljava/lang/Boolean;" -> true
        else -> false
    }

    private data class UiSynthesisIsolationEvidence(
        val senderDefinitions: Int,
        val currentUserDefinitions: Int,
        val directDexCalls: Int,
    ) {
        fun redactedSummary(): String =
            "CAP-011 final DEX UI isolation passed: " +
                "senderAccessorDefs=$senderDefinitions currentUserAccessorDefs=$currentUserDefinitions " +
                "directDexCalls=$directDexCalls accessorToCaptureEdges=0"
    }

    private fun verifyProductionRuntimeWiring(
        classes: Map<String, ClassDef>,
        runtime: String,
        runtimeParts: String,
        productionParts: String,
    ) {
        val start = classes.getValue(runtime).methods.singleOrNull { method ->
            method.name == "start" && method.parameterTypes.isEmpty() && method.returnType == "V"
        } ?: throw IllegalArgumentException("final v2 runtime start owner is missing")
        val orderedStarts = start.implementation?.instructions?.mapNotNull { instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            reference?.takeIf { it.definingClass == runtimeParts }?.name
        }?.filter { it in setOf("startOutbox", "activateCapture") }
            ?: emptyList()
        if (orderedStarts != listOf("startOutbox", "activateCapture")) {
            throw IllegalArgumentException("final v2 runtime activation order is not exact")
        }

        val create = classes.getValue(productionParts).methods.singleOrNull { method ->
            method.name == "create" && method.parameterTypes == listOf(
                "[B",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptFileOps;",
                "I",
            ) && method.returnType == productionParts
        } ?: throw IllegalArgumentException("final v2 production composition owner is missing")
        val calls = create.implementation?.instructions?.mapNotNull { instruction ->
            (instruction as? ReferenceInstruction)?.reference as? MethodReference
        } ?: emptyList()
        val requiredCalls = setOf(
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptAndroidDurableStore;-><init>",
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptAndroidCounterJournal;-><init>",
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptAndroidPendingBatchJournal;-><init>",
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptAndroidMaintenanceWorker;-><init>",
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptDurableStream;-><init>",
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptPendingBatchSink;-><init>",
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptCaptureCoordinator;-><init>",
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptV2CaptureRuntime;->factory",
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptV2OutboxServer;->android",
        )
        val observed = calls.mapTo(linkedSetOf()) { "${it.definingClass}->${it.name}" }
        if (!observed.containsAll(requiredCalls)) {
            throw IllegalArgumentException("final v2 production composition is incomplete")
        }
        val durableConstructor = calls.singleOrNull { reference ->
            reference.definingClass ==
                "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptDurableStream;" &&
                reference.name == "<init>" && reference.parameterTypes.size == 8
        }
        val bootIdRead = calls.filter { reference ->
            reference.definingClass ==
                "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptBootIdReader;" &&
                reference.name == "read"
        }.singleOrNull()?.takeIf { reference ->
            reference.parameterTypes == listOf(
                    "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptFileOps;",
                ) && reference.returnType == "Ljava/util/UUID;"
        }
        val retainedBootIdRead = classes.getValue(
            "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptBootIdReader;",
        ).methods.filter { method -> method.name == "read" }.singleOrNull()?.takeIf { method ->
            method.parameterTypes == listOf(
                "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptFileOps;",
            ) && method.returnType == "Ljava/util/UUID;"
        }
        if (durableConstructor == null) {
            throw IllegalArgumentException("final v2 durable stream is not using the journaled constructor")
        }
        if (bootIdRead == null || retainedBootIdRead == null) {
            throw IllegalArgumentException("final v2 boot ID reader retention or direct call is not exact")
        }
    }

    private fun selfTest() {
        val classLoader = ReadReceiptV2NativeResourceVerifier::class.java.classLoader
        val extensionTypes = classLoader.getResourceAsStream(V2_EXTENSION_RESOURCE)?.use { input ->
            DexBackedDexFile.fromInputStream(
                Opcodes.getDefault(),
                BufferedInputStream(input),
            ).classes.mapTo(linkedSetOf()) { it.type }
        } ?: throw IllegalArgumentException("missing isolated v2 extension resource")
        verifyExtensionTypes(extensionTypes)
        try {
            verifyExtensionTypes(extensionTypes + "Lkotlin/jvm/internal/Intrinsics;")
            error("non-v2 extension class was accepted")
        } catch (_: IllegalArgumentException) {
            Unit
        }
        val resources = NATIVE_ABIS.keys.associateWith { abi ->
            val path = "$NATIVE_RESOURCE_ROOT/$abi/$NATIVE_LIBRARY_NAME"
            classLoader.getResourceAsStream(path)?.use { it.readAllBytes() }
                ?: throw IllegalArgumentException("missing packaged native resource")
        }
        resources.forEach { (abi, bytes) ->
            verifyReadReceiptNativeResource(ReadReceiptNativeResource(abi, bytes))
        }
        NATIVE_ABIS.keys.forEach { abi ->
            val tampered = resources.getValue(abi).clone().also { it[it.lastIndex] = (it.last() + 1).toByte() }
            try {
                verifyReadReceiptNativeResource(ReadReceiptNativeResource(abi, tampered))
                error("tampered native resource was accepted")
            } catch (_: IllegalArgumentException) {
                Unit
            }
        }
        try {
            verifyReadReceiptNativeResource(ReadReceiptNativeResource("x86", resources.values.first()))
            error("unsupported native ABI was accepted")
        } catch (_: IllegalArgumentException) {
            Unit
        }
        val syntheticApk = File("synthetic.apk")
        ReadReceiptV2ExactDeltaPreflight.requireNoVerifiedSigner(syntheticApk) { false }
        try {
            ReadReceiptV2ExactDeltaPreflight.requireNoVerifiedSigner(syntheticApk) { true }
            error("verified signer was accepted for an unsigned final APK")
        } catch (_: ExactDeltaPreflightException) {
            Unit
        }
        val modern = syntheticBackupDump(
            "data-extraction-rules",
            listOf("cloud-backup", "device-transfer"),
        )
        val legacy = syntheticBackupDump("full-backup-content", emptyList())
        verifyBackupXmlDump(modern, "data-extraction-rules", setOf("cloud-backup", "device-transfer"), 2)
        verifyBackupXmlDump(legacy, "full-backup-content", emptySet(), 1)
        try {
            verifyBackupXmlDump(
                modern.replaceFirst("    E: device-transfer (line=6)", "    E: unknown (line=6)"),
                "data-extraction-rules",
                setOf("cloud-backup", "device-transfer"),
                2,
            )
            error("tampered backup dump was accepted")
        } catch (_: IllegalArgumentException) {
            Unit
        }
        println(
            "RRV2-NATIVE-UNIT-001 native/final verifier passed: " +
                "extensionClasses=${extensionTypes.size} extensionTamper=1 " +
                "nativePositive=2 nativeTamper=2 unsupported=1 unsignedSigner=1/1reject " +
                "backupPositive=2 backupTamper=1",
        )
    }

    private fun verifyExtensionTypes(types: Set<String>) {
        val required = setOf(
            EXACT_DELTA_V2_CAPTURE_FACADE,
            EXACT_DELTA_V2_ENTRYPOINT,
            "${V2_EXTENSION_PREFIX}ReadReceiptV2Initializer;",
            "${V2_EXTENSION_PREFIX}ReadReceiptV2Runtime;",
            "${V2_EXTENSION_PREFIX}ReadReceiptDurableStream;",
            "${V2_EXTENSION_PREFIX}ReadReceiptV2OutboxServer;",
        )
        if (!types.containsAll(required) || types.any { type ->
                !type.startsWith(V2_EXTENSION_PREFIX) && type != V2_EXTENSION_R_CLASS
            }
        ) {
            throw IllegalArgumentException("isolated v2 extension class set is not exact")
        }
    }

    private fun syntheticBackupDump(root: String, sections: List<String>): String {
        var line = 1
        fun exclusions(indent: String) = FINAL_DATABASE_PATHS.joinToString("\n") { path ->
            "${indent}E: exclude (line=${line++})\n" +
                "${indent}  A: domain=\"database\" (Raw: \"database\")\n" +
                "${indent}  A: path=\"$path\" (Raw: \"$path\")"
        }
        return buildString {
            append("E: $root (line=${line++})\n")
            if (sections.isEmpty()) {
                append(exclusions("    "))
            } else {
                sections.forEachIndexed { index, section ->
                    if (index > 0) append('\n')
                    append("    E: $section (line=${line++})\n")
                    append(exclusions("        "))
                }
            }
        }
    }
}
