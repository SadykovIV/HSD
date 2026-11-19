package com.hsd.universal.hicar

import com.hsd.universal.ProjectionResources
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class HiCarInteropTest {
    @Test fun phoneNegotiatesAndProjectsToIndependentReceiver() {
        val key = ByteArray(32) { (it+1).toByte() }
        HiCarReceiverFixture(key).use { receiver ->
            ProjectionResources().use { life ->
                val errors = CopyOnWriteArrayList<Exception>()
                val inputs = CopyOnWriteArrayList<HiCarMedia.Input>()
                val sender = HiCarSender(life, receiver.address, receiver.address, "11".repeat(32), key.clone(), { port ->
                    Socket().also { life.own(it::close); it.connect(InetSocketAddress(receiver.address, port), 5000); it.soTimeout=10000 }
                }, { inputs.add(it) }, { errors.add(it) })
                val mode = sender.start(receiver.port)
                assertEquals(800, mode.width)
                assertEquals(480, mode.height)
                val end = System.nanoTime() + 5_000_000_000
                while ((!sender.audioReady || inputs.size < 3) && System.nanoTime() < end) Thread.sleep(10)
                assertTrue("Speaker setup incomplete: $errors", sender.audioReady)
                val frame = byteArrayOf(0,0,0,1,0x65,23,87,23)
                assertTrue(sender.video(frame, 100_000))
                sender.audio(byteArrayOf(-1,-15,0x4c,-128,1,63,-4,42,12), 100_000)
                while ((receiver.videos.isEmpty() || receiver.audioFrames.isEmpty()) && System.nanoTime() < end) Thread.sleep(10)
                receiver.assertHealthy()
                assertTrue(errors.toString(), errors.isEmpty())
                assertArrayEquals(frame, receiver.videos.single())
                assertArrayEquals(byteArrayOf(42,12), receiver.audioFrames.single())
                assertEquals(listOf(0,1,2), inputs.map { it.action })
            }
        }
    }
}
