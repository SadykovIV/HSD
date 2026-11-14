package com.hsd.universal

import android.content.Context
import com.hsd.universal.aa.AaDensity
import com.hsd.universal.aa.VideoGeometry

/** Состояние для экрана; пишут сервис и сессия, читает activity (один процесс). */
object CarLifeState {
    @Volatile private var statusText: (Context) -> String = { it.getString(R.string.projection_idle) }
    fun status(context: Context): String = statusText(context)
    fun setStatus(message: String) { statusText = { message } }
    fun setStatus(resource: Int, vararg arguments: Any) {
        statusText = { context -> context.getString(resource, *arguments) }
    }
    @Volatile var accessory: String = "-"
    @Volatile var protocol: String = "-"
    @Volatile var auth: String = "-"
    @Volatile var video: String = "-"
    @Volatile var touch: String = "-"

    /** Видео-режим текущей/последней сессии (фактический, с учётом passthrough); null - сессии не было. */
    @Volatile var videoMode: VideoGeometry? = null

    /** Ручной DPI AA той же сессии (AaDensity.AUTO = density режима). */
    @Volatile var densityOverride: Int = AaDensity.AUTO
    @Volatile var running: Boolean = false
    @Volatile var awaitingCarLifeUsbHandshake: Boolean = false
    @Volatile var waitingForUsbVersion: Boolean = false

    /** Текущая сессия идёт по Wi-Fi (TCP), а не по кабелю; false - сессии нет или она по USB. */
    @Volatile var wirelessSession: Boolean = false
    @Volatile internal var waitingForProjectionWifi = false

    /** Беспровод (бета, ветка B): что сейчас происходит, для экрана; null - режим не включён. */
    @Volatile var wireless: String? = null

    /** Bluetooth-проба ветки A: что прислала ГУ по RFCOMM `carlife`; null - проба не запускалась. */
    @Volatile var btProbe: String? = null

    /** Авторежим беспровода: выбранная машина рядом по Bluetooth (пишет BridgeService). */
    @Volatile var carAuto: Boolean = false

    /** Запрос сети Wi-Fi машины: idle / requesting / available / unavailable (пишет BridgeService). */
    @Volatile var carNet: String = "idle"

    fun resetSession() {
        waitingForUsbVersion = false
        protocol = "-"
        auth = "-"
        video = "-"
        touch = "-"
    }

    fun summary(context: Context): String =
        context.getString(R.string.projection_diagnostics, status(context), accessory, protocol, auth, video, touch) +
            (wireless?.let { "\n" + context.getString(R.string.projection_wireless_diagnostics, it) } ?: "")
}
