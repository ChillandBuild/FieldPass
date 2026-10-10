package com.fieldpass.student.data.device

import android.os.Build
import com.fieldpass.student.core.model.DeviceFlags
import java.io.File

/** The facts the check looks at, separated from Android so the rule is a plain function. */
data class DeviceTraits(
    val buildTags: String?,
    val fingerprint: String,
    val model: String,
    val hardware: String,
    val product: String,
    val existingPaths: Set<String>,
)

/**
 * Best-effort signs of a rooted phone or an emulator. A modified app can lie about these
 * (integration/known-limitations.md); they are hints for faculty, not proof.
 */
object DeviceIntegrity {
    val ROOT_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/app/Superuser.apk", "/data/adb/magisk", "/system/bin/magisk",
    )
    private val EMULATOR_HARDWARE = setOf("goldfish", "ranchu")
    private val EMULATOR_MODEL_MARKS = listOf("sdk_gphone", "Emulator", "Android SDK built for")

    fun check(traits: DeviceTraits): DeviceFlags = DeviceFlags(
        rooted = traits.buildTags?.contains("test-keys") == true || traits.existingPaths.isNotEmpty(),
        emulator = traits.hardware in EMULATOR_HARDWARE ||
            EMULATOR_MODEL_MARKS.any { traits.model.contains(it) } ||
            traits.fingerprint.startsWith("generic") ||
            traits.product.contains("sdk_gphone"),
    )
}

fun interface DeviceIntegritySource {
    fun flags(): DeviceFlags
}

/** Reads the real phone. */
class BuildDeviceIntegrity : DeviceIntegritySource {
    override fun flags(): DeviceFlags = DeviceIntegrity.check(
        DeviceTraits(
            buildTags = Build.TAGS, fingerprint = Build.FINGERPRINT, model = Build.MODEL,
            hardware = Build.HARDWARE, product = Build.PRODUCT,
            existingPaths = DeviceIntegrity.ROOT_PATHS.filter { runCatching { File(it).exists() }.getOrDefault(false) }.toSet(),
        ),
    )
}
