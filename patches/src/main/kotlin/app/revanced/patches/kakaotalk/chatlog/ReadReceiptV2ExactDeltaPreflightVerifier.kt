package app.revanced.patches.kakaotalk.chatlog

import java.io.File

object ReadReceiptV2ExactDeltaPreflightVerifier {
    @JvmStatic
    fun main(args: Array<String>) {
        val input = args.firstOrNull()?.let(::File)
            ?: throw IllegalArgumentException("usage: ReadReceiptV2ExactDeltaPreflightVerifier <exact-input.apk> [legacy-evidence.apk]")
        val evidence = ReadReceiptV2ExactDeltaPreflight.verifyBeforePatcher(input)
        val manifestDump = ReadReceiptV2ExactDeltaPreflight.dumpXmlTree(input, "AndroidManifest.xml")
        val localeConfigId = Regex("(?m)^        A: .*:localeConfig\\([^)]*\\)=@(0x[0-9a-f]+)$")
            .find(manifestDump)?.groupValues?.get(1) ?: error("manifest resource reference smoke failed")
        val resourceTable = ReadReceiptV2ExactDeltaPreflight.dumpResourceTable(input)
        check(
            Regex("(?m)^    resource ${Regex.escape(localeConfigId)} xml/locales_config$")
                .containsMatchIn(resourceTable),
        )

        rejects("hash") { evidence.copy(sha256 = "0".repeat(64)) }
        rejects("known legacy artifact hash") { evidence.copy(sha256 = FORBIDDEN_LEGACY_ARTIFACT_SHA256) }
        rejects("package") { evidence.copy(packageName = "com.kakao.talk.changed") }
        rejects("version name") { evidence.copy(versionName = "26.6.1") }
        rejects("version code") { evidence.copy(versionCode = "29260601") }
        rejects("application") { evidence.copy(applicationName = "com.kakao.talk.Other") }
        rejects("extractNativeLibs") { evidence.copy(extractNativeLibs = "true") }
        rejects("signer") { evidence.copy(signerSha256 = setOf("0".repeat(64))) }
        rejects("missing old extension marker") {
            evidence.copy(classEntries = evidence.classEntries - EXACT_DELTA_EXTENSION_MARKER)
        }
        rejects("legacy descriptor") {
            evidence.copy(
                legacyDescriptors = setOf(
                    "Lapp/revanced/extension/kakaotalk/chatlog/readreceipt/ReadReceiptPersistence;",
                ),
            )
        }
        rejects("v2 descriptor") {
            evidence.copy(v2Descriptors = setOf(EXACT_DELTA_V2_ENTRYPOINT))
        }
        rejects("v2 invoke") {
            evidence.copy(forbiddenDexStrings = setOf(EXACT_DELTA_V2_ENTRYPOINT))
        }
        rejects("synthetic v2 marker") {
            evidence.copy(forbiddenDexStrings = setOf("Ltv/l;->$EXACT_DELTA_V2_SYNTHETIC_MARKER()V"))
        }
        rejects("legacy chat id accessor") {
            evidence.copy(forbiddenDexStrings = setOf(FORBIDDEN_LEGACY_CHAT_ID_ACCESSOR))
        }
        rejects("native double apply") {
            evidence.copy(entryNames = evidence.entryNames + "lib/arm64-v8a/libreadreceiptfs.so")
        }
        rejects("backup double apply") {
            evidence.copy(entryNames = evidence.entryNames + "res/xml/revanced_read_receipt_full_backup_content.xml")
        }

        args.getOrNull(1)?.let(::File)?.let { legacy ->
            try {
                ReadReceiptV2ExactDeltaPreflight.verifyBeforePatcher(legacy)
                error("legacy evidence APK was accepted")
            } catch (_: ExactDeltaPreflightException) {
                Unit
            }
        }

        println(
            "RRV2-PRE-001 exact-delta preflight passed: " +
                "sha256=${evidence.sha256} package=${evidence.packageName} " +
                "version=${evidence.versionName}(${evidence.versionCode}) signer=1 dexShapes=10 resourceTable=1",
        )
    }

    private fun rejects(label: String, mutate: () -> ExactDeltaArtifactEvidence) {
        try {
            ReadReceiptV2ExactDeltaPreflight.verifyEvidence(mutate())
            error("negative preflight was accepted: $label")
        } catch (_: ExactDeltaPreflightException) {
            Unit
        }
    }
}
