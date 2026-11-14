package com.hsd.universal.dex

import org.junit.Assert.*
import org.junit.Test

class UsbPhoneActivationTest {
    @Test fun resolvesExactlyOneInstalledBaseAcrossAndroidPackageLayouts() {
        for (path in listOf("/data/app/com.hsd.universal-2/base.apk",
            "/data/app/~~id1==/com.hsd.universal-nA-t_7==/base.apk", "/mnt/expand/adopted/app/com.hsd.universal/base.apk")) {
            assertEquals(path, UsbPhoneActivation.installedBase("package:$path\npackage:${path.replace("base.apk", "split_config.en.apk")}\n"))
        }
        for (response in listOf("Failure", "package:/data/../base.apk", "package:/data/app/ap'k/base.apk",
            "package:/a/base.apk\npackage:/b/base.apk", "package:/a/b.apk"))
            assertNull(UsbPhoneActivation.installedBase(response))
    }
}
