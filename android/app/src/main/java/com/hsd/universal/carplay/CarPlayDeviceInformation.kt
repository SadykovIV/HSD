package com.hsd.universal.carplay

import com.hsd.universal.ProjectionResources
import io.github.shaonialife.cardeck.iap2.Iap2Csm
import java.nio.ByteBuffer
import java.time.ZonedDateTime
import java.util.Locale
import java.util.UUID

/** iAP2 uses one temporary device UUID across Bluetooth and IP, distinct from its pairing identity. */
internal class CarPlayDeviceUuids(private val elapsedMillis: () -> Long) {
    private var uuid: UUID? = null
    private var connections = 0
    private var disconnectedAt = 0L

    @Synchronized fun acquire(life: ProjectionResources): UUID {
        val current = uuid?.takeIf { connections > 0 || elapsedMillis() - disconnectedAt < 60_000 }
            ?: UUID.randomUUID().also { uuid = it }
        connections++
        life.own {
            synchronized(this) {
                if (--connections == 0) disconnectedAt = elapsedMillis()
            }
        }
        return current
    }
}

internal class CarPlayDeviceInformation(private val uuid: UUID) {
    fun publish(
        receives: List<Int>,
        language: String = Locale.getDefault().language,
        now: ZonedDateTime = ZonedDateTime.now(),
        send: (Int, ByteArray) -> Unit,
    ) {
        if (0x4e0c in receives) send(0x4e0c, Iap2Csm.string(0, uuid.toString().uppercase(Locale.ROOT)))
        if (0x4e0a in receives && language.isNotBlank()) send(0x4e0a, Iap2Csm.string(0, language))
        if (0x4e0b in receives) {
            val daylightMinutes = now.zone.rules.getDaylightSavings(now.toInstant()).toMinutes().toInt()
            val epoch = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(now.toEpochSecond()).array()
            send(0x4e0b, Iap2Csm.param(0, epoch) + Iap2Csm.u16(1, now.offset.totalSeconds / 60) +
                Iap2Csm.u8(2, daylightMinutes))
        }
        if (0x4e09 in receives) send(0x4e09, Iap2Csm.string(0, "HSD"))
    }
}
