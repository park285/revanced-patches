package app.revanced.patches.kakaotalk.chatlog

import app.morphe.patcher.patch.resourcePatch
import java.io.File

internal val readReceiptBackupRulesPatch = resourcePatch {
    dependsOn(readReceiptV2ExactDeltaBytecodePreflightPatch)

    execute {
        val manifestFile = get("AndroidManifest.xml") as File
        val resourceRoot = get("res") as File
        executeBackupRulesPatch(object : BackupRulesFileSystem {
            override fun readManifest(): ByteArray = manifestFile.readBytes()

            override fun readXmlResources(): Map<String, ByteArray> = resourceRoot.walkTopDown()
                .filter { it.isFile && it.extension == "xml" }
                .associate { "res/" + it.relativeTo(resourceRoot).path.replace(File.separatorChar, '/') to it.readBytes() }

            override fun write(path: String, content: String) {
                val destination = if (path == "AndroidManifest.xml") manifestFile else File(resourceRoot, path.removePrefix("res/"))
                destination.parentFile?.mkdirs()
                destination.writeText(content, Charsets.UTF_8)
            }
        })
    }
}
