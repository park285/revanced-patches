package app.revanced.patches.kakaotalk.chatlog

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val EXACT_DELTA_V2_CAPTURE_FACADE =
    "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptCaptureFacade;"

internal val READ_RECEIPT_V2_EXACT_DELTA_COMPATIBILITY = Compatibility(
    name = "KakaoTalk 26.6.0 patched exact delta",
    packageName = EXACT_DELTA_PACKAGE,
    apkFileType = ApkFileType.APK_REQUIRED,
    signatures = setOf(EXACT_DELTA_INPUT_SIGNER_SHA256),
    targets = listOf(AppTarget(EXACT_DELTA_VERSION_NAME, EXACT_DELTA_VERSION_CODE.toInt())),
)

internal data class ReadReceiptV2ExactDeltaHookScaffold(
    val watermarkWrapper: MutableMethod,
)

internal fun BytecodePatchContext.resolveReadReceiptV2ExactDeltaHookScaffold(): ReadReceiptV2ExactDeltaHookScaffold {
    val targets = ReadReceiptV2ExactDeltaPreflight.resolveTargets(::classDefByOrNull).also(
        ReadReceiptV2ExactDeltaPreflight::verifyTargetShapes,
    )
    val wrapper = mutableClassDefBy(targets.watermarkWrapper.definingClass).methods.single { candidate ->
        candidate.name == targets.watermarkWrapper.name &&
            candidate.parameterTypes == targets.watermarkWrapper.parameterTypes &&
            candidate.returnType == targets.watermarkWrapper.returnType
    }
    return ReadReceiptV2ExactDeltaHookScaffold(wrapper)
}

internal fun BytecodePatchContext.injectReadReceiptV2ApplicationEntryPoint() {
    val targets = verifyExactDeltaBytecode()
    val method = mutableClassDefBy(targets.applicationOnCreate.definingClass).methods.single { candidate ->
        candidate.name == "onCreate" && candidate.parameterTypes.isEmpty() && candidate.returnType == "V"
    }
    injectReadReceiptV2ApplicationEntryPoint(method)
}

internal fun injectReadReceiptV2ApplicationEntryPoint(method: MutableMethod) {
    if (method.instructions.any { instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            reference?.definingClass == EXACT_DELTA_V2_ENTRYPOINT && reference.name == "initialize"
        }
    ) {
        throw PatchException("v2 exact-delta application entrypoint is already present")
    }
    method.addInstructions(
        1,
        "invoke-static {p0}, $EXACT_DELTA_V2_ENTRYPOINT->initialize(Landroid/content/Context;)V",
    )
}

internal fun BytecodePatchContext.injectReadReceiptV2SyntheticMarker() {
    injectReadReceiptV2SyntheticMarker(mutableClassDefBy("Ltv/l;"))
}

internal fun injectReadReceiptV2SyntheticMarker(target: MutableClass) {
    if (target.methods.any { method -> method.name == EXACT_DELTA_V2_SYNTHETIC_MARKER }) {
        throw PatchException("v2 exact-delta synthetic marker is already present")
    }
    target.methods.add(
        ImmutableMethod(
            target.type,
            EXACT_DELTA_V2_SYNTHETIC_MARKER,
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            emptySet(),
            emptySet(),
            MutableMethodImplementation(1),
        ).toMutable().apply { addInstructions(0, "return-void") },
    )
}

internal fun BytecodePatchContext.injectReadReceiptV2ExactDeltaCaptureHooks(
    scaffold: ReadReceiptV2ExactDeltaHookScaffold,
) {
    injectReadReceiptV2ExactDeltaCaptureHooks(
        mutableClassDefBy(scaffold.watermarkWrapper.definingClass),
        scaffold,
    )
}

internal fun injectReadReceiptV2ExactDeltaCaptureHooks(
    watermarkOwner: MutableClass,
    scaffold: ReadReceiptV2ExactDeltaHookScaffold,
) {
    val current = watermarkOwner.methods.singleOrNull { method ->
        method.name == scaffold.watermarkWrapper.name &&
            method.parameterTypes == scaffold.watermarkWrapper.parameterTypes &&
            method.returnType == scaffold.watermarkWrapper.returnType
    }
    if (current == null || watermarkOwner.type != "Ltv/l;" ||
        current.instructions.any { instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            reference?.definingClass == EXACT_DELTA_V2_CAPTURE_FACADE
        }
    ) {
        throw PatchException("v2 exact-delta capture hook is already or partially present")
    }

    watermarkOwner.replaceExactDeltaMethod(
        current,
        registerCount = 12,
        instructions = """
            iget-object v0, p0, Ltv/l;->e:Ltv/I;
            invoke-virtual {v0, p1, p2, p3, p4}, Ltv/I;->j(JJ)Z
            move-result v0
            if-eqz v0, :revanced_rrv2_watermark_return
            iget-wide v1, p0, Ltv/l;->a:J
            move-wide v3, p1
            move-wide v5, p3
            invoke-static/range {v1 .. v6}, $EXACT_DELTA_V2_CAPTURE_FACADE->captureSuccessfulWatermark(JJJ)V
            :revanced_rrv2_watermark_return
            return v0
        """.trimIndent(),
    )
}

private fun MutableClass.replaceExactDeltaMethod(
    original: MutableMethod,
    registerCount: Int,
    instructions: String,
): MutableMethod {
    val replacement = ImmutableMethod(
        original.definingClass,
        original.name,
        original.parameters,
        original.returnType,
        original.accessFlags,
        original.annotations,
        original.hiddenApiRestrictions,
        MutableMethodImplementation(registerCount),
    ).toMutable().apply {
        addInstructionsWithLabels(0, instructions)
    }
    if (!methods.remove(original) || !methods.add(replacement)) {
        throw PatchException("failed to replace exact-delta method ${original.definingClass}->${original.name}")
    }
    return replacement
}

internal fun verifyReadReceiptV2ExactDeltaFinalDexShapes(classLookup: (String) -> ClassDef?) {
    fun classDef(type: String) = classLookup(type) ?: throw PatchException("missing final exact-delta class $type")
    fun method(type: String, name: String, parameters: List<String>, returnType: String): Method =
        classDef(type).methods.singleOrNull { candidate ->
            candidate.name == name && candidate.parameterTypes == parameters && candidate.returnType == returnType
        } ?: throw PatchException("missing or ambiguous final exact-delta method $type->$name")

    val onCreate = method("Lcom/kakao/talk/application/App;", "onCreate", emptyList(), "V")
    val onCreateInstructions = onCreate.implementation?.instructions?.toList()
        ?: throw PatchException("missing final application entrypoint body")
    val entrypoints = onCreateInstructions.withIndex().filter { (_, instruction) ->
        instruction.isMethodCall(EXACT_DELTA_V2_ENTRYPOINT, "initialize", listOf("Landroid/content/Context;"), "V")
    }
    if (onCreate.implementation?.registerCount != 3 || entrypoints.singleOrNull()?.index != 1) {
        throw PatchException("final exact-delta application entrypoint is not exact")
    }

    val watermark = method("Ltv/l;", "I", listOf("J", "J"), "Z")
    val instructions = watermark.implementation?.instructions?.toList()
        ?: throw PatchException("missing final watermark hook body")
    if (watermark.implementation?.registerCount != 12 || instructions.map { it.opcode } != listOf(
            Opcode.IGET_OBJECT,
            Opcode.INVOKE_VIRTUAL,
            Opcode.MOVE_RESULT,
            Opcode.IF_EQZ,
            Opcode.IGET_WIDE,
            Opcode.MOVE_WIDE,
            Opcode.MOVE_WIDE,
            Opcode.INVOKE_STATIC_RANGE,
            Opcode.RETURN,
        )
    ) {
        throw PatchException("final watermark hook shape is not exact")
    }
    val managerField = (instructions[0] as? ReferenceInstruction)?.reference as? FieldReference
    val managerGet = instructions[0] as? TwoRegisterInstruction
    val put = instructions[1] as? FiveRegisterInstruction
    val chatIdField = (instructions[4] as? ReferenceInstruction)?.reference as? FieldReference
    val chatIdGet = instructions[4] as? TwoRegisterInstruction
    val userMove = instructions[5] as? TwoRegisterInstruction
    val watermarkMove = instructions[6] as? TwoRegisterInstruction
    val capture = instructions[7] as? RegisterRangeInstruction
    if (managerField?.definingClass != "Ltv/l;" || managerField.name != "e" || managerField.type != "Ltv/I;" ||
        managerGet?.registerA != 0 || managerGet.registerB != 7 ||
        !instructions[1].isMethodCall("Ltv/I;", "j", listOf("J", "J"), "Z") ||
        put?.registerCount != 5 || listOf(put.registerC, put.registerD, put.registerE, put.registerF, put.registerG) !=
        listOf(0, 8, 9, 10, 11) ||
        (instructions[2] as? OneRegisterInstruction)?.registerA != 0 ||
        (instructions[3] as? OneRegisterInstruction)?.registerA != 0 ||
        chatIdField?.definingClass != "Ltv/l;" || chatIdField.name != "a" || chatIdField.type != "J" ||
        chatIdGet?.registerA != 1 || chatIdGet.registerB != 7 ||
        userMove?.registerA != 3 || userMove.registerB != 8 ||
        watermarkMove?.registerA != 5 || watermarkMove.registerB != 10 ||
        !instructions[7].isMethodCall(EXACT_DELTA_V2_CAPTURE_FACADE, "captureSuccessfulWatermark", listOf("J", "J", "J"), "V") ||
        capture?.startRegister != 1 || capture.registerCount != 6 ||
        (instructions[8] as? OneRegisterInstruction)?.registerA != 0
    ) {
        throw PatchException("final watermark hook registers are not exact")
    }

    listOf("Ltv/I;", "LYr/s0;").forEach { type ->
        if (classDef(type).methods.any { method ->
                method.name.startsWith("revanced_read_receipt_v2_") ||
                    method.implementation?.instructions?.any { instruction ->
                        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                        reference?.definingClass == EXACT_DELTA_V2_CAPTURE_FACADE
                    } == true
            }
        ) {
            throw PatchException("final exact-delta contains an unexpected compatibility hook")
        }
    }

    val marker = method("Ltv/l;", EXACT_DELTA_V2_SYNTHETIC_MARKER, emptyList(), "V")
    val markerInstructions = marker.implementation?.instructions?.toList()
        ?: throw PatchException("missing final synthetic marker body")
    if (marker.implementation?.registerCount != 1 || markerInstructions.map { it.opcode } != listOf(Opcode.RETURN_VOID) ||
        marker.accessFlags and (AccessFlags.PUBLIC.value or AccessFlags.FINAL.value) !=
        (AccessFlags.PUBLIC.value or AccessFlags.FINAL.value)
    ) {
        throw PatchException("final synthetic marker is not exact")
    }
    classDef(EXACT_DELTA_V2_CAPTURE_FACADE)
    classDef(EXACT_DELTA_V2_ENTRYPOINT)
}

private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.isMethodCall(
    definingClass: String,
    name: String,
    parameters: List<String>,
    returnType: String,
): Boolean {
    val reference = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return reference.definingClass == definingClass && reference.name == name &&
        reference.parameterTypes == parameters && reference.returnType == returnType
}

internal val readReceiptV2ExactDeltaApplicationEntryPointPatch = bytecodePatch {
    dependsOn(readReceiptV2ExactDeltaBytecodePreflightPatch)
    execute { injectReadReceiptV2ApplicationEntryPoint() }
}

internal fun buildReadReceiptWatermarkV2ExactDeltaPatch(
    captureHooks: BytecodePatchContext.(ReadReceiptV2ExactDeltaHookScaffold) -> Unit,
): BytecodePatch = bytecodePatch(
    name = "Read receipt watermark v2 exact delta",
    description = "Adds the v2 durable read-receipt stream to the pinned patched KakaoTalk 26.6.0 APK.",
    default = false,
) {
    compatibleWith(READ_RECEIPT_V2_EXACT_DELTA_COMPATIBILITY)
    extendWith("extensions/kakaotalk/read-receipt-v2.mpe")
    dependsOn(
        readReceiptBackupRulesPatch,
        readReceiptV2NativeResourcesPatch,
        readReceiptV2ExactDeltaApplicationEntryPointPatch,
    )
    execute {
        val scaffold = resolveReadReceiptV2ExactDeltaHookScaffold()
        captureHooks(scaffold)
        injectReadReceiptV2SyntheticMarker()
        verifyReadReceiptV2ExactDeltaFinalDexShapes(::classDefByOrNull)
    }
}

internal object ReadReceiptWatermarkV2ExactDeltaCarrier {
    val patch = buildReadReceiptWatermarkV2ExactDeltaPatch { scaffold ->
        injectReadReceiptV2ExactDeltaCaptureHooks(scaffold)
    }
}
