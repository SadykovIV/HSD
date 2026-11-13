package com.hsd.universal.cluster

import com.hsd.universal.aa.AaMeta
import org.junit.Assert.*
import org.junit.Test

class NavigationHintTest {
    @Test fun notificationVariantsSupplyOnlyTheSameManeuversStreet() {
        val primary = ClusterNavigationHint(AaMeta.T_LEFT, "", 120)
        val named = primary.copy(road = "улица Мира")
        val otherTurn = named.copy(nextTurn = AaMeta.T_RIGHT)
        val otherDistance = named.copy(distanceMeters = 300)
        assertEquals(named, NavigationHintParser.fromCardVariants(sequenceOf(primary, otherTurn, named), true))
        assertEquals(primary, NavigationHintParser.fromCardVariants(sequenceOf(primary, otherTurn, otherDistance), true))
        assertEquals(primary, NavigationHintParser.fromCardVariants(sequenceOf(primary, named), false))
        assertEquals(primary, NavigationHintParser.fromCardVariants(sequenceOf(named), false))
        assertEquals(named, NavigationHintParser.fromCardVariants(sequenceOf(primary, named.copy(timeSeconds = 3)), true))
    }
    @Test fun supportsManeuverTextAndDecimalUnits() {
        assertEquals(ClusterNavigationHint(AaMeta.T_LEFT, "", 450),
            NavigationHintParser.parse("Через 450 м поверните налево", "Тестовая улица", null, false))
        assertEquals(ClusterNavigationHint(AaMeta.T_RIGHT, "Тестовая улица", 1200),
            NavigationHintParser.parse("1,2 км", "Тестовая улица", AaMeta.T_RIGHT, true))
        assertEquals(805, NavigationHintParser.metres("0.5 miles"))
        assertEquals(90, NavigationHintParser.metres("90 метров"))
    }
    @Test fun ignoresMissingManeuverDistanceSpeedAndEta() {
        assertNull(NavigationHintParser.parse("До конца маршрута 8 км", "Поверните налево", null, false))
        assertNull(NavigationHintParser.parse("Скорость 60 км/ч", "Поворот налево", null, false))
        assertNull(NavigationHintParser.parse("Перестроение маршрута", "220 м", null, false))
        assertNull(NavigationHintParser.parse("Поверните направо", "", null, false))
    }
    @Test fun usesTheUpcomingManeuverWhenTheInstructionPreviewsASecondTurn() {
        assertEquals(ClusterNavigationHint(AaMeta.T_RIGHT, "", 120),
            NavigationHintParser.parse("Через 120 м поверните направо, затем через 400 м поверните налево", "", null, false))
        assertNull(NavigationHintParser.parse("Поверните направо, затем через 400 м поверните налево", "", null, false))
    }

    @Test fun onlyKnownGuidanceStreetFieldCanSupplyTheRoad() {
        assertEquals("улица 1905 года", NavigationHintParser.parse("120 м", "улица 1905 года", AaMeta.T_LEFT, true)?.road)
        assertEquals("", NavigationHintParser.parse("120 м", "улица 1905 года", AaMeta.T_LEFT, false)?.road)
        for (detail in listOf("Поверните налево", "Осталось 8 км", "120 м", "Street\u0000Injected"))
            assertEquals("", NavigationHintParser.parse("120 м", detail, AaMeta.T_LEFT, true)?.road)
    }
}
