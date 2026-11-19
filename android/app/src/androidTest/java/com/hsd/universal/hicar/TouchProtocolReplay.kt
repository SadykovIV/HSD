package com.hsd.universal.hicar

import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import com.hsd.universal.CarlifeTouch
import com.hsd.universal.Msg
import com.hsd.universal.Msgs
import com.hsd.universal.aa.AapMessages
import com.hsd.universal.aa.TouchPoint
import com.hsd.universal.carplay.CarPlayTouchLayout

/** Encode a receiver's contact and decode it with the corresponding HSD wire format. */
internal fun forEachTouchProtocol(w: Int, h: Int,
    test: (String, (Int) -> Pair<Int, TouchPoint>) -> Unit) {
    test("Android_Auto") { action ->
        val packet = AapMessages.touchEvent(action, w / 2, h / 2, System.nanoTime()).payload
        val report = InputReport.parseFrom(packet).touchEvent
        val p = report.pointerDataList.single()
        report.actionValue to TouchPoint(p.pointerId, p.x, p.y)
    }
    test("CarLife") { action ->
        val p = checkNotNull(CarlifeTouch.parse(Msg.TOUCH_ACTION, Msgs.touchAction(action, w / 2, h / 2)))
        p.masked to TouchPoint(0, p.x, p.y)
    }
    val digitizer = "050d0904a101050d0922a102050d0938750895018102093315002501750195018102750795018101" +
        "05010930150026ff7f7510950181020931150026ff7f751095018102c0c0"
    val hid = checkNotNull(CarPlayTouchLayout.parse(digitizer.chunked(2).map { it.toInt(16).toByte() }.toByteArray()))
    test("CarPlay") { action ->
        val touch = byteArrayOf(0, if (action == 0) 1 else 0, 0, 64, 0, 64)
        val p = checkNotNull(hid.contacts(touch, w, h)).single()
        (if (p.down) 0 else 1) to TouchPoint(p.slot, p.xPixels, p.yPixels)
    }
    val key = ByteArray(32) { it.toByte() }
    RefUiInput(key).use { peer ->
        HiCarMedia(key).use { phone ->
            test("HiCar") { action ->
                val packet = peer.encode(RefUiInput.Event.touch(action, w / 2, h / 2), 40)
                val p = phone.input(packet.copyOfRange(4, packet.size), w, h)
                p.action to TouchPoint(0, p.x, p.y)
            }
        }
    }
}
