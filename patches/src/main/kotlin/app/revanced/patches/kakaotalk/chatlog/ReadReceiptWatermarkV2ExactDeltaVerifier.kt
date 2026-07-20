package app.revanced.patches.kakaotalk.chatlog

import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipFile

object ReadReceiptWatermarkV2ExactDeltaVerifier {
    @JvmStatic
    fun main(args: Array<String>) {
        val input = args.singleOrNull()?.let(::File)
            ?: throw IllegalArgumentException("usage: ReadReceiptWatermarkV2ExactDeltaVerifier <exact-input.apk>")
        ReadReceiptV2ExactDeltaPreflight.verifyBeforePatcher(input)

        val classes = loadMutableClasses(
            input,
            mapOf(
                "Lcom/kakao/talk/application/App;" to "classes.dex",
                "Ltv/l;" to "classes.dex",
                "Ltv/I;" to "classes.dex",
                "LYr/s0;" to "classes37.dex",
            ),
        )
        val application = classes.getValue("Lcom/kakao/talk/application/App;")
        val watermarkOwner = classes.getValue("Ltv/l;")
        val scaffold = ReadReceiptV2ExactDeltaHookScaffold(
            watermarkOwner.exactMethod("I", listOf("J", "J"), "Z"),
        )

        injectReadReceiptV2ApplicationEntryPoint(application.exactMethod("onCreate", emptyList(), "V"))
        injectReadReceiptV2ExactDeltaCaptureHooks(watermarkOwner, scaffold)
        injectReadReceiptV2SyntheticMarker(watermarkOwner)
        verifyWithExtensionPlaceholders(classes)

        rejects("double apply") {
            injectReadReceiptV2ExactDeltaCaptureHooks(watermarkOwner, scaffold)
        }
        verifyWithExtensionPlaceholders(classes)

        val patched = watermarkOwner.exactMethod("I", listOf("J", "J"), "Z")
        val captureIndex = patched.instructions.indexOfFirst { instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            reference?.definingClass == EXACT_DELTA_V2_CAPTURE_FACADE &&
                reference.name == "captureSuccessfulWatermark"
        }
        check(captureIndex >= 0)
        patched.implementation!!.removeInstruction(captureIndex)
        rejects("missing direct capture hook") { verifyWithExtensionPlaceholders(classes) }

        println(
            "RRV2-HOOK-001 exact-delta hook verifier passed: " +
                "captureHooks=1 accessors=0 pendingBindings=0 doubleApply=1 tamper=1",
        )
    }

    private fun verifyWithExtensionPlaceholders(classes: Map<String, MutableClass>) {
        verifyReadReceiptV2ExactDeltaFinalDexShapes { type ->
            classes[type] ?: when (type) {
                EXACT_DELTA_V2_CAPTURE_FACADE, EXACT_DELTA_V2_ENTRYPOINT -> classes.getValue("Ltv/l;")
                else -> null
            }
        }
    }

    private fun MutableClass.exactMethod(
        name: String,
        parameters: List<String>,
        returnType: String,
    ): MutableMethod = methods.singleOrNull { method ->
        method.name == name && method.parameterTypes == parameters && method.returnType == returnType
    } ?: error("missing verifier method $type->$name")

    private fun loadMutableClasses(apk: File, expected: Map<String, String>): Map<String, MutableClass> =
        ZipFile(apk).use { zip ->
            expected.entries.groupBy({ it.value }, { it.key }).flatMap { (entryName, types) ->
                val entry = zip.getEntry(entryName) ?: error("missing verifier DEX $entryName")
                val dex = DexBackedDexFile.fromInputStream(
                    Opcodes.getDefault(),
                    BufferedInputStream(zip.getInputStream(entry)),
                )
                types.map { type ->
                    val classDef = dex.classes.singleOrNull { it.type == type }
                        ?: error("missing verifier class $type in $entryName")
                    type to MutableClass(classDef)
                }
            }.toMap()
        }

    private fun rejects(label: String, block: () -> Unit) {
        try {
            block()
            error("negative hook verification was accepted: $label")
        } catch (_: PatchException) {
            Unit
        }
    }
}
