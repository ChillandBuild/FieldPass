package com.fieldpass.student.data

import com.fieldpass.student.data.device.DeviceIntegrity
import com.fieldpass.student.data.device.DeviceTraits
import com.fieldpass.student.core.model.DeviceFlags
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceIntegrityTest {
    private val phone = DeviceTraits(
        buildTags = "release-keys", fingerprint = "Xiaomi/sweet/sweet:13/TKQ1/V14:user/release-keys",
        model = "Redmi Note 10 Pro", hardware = "qcom", product = "sweet", existingPaths = emptySet(),
    )

    @Test fun `a normal phone raises nothing`() =
        assertEquals(DeviceFlags(rooted = false, emulator = false), DeviceIntegrity.check(phone))

    @Test fun `an su or magisk file means rooted`() =
        assertEquals(true, DeviceIntegrity.check(phone.copy(existingPaths = setOf("/data/adb/magisk"))).rooted)

    @Test fun `test-keys means rooted`() =
        assertEquals(true, DeviceIntegrity.check(phone.copy(buildTags = "test-keys")).rooted)

    @Test fun `missing build tags is not rooted`() =
        assertEquals(false, DeviceIntegrity.check(phone.copy(buildTags = null)).rooted)

    @Test fun `the Android emulator is spotted by hardware or model`() {
        assertEquals(true, DeviceIntegrity.check(phone.copy(hardware = "ranchu")).emulator)
        assertEquals(true, DeviceIntegrity.check(phone.copy(model = "sdk_gphone64_arm64")).emulator)
        assertEquals(true, DeviceIntegrity.check(phone.copy(fingerprint = "generic/sdk/generic:14/x:userdebug/test-keys")).emulator)
    }
}
