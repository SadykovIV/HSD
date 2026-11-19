package com.hsd.universal

import com.hsd.universal.ui.UsbAccessoryPolicy
import org.junit.Assert.*
import org.junit.Test

class ProjectionRouteTest {
    @Test fun savedModesKeepTheirMeaningAcrossUpgrade() {
        for (route in ProjectionRoute.entries) assertEquals(route, ProjectionRoute.restore(route.name))
        assertEquals(ProjectionRoute.USB, ProjectionRoute.restore(null))
        assertEquals(ProjectionRoute.USB, ProjectionRoute.restore("unknown"))
        assertEquals(setOf(ProjectionRoute.CARLIFE_USB, ProjectionRoute.USB, ProjectionRoute.HICAR_USB),
            ProjectionRoute.entries.filter { it.wired }.toSet())
        assertEquals(setOf(ProjectionRoute.CARLIFE_USB, ProjectionRoute.CARLIFE_WIFI), ProjectionRoute.entries.filter { it.carLife }.toSet())
    }

    @Test fun accessoryIdentitySelectsOneWireProtocol() {
        assertEquals(ProjectionRoute.CARLIFE_USB, UsbAccessoryPolicy.route("Baidu", "CarLife"))
        assertEquals(ProjectionRoute.USB, UsbAccessoryPolicy.route("Android", "Android Open Automotive Protocol"))
        assertEquals(ProjectionRoute.USB, UsbAccessoryPolicy.route("Android", "Android Auto"))
        assertNull(UsbAccessoryPolicy.route("Android", "CarLife"))
        assertNull(UsbAccessoryPolicy.route("Baidu", "Android Auto"))
        assertNull(UsbAccessoryPolicy.route(null, null))
    }

    @Test fun onlyHeadUnitHotspotTransportsWaitForThePhoneWifiRadio() {
        assertEquals(setOf(ProjectionRoute.CARPLAY, ProjectionRoute.WIRELESS_AA, ProjectionRoute.HICAR),
            ProjectionRoute.entries.filter { it.usesHeadUnitHotspot }.toSet())
        assertTrue(ProjectionRoute.HICAR_USB.usesLocalNetwork)
        for (state in listOf(android.net.wifi.WifiManager.WIFI_STATE_DISABLED, android.net.wifi.WifiManager.WIFI_STATE_DISABLING))
            assertEquals(ProjectionWifi.OFF, ProjectionWifi.of(state))
        assertTrue(ProjectionWifi.of(android.net.wifi.WifiManager.WIFI_STATE_ENABLING).needsEnable)
        assertFalse(ProjectionWifi.of(android.net.wifi.WifiManager.WIFI_STATE_ENABLED).needsEnable)
        assertFalse(ProjectionWifi.of(null).needsEnable)
        assertFalse(ProjectionWifi.of(android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN).needsEnable)
    }
}
