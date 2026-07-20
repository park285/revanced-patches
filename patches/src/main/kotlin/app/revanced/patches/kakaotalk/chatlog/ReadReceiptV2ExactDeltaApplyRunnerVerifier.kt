package app.revanced.patches.kakaotalk.chatlog

import app.morphe.patcher.patch.Patch
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ReadReceiptV2ExactDeltaApplyRunnerVerifier {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Files.createTempDirectory("rrv2-runner-verifier-").toFile()
        try {
            val input = File(root, "input.apk").apply { writeBytes(byteArrayOf(1)) }
            val keyStore = File(root, "signing.bks").apply { writeBytes(byteArrayOf(2)) }
            val storePassword = File(root, "store-password").apply { writeText("store") }
            val keyPassword = File(root, "key-password").apply { writeText("key") }
            val extensionMpe = File(root, "read-receipt-v2.mpe").apply { writeBytes(byteArrayOf(3)) }
            val temporaryParent = File(root, "temporary-parent").apply { mkdirs() }
            val evidenceArgs = arrayOf(
                "--dependency-lane", "pinned_offline",
                "--extension-mpe", extensionMpe.path,
                "--revanced-head", "a".repeat(40),
                "--revanced-tree-sha256", "b".repeat(64),
                "--iris-head", "c".repeat(40),
                "--iris-tree-sha256", "d".repeat(64),
            )

            val unsigned = ReadReceiptV2ExactDeltaApplyRunner.parse(
                arrayOf(
                    "--input", input.path,
                    "--output", File(root, "output.unsigned.apk").path,
                    "--temp", temporaryParent.path,
                    "--unsigned",
                    *evidenceArgs,
                ),
            )
            ReadReceiptV2ExactDeltaApplyRunner.validate(unsigned)

            val signedArgs = arrayOf(
                "--input", input.path,
                "--output", File(root, "output.apk").path,
                "--temp", temporaryParent.path,
                "--keystore", keyStore.path,
                "--keystore-password-file", storePassword.path,
                "--key-alias", "release",
                "--key-password-file", keyPassword.path,
                "--signer-name", "read-receipt-v2",
                "--expected-signer-sha256", EXACT_DELTA_FINAL_SIGNER_SHA256,
                *evidenceArgs,
            )
            val signed = ReadReceiptV2ExactDeltaApplyRunner.parse(signedArgs)
            ReadReceiptV2ExactDeltaApplyRunner.validate(signed)
            ReadReceiptV2ExactDeltaPreflight.verifyFinalSignerIdentity(
                EXACT_DELTA_FINAL_SIGNER_SHA256,
                setOf(EXACT_DELTA_FINAL_SIGNER_SHA256),
            )

            val carrierClass = ReadReceiptWatermarkV2ExactDeltaCarrier::class.java
            check(
                carrierClass.fields.none { field ->
                    Patch::class.java.isAssignableFrom(field.type) && Modifier.isStatic(field.modifiers)
                },
            )
            check(
                carrierClass.methods.none { method ->
                    Patch::class.java.isAssignableFrom(method.returnType) && Modifier.isStatic(method.modifiers)
                },
            )
            check(
                ReadReceiptWatermarkV2ExactDeltaCarrier.patch.dependencies == setOf(
                    readReceiptBackupRulesPatch,
                    readReceiptV2NativeResourcesPatch,
                    readReceiptV2ExactDeltaApplicationEntryPointPatch,
                ),
            )

            val snapshotRoot = ReadReceiptV2ExactDeltaApplyRunner.createOwnedTemporaryRoot(temporaryParent)
            check(
                Files.getPosixFilePermissions(snapshotRoot.path) ==
                    PosixFilePermissions.fromString("rwx------"),
            )
            val snapshot = ReadReceiptV2ExactDeltaApplyRunner.createInputSnapshot(input, snapshotRoot)
            input.writeBytes(byteArrayOf(9))
            check(snapshot.readBytes().contentEquals(byteArrayOf(1)))
            check(ReadReceiptV2ExactDeltaApplyRunner.cleanupOwnedTemporaryRoot(snapshotRoot))

            rejectsVerification("preflight failure cleanup") {
                ReadReceiptV2ExactDeltaApplyRunner.main(
                    arrayOf(
                        "--input", input.path,
                        "--output", File(root, "preflight-failure.unsigned.apk").path,
                        "--temp", temporaryParent.path,
                        "--unsigned",
                    ),
                )
            }
            check(temporaryParent.listFiles().isNullOrEmpty())

            val publishRoot = ReadReceiptV2ExactDeltaApplyRunner.createOwnedTemporaryRoot(temporaryParent)
            val publishSource = publishRoot.path.resolve("artifact.apk")
            Files.write(publishSource, byteArrayOf(4, 5, 6))
            val published = File(root, "published.unsigned.apk").toPath()
            var unsignedVerifierCalls = 0
            ReadReceiptV2ExactDeltaApplyRunner.publishUnsignedArtifact(publishSource, published) { artifact ->
                unsignedVerifierCalls++
                check(artifact.readBytes().contentEquals(byteArrayOf(4, 5, 6)))
            }
            check(unsignedVerifierCalls == 2)
            check(!Files.exists(publishSource))
            check(Files.readAllBytes(published).contentEquals(byteArrayOf(4, 5, 6)))
            check(ReadReceiptV2ExactDeltaApplyRunner.cleanupOwnedTemporaryRoot(publishRoot))

            val receiptRoot = ReadReceiptV2ExactDeltaApplyRunner.createOwnedTemporaryRoot(temporaryParent)
            val receiptArtifact = receiptRoot.path.resolve("artifact.apk")
            writeApk(receiptArtifact.toFile(), "payload-a")
            val receiptOutput = File(root, "paired.unsigned.apk")
            val receiptConfig = unsigned.copy(output = receiptOutput)
            val expectedReceipt = ReadReceiptV2ExactDeltaApplyRunner.createApplyReceipt(
                receiptConfig,
                receiptArtifact,
                "unsigned",
                lineageId = "e".repeat(64),
            )
            check(
                expectedReceipt.outputPayloadSha256 ==
                    "4728ade9214feb8c2faa379adec81310623c2fe6f3cdcd48731b765a6bec418b",
            )
            val receiptStaging = receiptRoot.path.resolve("apply-receipt.properties")
            ReadReceiptV2ExactDeltaApplyRunner.writeApplyReceipt(receiptStaging, expectedReceipt)
            val publishedReceipt = ReadReceiptV2ExactDeltaApplyRunner.receiptPath(receiptOutput).toPath()
            ReadReceiptV2ExactDeltaApplyRunner.publishArtifactWithReceipt(
                receiptArtifact,
                receiptOutput.toPath(),
                receiptStaging,
                publishedReceipt,
                expectedReceipt,
            ) { artifact -> check(Files.size(artifact) > 0) }
            check(ReadReceiptV2ExactDeltaApplyRunner.canonicalPayloadSha256(receiptOutput.toPath()) ==
                expectedReceipt.outputPayloadSha256)
            check(Files.getPosixFilePermissions(publishedReceipt) == PosixFilePermissions.fromString("r--------"))
            ReadReceiptV2ExactDeltaApplyRunner.verifyApplyReceipt(
                publishedReceipt,
                receiptOutput.toPath(),
                expectedReceipt,
            )
            val tamperedLaneReceipt = receiptRoot.path.resolve("tampered-lane-receipt.properties")
            ReadReceiptV2ExactDeltaApplyRunner.writeApplyReceipt(
                tamperedLaneReceipt,
                expectedReceipt.copy(dependencyLane = "release"),
            )
            rejectsState("tampered dependency lane receipt") {
                ReadReceiptV2ExactDeltaApplyRunner.verifyApplyReceipt(
                    tamperedLaneReceipt,
                    receiptOutput.toPath(),
                    expectedReceipt,
                )
            }
            check(ReadReceiptV2ExactDeltaApplyRunner.cleanupOwnedTemporaryRoot(receiptRoot))

            val staleRoot = ReadReceiptV2ExactDeltaApplyRunner.createOwnedTemporaryRoot(temporaryParent)
            val staleArtifact = staleRoot.path.resolve("artifact.apk")
            writeApk(staleArtifact.toFile(), "payload-b")
            val staleOutput = File(root, "stale.unsigned.apk")
            val staleReceipt = ReadReceiptV2ExactDeltaApplyRunner.createApplyReceipt(
                unsigned.copy(output = staleOutput),
                staleArtifact,
                "unsigned",
                lineageId = "f".repeat(64),
            )
            val staleReceiptPath = staleRoot.path.resolve("apply-receipt.properties")
            ReadReceiptV2ExactDeltaApplyRunner.writeApplyReceipt(staleReceiptPath, staleReceipt)
            writeApk(staleArtifact.toFile(), "payload-c")
            rejectsState("stale artifact receipt") {
                ReadReceiptV2ExactDeltaApplyRunner.publishArtifactWithReceipt(
                    staleArtifact,
                    staleOutput.toPath(),
                    staleReceiptPath,
                    ReadReceiptV2ExactDeltaApplyRunner.receiptPath(staleOutput).toPath(),
                    staleReceipt,
                ) {}
            }
            check(!staleOutput.exists() && !ReadReceiptV2ExactDeltaApplyRunner.receiptPath(staleOutput).exists())
            check(ReadReceiptV2ExactDeltaApplyRunner.cleanupOwnedTemporaryRoot(staleRoot))

            val switchedReceiptRoot = ReadReceiptV2ExactDeltaApplyRunner.createOwnedTemporaryRoot(temporaryParent)
            val switchedArtifact = switchedReceiptRoot.path.resolve("artifact.apk")
            writeApk(switchedArtifact.toFile(), "payload-d")
            val switchedOutput = File(root, "switched.unsigned.apk")
            val switchedConfig = unsigned.copy(output = switchedOutput)
            val originalReceipt = ReadReceiptV2ExactDeltaApplyRunner.createApplyReceipt(
                switchedConfig,
                switchedArtifact,
                "unsigned",
                lineageId = "1".repeat(64),
            )
            val switchedReceiptPath = switchedReceiptRoot.path.resolve("apply-receipt.properties")
            ReadReceiptV2ExactDeltaApplyRunner.writeApplyReceipt(switchedReceiptPath, originalReceipt)
            Files.delete(switchedReceiptPath)
            ReadReceiptV2ExactDeltaApplyRunner.writeApplyReceipt(
                switchedReceiptPath,
                originalReceipt.copy(lineageId = "2".repeat(64)),
            )
            rejectsState("switched apply receipt") {
                ReadReceiptV2ExactDeltaApplyRunner.publishArtifactWithReceipt(
                    switchedArtifact,
                    switchedOutput.toPath(),
                    switchedReceiptPath,
                    ReadReceiptV2ExactDeltaApplyRunner.receiptPath(switchedOutput).toPath(),
                    originalReceipt,
                ) {}
            }
            check(!switchedOutput.exists() && !ReadReceiptV2ExactDeltaApplyRunner.receiptPath(switchedOutput).exists())
            check(ReadReceiptV2ExactDeltaApplyRunner.cleanupOwnedTemporaryRoot(switchedReceiptRoot))

            val publishedSwitchRoot = ReadReceiptV2ExactDeltaApplyRunner.createOwnedTemporaryRoot(temporaryParent)
            val publishedSwitchArtifact = publishedSwitchRoot.path.resolve("artifact.apk")
            writeApk(publishedSwitchArtifact.toFile(), "payload-e")
            val publishedSwitchOutput = File(root, "published-switch.unsigned.apk")
            val publishedSwitchConfig = unsigned.copy(output = publishedSwitchOutput)
            val publishedSwitchReceipt = ReadReceiptV2ExactDeltaApplyRunner.createApplyReceipt(
                publishedSwitchConfig,
                publishedSwitchArtifact,
                "unsigned",
                lineageId = "3".repeat(64),
            )
            val publishedSwitchReceiptPath = publishedSwitchRoot.path.resolve("apply-receipt.properties")
            ReadReceiptV2ExactDeltaApplyRunner.writeApplyReceipt(
                publishedSwitchReceiptPath,
                publishedSwitchReceipt,
            )
            val displacedPublished = File(root, "displaced-published.apk")
            var publishedVerifierCalls = 0
            rejectsState("published artifact substitution") {
                ReadReceiptV2ExactDeltaApplyRunner.publishArtifactWithReceipt(
                    publishedSwitchArtifact,
                    publishedSwitchOutput.toPath(),
                    publishedSwitchReceiptPath,
                    ReadReceiptV2ExactDeltaApplyRunner.receiptPath(publishedSwitchOutput).toPath(),
                    publishedSwitchReceipt,
                ) { artifact ->
                    publishedVerifierCalls++
                    if (publishedVerifierCalls == 2) {
                        Files.move(artifact, displacedPublished.toPath())
                        writeApk(artifact.toFile(), "attacker-payload")
                    }
                }
            }
            check(publishedVerifierCalls == 2)
            check(publishedSwitchOutput.exists())
            check(ReadReceiptV2ExactDeltaApplyRunner.canonicalPayloadSha256(publishedSwitchOutput.toPath()) !=
                publishedSwitchReceipt.outputPayloadSha256)
            check(!ReadReceiptV2ExactDeltaApplyRunner.receiptPath(publishedSwitchOutput).exists())
            publishedSwitchOutput.delete()
            displacedPublished.delete()
            check(ReadReceiptV2ExactDeltaApplyRunner.cleanupOwnedTemporaryRoot(publishedSwitchRoot))

            val invalidUnsignedRoot =
                ReadReceiptV2ExactDeltaApplyRunner.createOwnedTemporaryRoot(temporaryParent)
            val invalidUnsigned = invalidUnsignedRoot.path.resolve("invalid.unsigned.apk")
            Files.write(invalidUnsigned, byteArrayOf(8))
            rejectsVerification("default unsigned final verifier") {
                ReadReceiptV2ExactDeltaApplyRunner.publishUnsignedArtifact(
                    invalidUnsigned,
                    File(root, "invalid-must-not-publish.unsigned.apk").toPath(),
                )
            }
            check(!File(root, "invalid-must-not-publish.unsigned.apk").exists())
            check(ReadReceiptV2ExactDeltaApplyRunner.cleanupOwnedTemporaryRoot(invalidUnsignedRoot))

            val stagingSubstitutionRoot =
                ReadReceiptV2ExactDeltaApplyRunner.createOwnedTemporaryRoot(temporaryParent)
            val substitutedStaging = stagingSubstitutionRoot.path.resolve("artifact.apk")
            val displacedStaging = stagingSubstitutionRoot.path.resolve("displaced.apk")
            Files.write(substitutedStaging, byteArrayOf(10))
            rejectsState("staging substitution") {
                ReadReceiptV2ExactDeltaApplyRunner.publishOwnedArtifact(
                    substitutedStaging,
                    File(root, "must-not-publish.apk").toPath(),
                ) { artifact ->
                    Files.move(artifact, displacedStaging)
                    Files.write(artifact, byteArrayOf(11))
                }
            }
            check(!File(root, "must-not-publish.apk").exists())

            val inPlaceStaging = stagingSubstitutionRoot.path.resolve("in-place.apk")
            Files.write(inPlaceStaging, byteArrayOf(12))
            rejectsState("in-place staging mutation") {
                ReadReceiptV2ExactDeltaApplyRunner.publishOwnedArtifact(
                    inPlaceStaging,
                    File(root, "must-not-publish-in-place.apk").toPath(),
                ) { artifact ->
                    Files.write(artifact, byteArrayOf(13))
                }
            }
            check(!File(root, "must-not-publish-in-place.apk").exists())
            check(ReadReceiptV2ExactDeltaApplyRunner.cleanupOwnedTemporaryRoot(stagingSubstitutionRoot))

            val substitutedRoot = ReadReceiptV2ExactDeltaApplyRunner.createOwnedTemporaryRoot(temporaryParent)
            val relocatedRoot = File(root, "relocated-owned-root").toPath()
            Files.move(substitutedRoot.path, relocatedRoot)
            Files.createDirectory(substitutedRoot.path)
            val replacementMarker = substitutedRoot.path.resolve("must-survive")
            Files.write(replacementMarker, byteArrayOf(7))
            check(!ReadReceiptV2ExactDeltaApplyRunner.cleanupOwnedTemporaryRoot(substitutedRoot))
            check(Files.exists(replacementMarker))
            substitutedRoot.path.toFile().deleteRecursively()
            relocatedRoot.toFile().deleteRecursively()

            rejects("raw password argument") {
                ReadReceiptV2ExactDeltaApplyRunner.parse(signedArgs + arrayOf("--key-password", "secret"))
            }
            rejects("missing dependency lane") {
                ReadReceiptV2ExactDeltaApplyRunner.parse(
                    signedArgs.filterNotWithFollowingValue("--dependency-lane").toTypedArray(),
                )
            }
            rejects("invalid dependency lane") {
                ReadReceiptV2ExactDeltaApplyRunner.parse(
                    signedArgs.replaceOptionValue("--dependency-lane", "unverified"),
                )
            }
            rejects("unsigned with signing") {
                ReadReceiptV2ExactDeltaApplyRunner.parse(
                    arrayOf(
                        "--input", input.path,
                        "--output", File(root, "other.unsigned.apk").path,
                        "--temp", File(root, "temp-other").path,
                        "--unsigned",
                        "--keystore", keyStore.path,
                    ),
                )
            }
            rejects("unsigned final-looking name") {
                ReadReceiptV2ExactDeltaApplyRunner.validate(
                    unsigned.copy(output = File(root, "not-marked.apk")),
                )
            }
            rejects("existing output") {
                val existing = File(root, "existing.unsigned.apk").apply { writeBytes(byteArrayOf(3)) }
                ReadReceiptV2ExactDeltaApplyRunner.validate(unsigned.copy(output = existing))
            }
            rejects("existing receipt") {
                val receiptOutput = File(root, "receipt-exists.unsigned.apk")
                ReadReceiptV2ExactDeltaApplyRunner.receiptPath(receiptOutput).writeText("occupied")
                ReadReceiptV2ExactDeltaApplyRunner.validate(unsigned.copy(output = receiptOutput))
            }
            rejects("symlink input") {
                val symlink = File(root, "input-link.apk").toPath()
                Files.createSymbolicLink(symlink, input.toPath())
                ReadReceiptV2ExactDeltaApplyRunner.validate(unsigned.copy(input = symlink.toFile()))
            }
            rejects("symlink output") {
                val symlink = File(root, "output-link.unsigned.apk").toPath()
                Files.createSymbolicLink(symlink, input.toPath())
                ReadReceiptV2ExactDeltaApplyRunner.validate(unsigned.copy(output = symlink.toFile()))
            }
            rejects("missing temporary parent") {
                ReadReceiptV2ExactDeltaApplyRunner.validate(
                    unsigned.copy(temporaryDirectory = File(root, "missing-temporary-parent")),
                )
            }
            rejects("invalid signer digest") {
                ReadReceiptV2ExactDeltaApplyRunner.validate(
                    signed.copy(signing = signed.signing!!.copy(expectedSignerSha256 = "A".repeat(64))),
                )
            }
            rejects("input signer digest") {
                ReadReceiptV2ExactDeltaApplyRunner.validate(
                    signed.copy(
                        signing = signed.signing!!.copy(
                            expectedSignerSha256 = EXACT_DELTA_INPUT_SIGNER_SHA256,
                        ),
                    ),
                )
            }
            rejects("different valid signer digest") {
                ReadReceiptV2ExactDeltaApplyRunner.validate(
                    signed.copy(signing = signed.signing!!.copy(expectedSignerSha256 = "a".repeat(64))),
                )
            }
            rejects("final verifier input signer digest") {
                ReadReceiptV2ExactDeltaPreflight.verifyFinalSignerIdentity(
                    EXACT_DELTA_INPUT_SIGNER_SHA256,
                    setOf(EXACT_DELTA_INPUT_SIGNER_SHA256),
                )
            }
            rejects("final verifier different valid signer digest") {
                ReadReceiptV2ExactDeltaPreflight.verifyFinalSignerIdentity(
                    "a".repeat(64),
                    setOf("a".repeat(64)),
                )
            }

            println(
                "RRV2-RUNNER-UNIT-001 apply runner verifier passed: " +
                    "positive=2 carrierHidden=1 additiveDependencies=1 snapshot=1 unsignedVerify=2/1reject " +
                    "publishMove=1 receiptPublish=1 staleReject=1 receiptSwitchReject=1 " +
                    "publishedArtifactSwitchReject=1 dependencyLaneTamperReject=1 substitutions=3 " +
                    "failureCleanup=1 finalSigner=1/2reject negative=15 passwordArgs=0",
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun rejects(label: String, block: () -> Unit) {
        try {
            block()
            error("negative runner verification was accepted: $label")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }

    private fun rejectsState(label: String, block: () -> Unit) {
        val rejected = try {
            block()
            false
        } catch (_: IllegalStateException) {
            true
        }
        check(rejected) { "negative runner state verification was accepted: $label" }
    }

    private fun Array<String>.filterNotWithFollowingValue(option: String): List<String> {
        val optionIndex = indexOf(option)
        check(optionIndex >= 0 && optionIndex + 1 < size)
        return filterIndexed { index, _ -> index != optionIndex && index != optionIndex + 1 }
    }

    private fun Array<String>.replaceOptionValue(option: String, replacement: String): Array<String> {
        val optionIndex = indexOf(option)
        check(optionIndex >= 0 && optionIndex + 1 < size)
        return copyOf().also { it[optionIndex + 1] = replacement }
    }

    private fun rejectsVerification(label: String, block: () -> Unit) {
        val rejected = try {
            block()
            false
        } catch (_: Exception) {
            true
        }
        check(rejected) { "invalid artifact verification was accepted: $label" }
    }

    private fun writeApk(file: File, payload: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(payload.toByteArray())
            zip.closeEntry()
        }
    }
}
