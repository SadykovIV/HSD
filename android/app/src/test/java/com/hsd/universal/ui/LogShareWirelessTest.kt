package com.hsd.universal.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class LogShareWirelessTest {
    @Test
    fun mixedLogPrefixesAreSortedBySessionTimeForSharingAfterAnUpdate() {
        val dir = Files.createTempDirectory("hsd-log-test").toFile()
        try {
            val expected = listOf(
                "HSD-20240102-140000.log",
                "headswap-20240102-100000.log",
                "HeadSwapDesk-20240101-230000.log",
            )
            (expected + listOf("other.log", "HSD-20240103-010000.zip")).forEach { dir.resolve(it).createNewFile() }
            dir.resolve("hsd-20240103-020000.log").mkdir()
            assertEquals(expected, LogShare.logFiles(dir).map { it.name })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun picksOnlyLinesWithWlMark() {
        val lines = listOf(
            "12:00:00.001 WL begin wifi-b bind=0.0.0.0",
            "12:00:00.002 сервис: wifi-b, слушаю 6 портов",
            "12:00:00.003 WLAN down",
            "12:00:00.004 WL udp beacon-first iface=wlan2",
        )
        assertEquals(
            listOf("12:00:00.001 WL begin wifi-b bind=0.0.0.0", "12:00:00.004 WL udp beacon-first iface=wlan2"),
            LogShare.wirelessLines(lines),
        )
    }

    @Test
    fun blocksPerFileInGivenOrder() {
        val out = LogShare.wirelessLog(
            listOf(
                "hsd-1.log" to listOf("10:00:00.000 WL a"),
                "hsd-2.log" to emptyList(),
                "hsd-3.log" to listOf("11:00:00.000 WL b", "11:00:00.001 WL c"),
            ),
        )
        assertEquals(
            "== hsd-1.log\n10:00:00.000 WL a\n== hsd-2.log\n== hsd-3.log\n11:00:00.000 WL b\n11:00:00.001 WL c\n",
            out,
        )
    }

    @Test
    fun noLinesGivesStub() {
        assertEquals("WL-строк нет\n", LogShare.wirelessLog(listOf("hsd-1.log" to listOf("обычная строка"))))
        assertEquals("WL-строк нет\n", LogShare.wirelessLog(emptyList()))
    }
}
