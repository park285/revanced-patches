rootProject.name = "revanced-patches"

pluginManagement {
    val readReceiptV2ReleaseLane =
        providers.gradleProperty("readReceiptV2ReleaseLane").orNull == "true"
    val readReceiptV2PinnedOfflineLane =
        providers.gradleProperty("readReceiptV2PinnedOfflineLane").orNull == "true"
    if (readReceiptV2ReleaseLane && readReceiptV2PinnedOfflineLane) {
        throw GradleException(
            "read-receipt v2 release and pinned offline lanes cannot both be enabled",
        )
    }
    repositories {
        if (!readReceiptV2ReleaseLane) {
            mavenLocal()
        }
        gradlePluginPortal()
        google()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/MorpheApp/registry")
            credentials {
                username = providers.gradleProperty("gpr.user").getOrElse(System.getenv("GITHUB_ACTOR"))
                password = providers.gradleProperty("gpr.key").getOrElse(System.getenv("GITHUB_TOKEN"))
            }
        }
    }
}

dependencyResolutionManagement {
    val readReceiptV2ReleaseLane =
        providers.gradleProperty("readReceiptV2ReleaseLane").orNull == "true"
    val readReceiptV2PinnedOfflineLane =
        providers.gradleProperty("readReceiptV2PinnedOfflineLane").orNull == "true"
    if (readReceiptV2ReleaseLane && readReceiptV2PinnedOfflineLane) {
        throw GradleException(
            "read-receipt v2 release and pinned offline lanes cannot both be enabled",
        )
    }
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        if (!readReceiptV2ReleaseLane) {
            mavenLocal()
        }
        gradlePluginPortal()
        google()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/MorpheApp/registry")
            credentials {
                username = providers.gradleProperty("gpr.user").getOrElse(System.getenv("GITHUB_ACTOR"))
                password = providers.gradleProperty("gpr.key").getOrElse(System.getenv("GITHUB_TOKEN"))
            }
        }
        maven { url = uri("https://jitpack.io") }
    }
}

plugins {
    id("app.morphe.patches") version "1.3.2"
}

settings {
    extensions {
        defaultNamespace = "app.revanced.extension"

        // Must resolve to an absolute path (not relative),
        // otherwise the extensions in subfolders will fail to find the proguard config.
        proguardFiles(rootProject.projectDir.resolve("extensions/proguard-rules.pro").toString())
    }
}

include(":patches:stub")
