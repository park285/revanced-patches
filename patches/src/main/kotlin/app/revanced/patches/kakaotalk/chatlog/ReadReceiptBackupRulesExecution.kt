package app.revanced.patches.kakaotalk.chatlog

internal interface BackupRulesFileSystem {
    fun readManifest(): ByteArray
    fun readXmlResources(): Map<String, ByteArray>
    fun write(path: String, content: String)
}

internal fun executeBackupRulesPatch(fileSystem: BackupRulesFileSystem) {
    val manifest = fileSystem.readManifest()
    val resources = fileSystem.readXmlResources()
    val plan = planBackupRules(manifest, resources)
    plan.resources.forEach { fileSystem.write(it.path, it.content) }
    plan.manifest?.let { fileSystem.write("AndroidManifest.xml", it) }
}
