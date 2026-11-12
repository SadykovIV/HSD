package com.hsd.universal

import com.hsd.universal.ui.UsbAccessoryPolicy
import org.junit.Assert.*
import org.junit.Test

class ProjectionRouteTest {
    @Test fun savedModesKeepTheirMeaningAcrossUpgrade() {
        for (route in ProjectionRoute.entries) assertEquals(route, ProjectionRoute.restore(route.name))
        assertEquals(ProjectionRoute.USB, ProjectionRoute.restore(null))
        assertEquals(ProjectionRoute.USB, ProjectionRoute.restore("unknown"))
        assertEquals(setOf(ProjectionRoute.CARLIFE_USB, ProjectionRoute.USB), ProjectionRoute.entries.filter { it.wired }.toSet())
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
}
