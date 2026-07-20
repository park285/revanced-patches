group = "app.ample"

fun readProvenanceProperty(path: String, key: String): String {
    val source = file(path)
    if (!source.isFile) throw GradleException("provenance properties file is missing")
    val prefix = "$key="
    val matches = source.readLines().filter { it.startsWith(prefix) }
    if (matches.size != 1) throw GradleException("provenance $key must occur exactly once")
    return matches.single().removePrefix(prefix).takeIf(String::isNotBlank)
        ?: throw GradleException("provenance $key is empty")
}

val readReceiptV2ExactApk = providers.gradleProperty("readReceiptV2ExactApk")
val readReceiptV2Provenance = providers.gradleProperty("readReceiptV2Provenance")
val readReceiptV2ProvenancePhase = providers.gradleProperty("readReceiptV2ProvenancePhase")
val readReceiptV2IrisWorktree = providers.gradleProperty("readReceiptV2IrisWorktree")
val readReceiptV2FinalApk = providers.gradleProperty("readReceiptV2FinalApk")
val readReceiptV2FinalSignerSha256 = providers.gradleProperty("readReceiptV2FinalSignerSha256")
val readReceiptV2ReleaseLane = providers.gradleProperty("readReceiptV2ReleaseLane")
val readReceiptV2PinnedOfflineLane = providers.gradleProperty("readReceiptV2PinnedOfflineLane")
val readReceiptV2DevicePerformanceApproved =
    providers.gradleProperty("readReceiptV2DevicePerformanceApproved")
val readReceiptV2DeviceSshTarget = providers.gradleProperty("readReceiptV2DeviceSshTarget")
val readReceiptV2PerformanceOutputDir = providers.gradleProperty("readReceiptV2PerformanceOutputDir")
val readReceiptV2LaneProtectedTasks = setOf(
    "verifyReadReceiptV2DependencyLane",
    "verifyReadReceiptV2DependencyIntegrity",
    "verifyReadReceiptBackupRules",
    "verifyReadReceiptV2ActualSqliteSchema",
    "verifyReadReceiptV2NativeArtifacts",
    "verifyReadReceiptV2NativeVerifier",
    "verifyReadReceiptV2ProvenanceSelfTest",
    "verifyReadReceiptV2PerformanceReceiptSelfTest",
    "verifyReadReceiptV2DevicePerformanceRunnerSelfTest",
    "verifyReadReceiptV2FinalArtifact",
    "verifyReadReceiptV2Provenance",
    "verifyReadReceiptV2ExactDeltaRunner",
    "verifyReadReceiptV2ExactDeltaPreflight",
    "verifyReadReceiptV2ExactDeltaHooks",
    "verifyReadReceiptV2Portable",
    "verifyReadReceiptV2ExactDelta",
    "applyReadReceiptV2ExactDelta",
    "compileKotlin",
    "syncExtension",
    "testDebugUnitTest",
)
val readReceiptV2WorkflowTasks = readReceiptV2LaneProtectedTasks - setOf(
    "compileKotlin",
    "syncExtension",
    "testDebugUnitTest",
)
fun readReceiptV2TaskNameMatches(requested: String, expected: String) =
    requested == expected || requested.endsWith(":$expected")
val readReceiptV2WorkflowRequested = gradle.startParameter.taskNames.any { requested ->
    readReceiptV2WorkflowTasks.any { expected ->
        readReceiptV2TaskNameMatches(requested, expected)
    }
}
if (readReceiptV2WorkflowRequested) {
    val release = readReceiptV2ReleaseLane.orNull == "true"
    val pinnedOffline = readReceiptV2PinnedOfflineLane.orNull == "true"
    if (release == pinnedOffline) {
        throw GradleException(
            "exactly one of -PreadReceiptV2ReleaseLane=true or " +
                "-PreadReceiptV2PinnedOfflineLane=true is required",
        )
    }
}
if (readReceiptV2WorkflowRequested && gradle.startParameter.excludedTaskNames.any { excluded ->
        readReceiptV2LaneProtectedTasks.any { expected ->
            readReceiptV2TaskNameMatches(excluded, expected)
        }
    }
) {
    throw GradleException("-x cannot bypass a read-receipt v2 dependency lane task")
}
if (
    readReceiptV2WorkflowRequested &&
        gradle.startParameter.dependencyVerificationMode !=
        org.gradle.api.artifacts.verification.DependencyVerificationMode.STRICT
) {
    throw GradleException("read-receipt v2 dependency verification must use strict mode")
}
val verifyReadReceiptV2DependencyLane = tasks.register("verifyReadReceiptV2DependencyLane") {
    description = "Requires exactly one read-receipt v2 dependency lane"
    doLast {
        val release = readReceiptV2ReleaseLane.orNull == "true"
        val pinnedOffline = readReceiptV2PinnedOfflineLane.orNull == "true"
        if (release == pinnedOffline) {
            throw GradleException(
                "exactly one of -PreadReceiptV2ReleaseLane=true or " +
                    "-PreadReceiptV2PinnedOfflineLane=true is required",
            )
        }
        if (
            gradle.startParameter.dependencyVerificationMode !=
                org.gradle.api.artifacts.verification.DependencyVerificationMode.STRICT
        ) {
            throw GradleException("read-receipt v2 dependency verification must use strict mode")
        }
    }
}
val readReceiptV2ExtensionScope = configurations.dependencyScope("readReceiptV2ExtensionScope").get()
val readReceiptV2ExtensionClasspath = configurations.resolvable("readReceiptV2ExtensionClasspath") {
    extendsFrom(readReceiptV2ExtensionScope)
}
val readReceiptV2RunnerClasspath = configurations.resolvable("readReceiptV2RunnerClasspath") {
    extendsFrom(
        configurations.named("implementation").get(),
        configurations.named("compileOnly").get(),
        configurations.named("runtimeOnly").get(),
    )
}
val readReceiptV2DependencyIntegrityScope =
    configurations.dependencyScope("readReceiptV2DependencyIntegrityScope").get()
val readReceiptV2DependencyIntegrityClasspath =
    configurations.resolvable("readReceiptV2DependencyIntegrityClasspath") {
        extendsFrom(readReceiptV2DependencyIntegrityScope)
    }.get()
val readReceiptV2VerifierClasspath = files(
    layout.buildDirectory.dir("classes/kotlin/main"),
    layout.projectDirectory.dir("src/main/resources"),
    readReceiptV2RunnerClasspath,
    readReceiptV2ExtensionClasspath,
)

patches {
    about {
        name = "Ample Patches"
        description = "Patches for Morphe"
        source = "git@github.com:AmpleReVanced/revanced-patches"
        author = "Ample"
        contact = "na"
        website = "na"
        license = "GNU General Public License v3.0"
    }
}

dependencies {
    val readReceiptV2ExtensionProject = project(
        mapOf(
            "path" to ":extensions:kakaotalk:read-receipt-v2",
            "configuration" to "extensionConfiguration",
        ),
    )
    add("extensionsDependencyScope", readReceiptV2ExtensionProject)
    add(readReceiptV2ExtensionScope.name, readReceiptV2ExtensionProject)

    // Used by JsonGenerator.
    implementation(libs.gson)

    // Required due to smali, or build fails. Can be removed once smali is bumped.
    implementation(libs.guava)

    implementation(libs.morphe.patches.library)

    // Morphe supplies this at runtime; the dedicated exact-delta runner also uses its Flow API.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    add(readReceiptV2DependencyIntegrityScope.name, libs.junit)
    add(
        readReceiptV2DependencyIntegrityScope.name,
        "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
    )

    implementation(libs.apksig)

    // Android API stubs defined here.
    compileOnly(project(":patches:stub"))
}

tasks {
    register<JavaExec>("checkStringResources") {
        description = "Checks resource strings for invalid formatting"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.patches.util.resource.CheckStringResourcesKt")
    }

    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.util.PatchListGeneratorKt")
    }

    register<JavaExec>("verifyReadReceiptBackupRules") {
        description = "Verifies BKP-001 backup-rule preflight and merge planning"
        dependsOn("compileKotlin", "verifyReadReceiptV2DependencyIntegrity")
        classpath = files(
            layout.buildDirectory.dir("classes/kotlin/main"),
            Unit::class.java.protectionDomain.codeSource.location,
        )
        mainClass.set("app.revanced.patches.kakaotalk.chatlog.ReadReceiptBackupRulesVerifier")
    }

    register<Exec>("verifyReadReceiptV2NativeArtifacts") {
        description = "Verifies the API 28 read-receipt native source and packaged libraries"
        dependsOn(verifyReadReceiptV2DependencyLane)
        commandLine(
            "bash",
            layout.projectDirectory.file("src/test/scripts/verify-read-receipt-native-resources.sh"),
        )
    }

    register<Exec>("verifyReadReceiptV2ActualSqliteSchema") {
        description = "Verifies the canonical v2 schema against an actual SQLite catalog"
        dependsOn(verifyReadReceiptV2DependencyLane)
        commandLine(
            "bash",
            rootProject.layout.projectDirectory.file(
                "extensions/kakaotalk/src/test/scripts/verify-read-receipt-schema-v2.sh",
            ),
        )
    }

    register<Exec>("verifyReadReceiptV2Provenance") {
        description = "Verifies phase-aware read-receipt v2 artifact and dirty-tree provenance"
        dependsOn("verifyReadReceiptV2FinalArtifact", verifyReadReceiptV2DependencyLane)
        doFirst {
            val phase = readReceiptV2ProvenancePhase.orNull
                ?: throw GradleException(
                    "-PreadReceiptV2ProvenancePhase=<unsigned|pre_sign|post_sign|final> is required",
                )
            val signerArgs = if (phase == "post_sign" || phase == "final") {
                listOf(
                    readReceiptV2FinalSignerSha256.orNull
                        ?: throw GradleException(
                            "-PreadReceiptV2FinalSignerSha256=<sha256> is required for signed provenance",
                        ),
                )
            } else {
                emptyList()
            }
            commandLine(
                "bash",
                layout.projectDirectory.file("src/test/scripts/verify-read-receipt-v2-provenance.sh"),
                readReceiptV2Provenance.orNull
                    ?: throw GradleException("-PreadReceiptV2Provenance=<properties> is required"),
                readReceiptV2IrisWorktree.orNull
                    ?: throw GradleException("-PreadReceiptV2IrisWorktree=<Iris worktree> is required"),
                phase,
                *signerArgs.toTypedArray(),
            )
        }
    }

    register<Exec>("verifyReadReceiptV2ProvenanceSelfTest") {
        description = "Rejects incomplete, duplicated, and tampered provenance evidence"
        commandLine(
            "bash",
            layout.projectDirectory.file("src/test/scripts/verify-read-receipt-v2-provenance.sh"),
            "--self-test",
        )
    }

    register<Exec>("verifyReadReceiptV2PerformanceReceiptSelfTest") {
        description = "Rejects incomplete or tampered read-receipt v2 performance evidence"
        commandLine(
            "bash",
            layout.projectDirectory.file(
                "src/test/scripts/verify-read-receipt-v2-performance-receipt.sh",
            ),
            "--self-test",
        )
    }

    register<Exec>("verifyReadReceiptV2DevicePerformanceRunnerSelfTest") {
        description = "Verifies the bounded read-receipt v2 device performance runner"
        commandLine(
            "bash",
            layout.projectDirectory.file(
                "src/test/scripts/run-read-receipt-v2-device-performance.sh",
            ),
            "--self-test",
        )
    }

    register<Exec>("runReadReceiptV2DevicePerformance") {
        description = "Runs explicitly approved read-receipt v2 target-device performance acceptance"
        doFirst {
            if (readReceiptV2DevicePerformanceApproved.orNull != "true") {
                throw GradleException(
                    "-PreadReceiptV2DevicePerformanceApproved=true is required for live marker writes",
                )
            }
            val provenancePath = readReceiptV2Provenance.orNull
                ?: throw GradleException("-PreadReceiptV2Provenance=<properties> is required")
            val signedApk = readReceiptV2FinalApk.orNull
                ?: readProvenanceProperty(provenancePath, "output_path")
            commandLine(
                "bash",
                layout.projectDirectory.file(
                    "src/test/scripts/run-read-receipt-v2-device-performance.sh",
                ),
                "--approved-live-marker",
                signedApk,
                readProvenanceProperty(provenancePath, "lineage_id"),
                readReceiptV2DeviceSshTarget.orNull
                    ?: throw GradleException("-PreadReceiptV2DeviceSshTarget=<host> is required"),
                readReceiptV2PerformanceOutputDir.orNull
                    ?: throw GradleException("-PreadReceiptV2PerformanceOutputDir=<dir> is required"),
            )
        }
    }

    val verifyReadReceiptV2DependencyIntegrity = register<Exec>("verifyReadReceiptV2DependencyIntegrity") {
        description = "Checks Gradle-verified read-receipt v2 runner, verifier, plugin, and extension inputs"
        dependsOn(
            "compileKotlin",
            ":extensions:kakaotalk:read-receipt-v2:syncExtension",
            verifyReadReceiptV2DependencyLane,
        )
        doFirst {
            fun requireComponent(
                configuration: Configuration,
                group: String,
                module: String,
                version: String,
            ) {
                val versions = configuration.incoming.resolutionResult.allComponents
                    .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
                    .filter { it.group == group && it.module == module }
                    .map { it.version }
                    .toSet()
                if (versions != setOf(version)) {
                    throw GradleException("dependency integrity component mismatch: $group:$module")
                }
            }

            fun artifact(
                configuration: Configuration,
                group: String,
                module: String,
                version: String,
            ): File {
                val files = configuration.incoming.artifactView {
                    componentFilter { identifier ->
                        val component = identifier as? org.gradle.api.artifacts.component.ModuleComponentIdentifier
                        component?.group == group && component.module == module && component.version == version
                    }
                }.artifacts.artifactFiles.files.toList()
                if (files.size != 1) {
                    throw GradleException("dependency integrity artifact mismatch: $group:$module")
                }
                return files.single()
            }

            val integrityClasspath = readReceiptV2DependencyIntegrityClasspath
            val runnerClasspath = readReceiptV2RunnerClasspath.get()
            runnerClasspath.files
            readReceiptV2ExtensionClasspath.get().files
            requireComponent(runnerClasspath, "com.google.code.gson", "gson", "2.14.0")
            requireComponent(runnerClasspath, "app.morphe", "morphe-patches-library", "1.5.1")
            requireComponent(runnerClasspath, "app.morphe", "morphe-patcher", "1.6.0")
            requireComponent(integrityClasspath, "junit", "junit", "4.13.2")
            requireComponent(
                integrityClasspath,
                "org.jetbrains.kotlinx",
                "kotlinx-coroutines-core",
                "1.10.2",
            )
            requireComponent(
                integrityClasspath,
                "org.jetbrains.kotlinx",
                "kotlinx-coroutines-core-jvm",
                "1.10.2",
            )
            commandLine(
                "bash",
                layout.projectDirectory.file(
                    "src/test/scripts/verify-read-receipt-v2-dependency-integrity.sh",
                ),
                "--self-test",
                artifact(integrityClasspath, "junit", "junit", "4.13.2"),
                artifact(
                    integrityClasspath,
                    "org.jetbrains.kotlinx",
                    "kotlinx-coroutines-core-jvm",
                    "1.10.2",
                ),
                artifact(runnerClasspath, "com.google.code.gson", "gson", "2.14.0"),
            )
        }
    }

    register<JavaExec>("verifyReadReceiptV2NativeVerifier") {
        description = "Runs the read-receipt native and final-APK verifier self-test"
        dependsOn("compileKotlin", verifyReadReceiptV2DependencyIntegrity)
        classpath = readReceiptV2VerifierClasspath
        mainClass.set("app.revanced.patches.kakaotalk.chatlog.ReadReceiptV2NativeResourceVerifier")
        args("--self-test")
    }

    register<JavaExec>("verifyReadReceiptV2FinalArtifact") {
        description = "Verifies an unsigned or signer-pinned final read-receipt v2 APK"
        dependsOn("compileKotlin", verifyReadReceiptV2DependencyIntegrity)
        classpath = readReceiptV2VerifierClasspath
        mainClass.set("app.revanced.patches.kakaotalk.chatlog.ReadReceiptV2NativeResourceVerifier")
        doFirst {
            val provenanceApk = readReceiptV2Provenance.orNull?.let { provenancePath ->
                file(readProvenanceProperty(provenancePath, "output_path")).canonicalFile
            }
            val explicitApk = readReceiptV2FinalApk.orNull?.let { file(it).canonicalFile }
            if (provenanceApk != null && explicitApk != null && provenanceApk != explicitApk) {
                throw GradleException("readReceiptV2FinalApk does not match provenance output_path")
            }
            val apk = provenanceApk ?: explicitApk
                ?: throw GradleException(
                    "-PreadReceiptV2FinalApk=<output.apk> or " +
                        "-PreadReceiptV2Provenance=<properties> is required",
                )
            val signer = when (val phase = readReceiptV2ProvenancePhase.orNull) {
                "unsigned", "pre_sign" -> null
                "post_sign", "final" -> readReceiptV2FinalSignerSha256.orNull
                    ?: throw GradleException(
                        "-PreadReceiptV2FinalSignerSha256=<sha256> is required for signed provenance",
                    )
                null -> readReceiptV2FinalSignerSha256.orNull
                else -> throw GradleException("unsupported provenance phase $phase")
            }
            args = if (signer == null) {
                listOf("--unsigned-final-apk", apk.absolutePath)
            } else {
                listOf("--final-apk", apk.absolutePath, signer)
            }
        }
    }

    register<JavaExec>("verifyReadReceiptV2ExactDeltaRunner") {
        description = "Verifies the exact-delta runner ownership, signing, and publish boundaries"
        dependsOn("compileKotlin", verifyReadReceiptV2DependencyIntegrity)
        classpath = readReceiptV2VerifierClasspath
        mainClass.set("app.revanced.patches.kakaotalk.chatlog.ReadReceiptV2ExactDeltaApplyRunnerVerifier")
    }

    register<JavaExec>("applyReadReceiptV2ExactDelta") {
        description = "Applies only the additive v2 delta through the dedicated exact-input runner"
        dependsOn(
            "compileKotlin",
            ":extensions:kakaotalk:read-receipt-v2:syncExtension",
            "verifyReadReceiptV2DependencyIntegrity",
        )
        classpath = readReceiptV2VerifierClasspath
        mainClass.set("app.revanced.patches.kakaotalk.chatlog.ReadReceiptV2ExactDeltaApplyRunner")
        doFirst {
            args(
                "--dependency-lane",
                if (readReceiptV2PinnedOfflineLane.orNull == "true") "pinned_offline" else "release",
            )
        }
    }

    register<JavaExec>("verifyReadReceiptV2ExactDeltaPreflight") {
        description = "Verifies the pinned already-patched KakaoTalk 26.6.0 input before Patcher creation"
        dependsOn("compileKotlin", verifyReadReceiptV2DependencyIntegrity)
        classpath = readReceiptV2VerifierClasspath
        mainClass.set("app.revanced.patches.kakaotalk.chatlog.ReadReceiptV2ExactDeltaPreflightVerifier")
        doFirst {
            args(
                readReceiptV2ExactApk.orNull
                    ?: throw GradleException("-PreadReceiptV2ExactApk=<already-patched-26.6.0.apk> is required"),
            )
        }
    }

    register<JavaExec>("verifyReadReceiptV2ExactDeltaHooks") {
        description = "Verifies the direct successful-watermark capture hook"
        dependsOn("compileKotlin", verifyReadReceiptV2DependencyIntegrity)
        classpath = readReceiptV2VerifierClasspath
        mainClass.set("app.revanced.patches.kakaotalk.chatlog.ReadReceiptWatermarkV2ExactDeltaVerifier")
        doFirst {
            args(
                readReceiptV2ExactApk.orNull
                    ?: throw GradleException("-PreadReceiptV2ExactApk=<already-patched-26.6.0.apk> is required"),
            )
        }
    }

    register("verifyReadReceiptV2Portable") {
        description = "Runs every release-blocking read-receipt v2 verifier that does not require an exact APK"
        dependsOn(
            verifyReadReceiptV2DependencyLane,
            ":extensions:kakaotalk:read-receipt-v2:testDebugUnitTest",
            ":extensions:kakaotalk:read-receipt-v2:syncExtension",
            "verifyReadReceiptBackupRules",
            "verifyReadReceiptV2ActualSqliteSchema",
            "verifyReadReceiptV2DependencyIntegrity",
            "verifyReadReceiptV2NativeArtifacts",
            "verifyReadReceiptV2NativeVerifier",
            "verifyReadReceiptV2ProvenanceSelfTest",
            "verifyReadReceiptV2PerformanceReceiptSelfTest",
            "verifyReadReceiptV2DevicePerformanceRunnerSelfTest",
            "verifyReadReceiptV2ExactDeltaRunner",
        )
    }

    register("verifyReadReceiptV2ExactDelta") {
        description = "Runs every release-blocking exact-delta verifier for the pinned patched APK"
        dependsOn(
            "verifyReadReceiptV2Portable",
            "verifyReadReceiptV2ExactDeltaPreflight",
            "verifyReadReceiptV2ExactDeltaHooks",
        )
    }
    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs = listOf("-Xcontext-parameters")
    }
}

publishing {
    repositories {
        maven {
            name = "githubPackages"
            url = uri("https://maven.pkg.github.com/amplerevanced/revanced-patches")
            credentials(PasswordCredentials::class)
        }
    }
}

//apply(from = "strings-processing.gradle.kts")
