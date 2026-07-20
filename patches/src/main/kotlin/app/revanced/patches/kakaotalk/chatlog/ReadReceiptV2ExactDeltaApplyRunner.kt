package app.revanced.patches.kakaotalk.chatlog

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.zip.ZipFile

object ReadReceiptV2ExactDeltaApplyRunner {
    private const val MAX_PASSWORD_FILE_BYTES = 16 * 1024L

    @JvmStatic
    fun main(args: Array<String>) {
        val config = parse(args)
        validate(config)

        val ownedRoot = createOwnedTemporaryRoot(config.temporaryDirectory)
        try {
            val extensionIdentity = artifactIdentity(config.evidence.extensionMpe.toPath())
            val inputSnapshot = createInputSnapshot(config.input, ownedRoot)
            ReadReceiptV2ExactDeltaPreflight.verifyBeforePatcher(inputSnapshot)

            val carrier = ReadReceiptWatermarkV2ExactDeltaCarrier.patch
            check(
                carrier.name == "Read receipt watermark v2 exact delta" &&
                    !carrier.default &&
                    carrier.dependencies == setOf(
                        readReceiptBackupRulesPatch,
                        readReceiptV2NativeResourcesPatch,
                        readReceiptV2ExactDeltaApplicationEntryPointPatch,
                    )
            ) {
                "exact-delta carrier identity changed"
            }
            val result = Patcher(
                PatcherConfig(
                    apkFile = inputSnapshot,
                    temporaryFilesPath = ownedRoot.path.resolve("patcher-work").toFile(),
                ),
            ).use { patcher ->
                patcher += setOf(carrier)
                val failures = runBlocking { patcher().toList() }.filter { it.exception != null }
                if (failures.isNotEmpty()) {
                    val summary = failures.joinToString("; ") { failure ->
                        "${failure.patch.name ?: "unnamed dependency"}: ${failure.exception?.message ?: "failed"}"
                    }
                    throw IllegalStateException("exact-delta patch execution failed: $summary")
                }
                patcher.get()
            }

            val unsignedStaging = ownedRoot.path.resolve("read-receipt-v2.unsigned.apk").toFile()
            Files.copy(inputSnapshot.toPath(), unsignedStaging.toPath())
            with(ApkUtils) { result.applyTo(unsignedStaging) }

            val signing = config.signing
            if (signing == null) {
                publishWithReceipt(
                    config,
                    ownedRoot,
                    unsignedStaging.toPath(),
                    "unsigned",
                    extensionIdentity,
                ) { artifact -> ReadReceiptV2NativeResourceVerifier.verifyUnsignedFinalApk(artifact.toFile()) }
                println(
                    "RRV2-APPLY-UNSIGNED-001 unsigned staging artifact created: " +
                        "finalSignedVerification=false output=${config.output.absolutePath} " +
                        "receipt=${receiptPath(config.output).absolutePath}",
                )
                return
            }

            val signedStaging = ownedRoot.path.resolve("read-receipt-v2.signed.apk").toFile()
            ApkUtils.signApk(
                inputApkFile = unsignedStaging,
                outputApkFile = signedStaging,
                signer = signing.signerName,
                keyStoreDetails = ApkUtils.KeyStoreDetails(
                    keyStore = signing.keyStore,
                    keyStorePassword = readSecretFile(signing.keyStorePasswordFile).ifEmpty { null },
                    alias = signing.keyAlias,
                    password = readSecretFile(signing.keyPasswordFile),
                ),
            )
            publishWithReceipt(
                config,
                ownedRoot,
                signedStaging.toPath(),
                "signed",
                extensionIdentity,
            ) { artifact ->
                ReadReceiptV2NativeResourceVerifier.verifyFinalApk(
                    artifact.toFile(),
                    signing.expectedSignerSha256,
                )
            }
            println(
                "RRV2-APPLY-SIGNED-001 signed exact-delta artifact created and final-verified: " +
                    "output=${config.output.absolutePath} receipt=${receiptPath(config.output).absolutePath}",
            )
        } finally {
            cleanupOwnedTemporaryRoot(ownedRoot)
        }
    }

    internal data class RunnerConfig(
        val input: File,
        val output: File,
        val temporaryDirectory: File,
        val dependencyLane: DependencyLane,
        val evidence: ApplyEvidence,
        val signing: SigningConfig?,
    )

    internal enum class DependencyLane(val receiptValue: String) {
        RELEASE("release"),
        PINNED_OFFLINE("pinned_offline");

        companion object {
            fun parse(value: String): DependencyLane = entries.singleOrNull { it.receiptValue == value }
                ?: throw IllegalArgumentException("unsupported dependency lane: $value")
        }
    }

    internal data class ApplyEvidence(
        val extensionMpe: File,
        val revancedHead: String,
        val revancedTreeSha256: String,
        val irisHead: String,
        val irisTreeSha256: String,
    )

    internal data class SigningConfig(
        val keyStore: File,
        val keyStorePasswordFile: File,
        val keyAlias: String,
        val keyPasswordFile: File,
        val signerName: String,
        val expectedSignerSha256: String,
    )

    internal fun parse(args: Array<String>): RunnerConfig {
        val values = linkedMapOf<String, String>()
        var unsigned = false
        var index = 0
        while (index < args.size) {
            val option = args[index]
            if (option == "--unsigned") {
                if (unsigned) throw IllegalArgumentException("duplicate option: --unsigned")
                unsigned = true
                index++
                continue
            }
            if (option !in VALUE_OPTIONS || index + 1 >= args.size || args[index + 1].startsWith("--")) {
                throw IllegalArgumentException(USAGE)
            }
            if (values.put(option, args[index + 1]) != null) {
                throw IllegalArgumentException("duplicate option: $option")
            }
            index += 2
        }
        fun required(name: String) = values[name]?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("missing option: $name")
        val input = File(required("--input"))
        val output = File(required("--output"))
        val temporaryDirectory = File(required("--temp"))
        val dependencyLane = DependencyLane.parse(required("--dependency-lane"))
        val evidence = ApplyEvidence(
            extensionMpe = File(required("--extension-mpe")),
            revancedHead = required("--revanced-head"),
            revancedTreeSha256 = required("--revanced-tree-sha256"),
            irisHead = required("--iris-head"),
            irisTreeSha256 = required("--iris-tree-sha256"),
        )
        val signingOptions = SIGNING_OPTIONS.filter(values::containsKey)
        val signing = if (unsigned) {
            if (signingOptions.isNotEmpty()) {
                throw IllegalArgumentException("--unsigned cannot be combined with signing options")
            }
            null
        } else {
            SigningConfig(
                keyStore = File(required("--keystore")),
                keyStorePasswordFile = File(required("--keystore-password-file")),
                keyAlias = required("--key-alias"),
                keyPasswordFile = File(required("--key-password-file")),
                signerName = required("--signer-name"),
                expectedSignerSha256 = required("--expected-signer-sha256"),
            )
        }
        return RunnerConfig(input, output, temporaryDirectory, dependencyLane, evidence, signing)
    }

    internal fun validate(config: RunnerConfig) {
        val input = config.input.toPath().toAbsolutePath().normalize()
        val output = config.output.toPath().toAbsolutePath().normalize()
        val receipt = receiptPath(config.output).toPath().toAbsolutePath().normalize()
        val temporary = config.temporaryDirectory.toPath().toAbsolutePath().normalize()
        val extensionMpe = config.evidence.extensionMpe.toPath().toAbsolutePath().normalize()
        if (!Files.isRegularFile(input, NOFOLLOW_LINKS) || !Files.isRegularFile(extensionMpe, NOFOLLOW_LINKS) ||
            input == output || input == receipt || input == temporary || output == receipt ||
            output == temporary || receipt == temporary || extensionMpe in setOf(input, output, receipt, temporary)
        ) {
            throw IllegalArgumentException("input, output, and temporary paths must be distinct and valid")
        }
        if (Files.exists(output, NOFOLLOW_LINKS) || Files.exists(receipt, NOFOLLOW_LINKS) ||
            !Files.isDirectory(temporary, NOFOLLOW_LINKS)
        ) {
            throw IllegalArgumentException(
                "output and receipt must not exist and temporary parent must be an existing directory",
            )
        }
        if (!Files.isDirectory(output.parent, NOFOLLOW_LINKS) ||
            !Files.isDirectory(temporary.parent, NOFOLLOW_LINKS)
        ) {
            throw IllegalArgumentException("output and temporary parent directories must already exist")
        }
        if (Files.getFileStore(output.parent) != Files.getFileStore(temporary)) {
            throw IllegalArgumentException("output and temporary parent must use the same filesystem")
        }
        if (!config.evidence.revancedHead.matches(Regex("[0-9a-f]{40}")) ||
            !config.evidence.irisHead.matches(Regex("[0-9a-f]{40}")) ||
            !config.evidence.revancedTreeSha256.matches(Regex("[0-9a-f]{64}")) ||
            !config.evidence.irisTreeSha256.matches(Regex("[0-9a-f]{64}"))
        ) {
            throw IllegalArgumentException("apply evidence digests are not canonical")
        }
        if (config.signing == null) {
            if (!config.output.name.endsWith(".unsigned.apk")) {
                throw IllegalArgumentException("unsigned output must end with .unsigned.apk")
            }
        } else {
            val signing = config.signing
            if (config.output.name.endsWith(".unsigned.apk") ||
                !Files.isRegularFile(signing.keyStore.toPath(), NOFOLLOW_LINKS) ||
                !Files.isRegularFile(signing.keyStorePasswordFile.toPath(), NOFOLLOW_LINKS) ||
                !Files.isRegularFile(signing.keyPasswordFile.toPath(), NOFOLLOW_LINKS) ||
                signing.keyAlias.any(Char::isISOControl) || signing.signerName.any(Char::isISOControl) ||
                signing.expectedSignerSha256 != EXACT_DELTA_FINAL_SIGNER_SHA256
            ) {
                throw IllegalArgumentException("invalid explicit signing configuration")
            }
            listOf(signing.keyStore, signing.keyStorePasswordFile, signing.keyPasswordFile).forEach { file ->
                val path = file.toPath().toAbsolutePath().normalize()
                if (path in setOf(output, receipt, temporary, input, extensionMpe)) {
                    throw IllegalArgumentException("signing files must be distinct from runner artifacts")
                }
            }
        }
    }

    internal data class OwnedTemporaryRoot(val path: Path, val fileKey: Any)

    internal fun createOwnedTemporaryRoot(parent: File): OwnedTemporaryRoot {
        val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        val root = Files.createTempDirectory(parent.toPath(), ".rrv2-exact-delta-", permissions)
        val attributes = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val fileKey = attributes.fileKey()
        if (!attributes.isDirectory || fileKey == null) {
            Files.deleteIfExists(root)
            throw IllegalStateException("owned temporary root has no stable directory identity")
        }
        return OwnedTemporaryRoot(root, fileKey)
    }

    internal fun createInputSnapshot(input: File, ownedRoot: OwnedTemporaryRoot): File {
        requireOwnedTemporaryRoot(ownedRoot)
        val snapshot = ownedRoot.path.resolve("exact-input.snapshot.apk").toFile()
        Files.copy(input.toPath(), snapshot.toPath())
        return snapshot
    }

    private fun readSecretFile(file: File): String {
        val size = Files.size(file.toPath())
        if (size > MAX_PASSWORD_FILE_BYTES) throw IllegalArgumentException("password file exceeds size bound")
        var value = Files.readString(file.toPath(), StandardCharsets.UTF_8)
        value = when {
            value.endsWith("\r\n") -> value.dropLast(2)
            value.endsWith('\n') || value.endsWith('\r') -> value.dropLast(1)
            else -> value
        }
        if (value.any { it == '\n' || it == '\r' || it == '\u0000' }) {
            throw IllegalArgumentException("password file must contain exactly one value")
        }
        return value
    }

    internal data class ApplyReceipt(
        val lineageId: String,
        val artifactKind: String,
        val dependencyLane: String,
        val inputSha256: String,
        val extensionMpeSha256: String,
        val revancedHead: String,
        val revancedTreeSha256: String,
        val irisHead: String,
        val irisTreeSha256: String,
        val outputSha256: String,
        val outputPayloadSha256: String,
        val outputBytes: Long,
    ) {
        fun render(): String = buildString {
            append("format_version=1\n")
            append("lineage_id=$lineageId\n")
            append("artifact_kind=$artifactKind\n")
            append("dependency_lane=$dependencyLane\n")
            append("input_sha256=$inputSha256\n")
            append("extension_mpe_sha256=$extensionMpeSha256\n")
            append("revanced_head=$revancedHead\n")
            append("revanced_tree_sha256=$revancedTreeSha256\n")
            append("iris_head=$irisHead\n")
            append("iris_tree_sha256=$irisTreeSha256\n")
            append("output_sha256=$outputSha256\n")
            append("output_payload_sha256=$outputPayloadSha256\n")
            append("output_bytes=$outputBytes\n")
        }
    }

    internal fun receiptPath(output: File): File = File(output.absolutePath + ".apply-receipt.properties")

    internal fun createApplyReceipt(
        config: RunnerConfig,
        artifact: Path,
        artifactKind: String,
        extensionIdentity: ArtifactIdentity = artifactIdentity(config.evidence.extensionMpe.toPath()),
        lineageId: String = randomSha256(),
    ): ApplyReceipt {
        if (artifactKind !in setOf("unsigned", "signed") || !lineageId.matches(Regex("[0-9a-f]{64}"))) {
            throw IllegalArgumentException("invalid apply receipt identity")
        }
        val outputIdentity = artifactIdentity(artifact)
        return ApplyReceipt(
            lineageId = lineageId,
            artifactKind = artifactKind,
            dependencyLane = config.dependencyLane.receiptValue,
            inputSha256 = EXACT_DELTA_INPUT_SHA256,
            extensionMpeSha256 = extensionIdentity.sha256,
            revancedHead = config.evidence.revancedHead,
            revancedTreeSha256 = config.evidence.revancedTreeSha256,
            irisHead = config.evidence.irisHead,
            irisTreeSha256 = config.evidence.irisTreeSha256,
            outputSha256 = outputIdentity.sha256,
            outputPayloadSha256 = canonicalPayloadSha256(artifact),
            outputBytes = outputIdentity.size,
        )
    }

    internal fun writeApplyReceipt(path: Path, receipt: ApplyReceipt) {
        Files.writeString(
            path,
            receipt.render(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--------"))
    }

    internal fun verifyApplyReceipt(path: Path, artifact: Path, expected: ApplyReceipt) {
        val content = Files.readString(path, StandardCharsets.UTF_8)
        if (content != expected.render()) throw IllegalStateException("apply receipt content changed")
        val identity = artifactIdentity(artifact)
        if (expected.inputSha256 != EXACT_DELTA_INPUT_SHA256 ||
            expected.dependencyLane !in DependencyLane.entries.map(DependencyLane::receiptValue) ||
            expected.outputSha256 != identity.sha256 || expected.outputBytes != identity.size ||
            expected.outputPayloadSha256 != canonicalPayloadSha256(artifact)
        ) {
            throw IllegalStateException("apply receipt artifact binding mismatch")
        }
    }

    internal fun publishArtifactWithReceipt(
        artifact: Path,
        output: Path,
        receipt: Path,
        publishedReceipt: Path,
        expectedReceipt: ApplyReceipt,
        verify: (Path) -> Unit,
    ) {
        val artifactBefore = artifactIdentity(artifact)
        val receiptBefore = artifactIdentity(receipt)
        verifyApplyReceipt(receipt, artifact, expectedReceipt)
        verify(artifact)
        if (artifactIdentity(artifact) != artifactBefore || artifactIdentity(receipt) != receiptBefore) {
            throw IllegalStateException("verified staging artifact or receipt changed")
        }
        try {
            Files.move(artifact, output, StandardCopyOption.ATOMIC_MOVE)
            if (artifactIdentity(output) != artifactBefore) {
                throw IllegalStateException("published artifact identity or content changed")
            }
            verify(output)
            verifyApplyReceipt(receipt, output, expectedReceipt)
            if (artifactIdentity(output) != artifactBefore || artifactIdentity(receipt) != receiptBefore) {
                throw IllegalStateException("artifact or receipt changed before receipt publication")
            }
            Files.move(receipt, publishedReceipt, StandardCopyOption.ATOMIC_MOVE)
            if (artifactIdentity(publishedReceipt) != receiptBefore) {
                throw IllegalStateException("published receipt identity or content changed")
            }
            verifyApplyReceipt(publishedReceipt, output, expectedReceipt)
            if (artifactIdentity(output) != artifactBefore || artifactIdentity(publishedReceipt) != receiptBefore) {
                throw IllegalStateException("published artifact pair changed during final verification")
            }
        } catch (exception: Exception) {
            deleteIfIdentity(publishedReceipt, receiptBefore)
            deleteIfIdentity(output, artifactBefore)
            throw exception
        }
    }

    private fun publishWithReceipt(
        config: RunnerConfig,
        ownedRoot: OwnedTemporaryRoot,
        artifact: Path,
        artifactKind: String,
        extensionIdentity: ArtifactIdentity,
        verify: (Path) -> Unit,
    ) {
        requireOwnedTemporaryRoot(ownedRoot)
        if (artifactIdentity(config.evidence.extensionMpe.toPath()) != extensionIdentity) {
            throw IllegalStateException("extension MPE changed during exact-delta apply")
        }
        val receipt = createApplyReceipt(config, artifact, artifactKind, extensionIdentity)
        val receiptStaging = ownedRoot.path.resolve("read-receipt-v2.apply-receipt.properties")
        writeApplyReceipt(receiptStaging, receipt)
        publishArtifactWithReceipt(
            artifact,
            config.output.toPath(),
            receiptStaging,
            receiptPath(config.output).toPath(),
            receipt,
            verify,
        )
    }

    internal fun publishOwnedArtifact(artifact: Path, output: Path, verify: (Path) -> Unit) {
        val before = artifactIdentity(artifact)
        verify(artifact)
        if (artifactIdentity(artifact) != before) {
            throw IllegalStateException("verified staging artifact identity or content changed")
        }
        Files.move(artifact, output, StandardCopyOption.ATOMIC_MOVE)
        if (artifactIdentity(output) != before) {
            throw IllegalStateException("published artifact identity or content changed")
        }
        verify(output)
        if (artifactIdentity(output) != before) {
            throw IllegalStateException("published artifact content changed during final verification")
        }
    }

    internal fun publishUnsignedArtifact(
        artifact: Path,
        output: Path,
        verify: (File) -> Unit = ReadReceiptV2NativeResourceVerifier::verifyUnsignedFinalApk,
    ) {
        publishOwnedArtifact(artifact, output) { candidate -> verify(candidate.toFile()) }
    }

    internal fun cleanupOwnedTemporaryRoot(ownedRoot: OwnedTemporaryRoot): Boolean {
        if (!isOwnedTemporaryRoot(ownedRoot)) {
            System.err.println("warning: owned exact-delta temporary root identity changed; cleanup refused")
            return false
        }
        return try {
            Files.walkFileTree(ownedRoot.path, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (directory == ownedRoot.path && attributes.fileKey() != ownedRoot.fileKey) {
                        throw OwnedRootSubstitutedException()
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, exception: java.io.IOException?): FileVisitResult {
                    if (exception != null) throw exception
                    if (directory == ownedRoot.path && !isOwnedTemporaryRoot(ownedRoot)) {
                        throw OwnedRootSubstitutedException()
                    }
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            })
            true
        } catch (_: OwnedRootSubstitutedException) {
            false
        } catch (_: java.io.IOException) {
            false
        }.also { cleaned ->
            if (!cleaned) {
                System.err.println("warning: owned exact-delta temporary root identity changed; cleanup refused")
            }
        }
    }

    private fun requireOwnedTemporaryRoot(ownedRoot: OwnedTemporaryRoot) {
        if (!isOwnedTemporaryRoot(ownedRoot)) {
            throw IllegalStateException("owned temporary root identity changed")
        }
    }

    private fun isOwnedTemporaryRoot(ownedRoot: OwnedTemporaryRoot): Boolean = try {
        val attributes = Files.readAttributes(ownedRoot.path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        attributes.isDirectory && attributes.fileKey() == ownedRoot.fileKey
    } catch (_: java.io.IOException) {
        false
    }

    internal data class ArtifactIdentity(
        val fileKey: Any,
        val size: Long,
        val modifiedMillis: Long,
        val sha256: String,
    )

    private fun artifactIdentity(path: Path): ArtifactIdentity {
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!before.isRegularFile || before.fileKey() == null) {
            throw IllegalStateException("artifact has no stable regular-file identity")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!after.isRegularFile || after.fileKey() != before.fileKey() || after.size() != before.size() ||
            after.lastModifiedTime() != before.lastModifiedTime()
        ) {
            throw IllegalStateException("artifact changed while hashing")
        }
        return ArtifactIdentity(
            before.fileKey(),
            before.size(),
            before.lastModifiedTime().toMillis(),
            digest.digest().joinToString("") { "%02x".format(it) },
        )
    }

    private fun randomSha256(): String = ByteArray(32).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) }

    private fun deleteIfIdentity(path: Path, expected: ArtifactIdentity) {
        runCatching {
            if (Files.exists(path, NOFOLLOW_LINKS) && artifactIdentity(path) == expected) {
                Files.delete(path)
            }
        }
    }

    internal fun canonicalPayloadSha256(apk: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        ZipFile(apk.toFile()).use { zip ->
            val entries = zip.entries().asSequence().filterNot { it.isDirectory || isSignatureEntry(it.name) }
                .sortedBy { it.name }.toList()
            if (entries.map { it.name }.toSet().size != entries.size) {
                throw IllegalStateException("duplicate APK payload entry")
            }
            entries.forEach { entry ->
                val name = entry.name.toByteArray(StandardCharsets.UTF_8)
                if (entry.name.any { it == '\n' || it == '\r' || it == '\u0000' }) {
                    throw IllegalStateException("unsafe APK payload entry name")
                }
                digest.update(name)
                digest.update(0)
                val entryDigest = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        entryDigest.update(buffer, 0, read)
                    }
                }
                digest.update(entryDigest.digest().joinToString("") { "%02x".format(it) }
                    .toByteArray(StandardCharsets.US_ASCII))
                digest.update('\n'.code.toByte())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isSignatureEntry(name: String): Boolean {
        val upper = name.uppercase(Locale.ROOT)
        return upper == "META-INF/MANIFEST.MF" ||
            (upper.startsWith("META-INF/") &&
                (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") ||
                    upper.endsWith(".EC") || upper.substringAfter("META-INF/").startsWith("SIG-")))
    }

    private class OwnedRootSubstitutedException : RuntimeException()

    private val SIGNING_OPTIONS = setOf(
        "--keystore",
        "--keystore-password-file",
        "--key-alias",
        "--key-password-file",
        "--signer-name",
        "--expected-signer-sha256",
    )
    private val EVIDENCE_OPTIONS = setOf(
        "--extension-mpe",
        "--revanced-head",
        "--revanced-tree-sha256",
        "--iris-head",
        "--iris-tree-sha256",
    )
    private val VALUE_OPTIONS = SIGNING_OPTIONS + EVIDENCE_OPTIONS +
        setOf("--input", "--output", "--temp", "--dependency-lane")
    private const val USAGE =
        "usage: --input <apk> --output <apk> --temp <existing-parent-dir> " +
            "--dependency-lane <release|pinned_offline> --extension-mpe <mpe> " +
            "--revanced-head <sha> --revanced-tree-sha256 <sha256> " +
            "--iris-head <sha> --iris-tree-sha256 <sha256> " +
            "(--unsigned | --keystore <bks> --keystore-password-file <file> --key-alias <alias> " +
            "--key-password-file <file> --signer-name <name> --expected-signer-sha256 <hex>)"
}
