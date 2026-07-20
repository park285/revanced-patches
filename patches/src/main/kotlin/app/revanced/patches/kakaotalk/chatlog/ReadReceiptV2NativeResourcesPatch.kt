package app.revanced.patches.kakaotalk.chatlog

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal const val NATIVE_RESOURCE_ROOT = "kakaotalk/readreceipt-native"
internal const val NATIVE_LIBRARY_NAME = "libreadreceiptfs.so"
private const val NATIVE_MAX_BYTES = 1024 * 1024
internal val NATIVE_ABIS = linkedMapOf(
    "arm64-v8a" to NativeExpectation(
        elfClass = 2,
        machine = 183,
        sha256 = "52c5768ad1714f807303140e5a4e7a37f291bf48a71460a338e507434887f78c",
    ),
    "armeabi-v7a" to NativeExpectation(
        elfClass = 1,
        machine = 40,
        sha256 = "07894b12d4f33a823cfcfd989eaf1fbe28d3ee5f17550fd0cdcadabb3755cac9",
    ),
)

internal data class NativeExpectation(val elfClass: Int, val machine: Int, val sha256: String)

internal data class ReadReceiptNativeResource(val abi: String, val bytes: ByteArray)

internal fun verifyReadReceiptNativeResource(resource: ReadReceiptNativeResource) {
    val expected = NATIVE_ABIS[resource.abi] ?: throw IllegalArgumentException("unsupported read-receipt ABI")
    val bytes = resource.bytes
    if (bytes.size !in 64..NATIVE_MAX_BYTES) throw IllegalArgumentException("invalid read-receipt native size")
    if (!bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())) ||
        bytes[4].toInt() != expected.elfClass || bytes[5].toInt() != 1 || bytes[6].toInt() != 1 ||
        littleEndianU16(bytes, 16) != 3 || littleEndianU16(bytes, 18) != expected.machine
    ) {
        throw IllegalArgumentException("invalid read-receipt ELF identity")
    }
    if (MessageDigest.getInstance("SHA-256").digest(bytes).toHex() != expected.sha256) {
        throw IllegalArgumentException("unexpected read-receipt native digest")
    }
}

private fun littleEndianU16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

internal val readReceiptV2NativeResourcesPatch = resourcePatch {
    dependsOn(readReceiptBackupRulesPatch)
    execute {
        val classLoader = object {}.javaClass.classLoader
        val resources = NATIVE_ABIS.keys.map { abi ->
            val path = "$NATIVE_RESOURCE_ROOT/$abi/$NATIVE_LIBRARY_NAME"
            val bytes = classLoader.getResourceAsStream(path)?.use { it.readNBytes(NATIVE_MAX_BYTES + 1) }
                ?: throw PatchException("missing read-receipt native resource for $abi")
            ReadReceiptNativeResource(abi, bytes).also {
                try {
                    verifyReadReceiptNativeResource(it)
                } catch (exception: IllegalArgumentException) {
                    throw PatchException(exception.message ?: "invalid read-receipt native resource")
                }
            }
        }

        val destinations = resources.associateWith { resource ->
            get("lib/${resource.abi}/$NATIVE_LIBRARY_NAME", copy = false)
        }
        if (destinations.values.any(File::exists)) {
            throw PatchException("read-receipt native resource is already present")
        }

        document("AndroidManifest.xml").use { document ->
            val manifest = document.documentElement
            val applications = (0 until manifest.childNodes.length).mapNotNull { manifest.childNodes.item(it) as? Element }
                .filter { it.tagName == "application" && it.namespaceURI.isNullOrEmpty() }
            if (applications.size != 1) throw PatchException("manifest must contain one direct application")
            val application = applications.single()
            if (application.decodedAndroidAttribute("name") != EXACT_DELTA_APPLICATION ||
                application.decodedAndroidAttribute("extractNativeLibs") != "false"
            ) {
                throw PatchException("unexpected exact application manifest before native injection")
            }

            destinations.forEach { (resource, destination) ->
                val parent = destination.parentFile
                if (parent == null || (!parent.isDirectory && !parent.mkdirs())) {
                    throw PatchException("could not create read-receipt native directory")
                }
                destination.writeBytes(resource.bytes)
            }
            application.setDecodedAndroidAttribute("extractNativeLibs", "true")
        }
    }
}
