import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.tasks.compile.JavaCompile

pluginManager.apply(app.morphe.patches.gradle.ExtensionPlugin::class.java)

dependencies {
    add("compileOnly", project(":extensions:shared:library"))
    add("compileOnly", project(":extensions:kakaotalk:stub"))
    add("compileOnly", libs.annotation)

    add("testImplementation", libs.junit)
}

configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

extensions.configure<ApplicationExtension> {
    namespace = "app.revanced.extension.kakaotalk.readreceipt.v2"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    sourceSets {
        getByName("main") {
            java.directories.clear()
            java.directories.addAll(
                listOf(
                    "../src/main/java/app/revanced/extension/kakaotalk/chatlog/readreceipt",
                    "src/main/java",
                ),
            )
        }
        getByName("test") {
            java.directories.clear()
            java.directories.addAll(
                listOf(
                    "../src/test/java/app/revanced/extension/kakaotalk/chatlog/readreceipt",
                ),
            )
            resources.directories.clear()
            resources.directories.add("../src/test/resources")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    exclude("MessageReadReceipts*.java")
}
