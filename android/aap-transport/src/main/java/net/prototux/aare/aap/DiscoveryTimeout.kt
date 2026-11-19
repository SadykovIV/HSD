package net.prototux.aare.aap

import java.util.concurrent.atomic.AtomicReference

/** The reader can see a reply before the writer returns from flush. Either event can race its timer. */
internal class DiscoveryTimeout(private val arm: (() -> Unit) -> Unit, private val expire: (String) -> Unit) {
    private enum class Stage { WRITE, REPLY, DONE }
    private val stage = AtomicReference(Stage.WRITE)

    fun start() = deadline(Stage.WRITE, "service discovery request write timed out")

    fun written() {
        if (stage.compareAndSet(Stage.WRITE, Stage.REPLY)) deadline(Stage.REPLY, "no service discovery response")
    }

    fun received() = stage.getAndSet(Stage.DONE) != Stage.DONE

    private fun deadline(expected: Stage, reason: String) = arm {
        if (stage.compareAndSet(expected, Stage.DONE)) expire(reason)
    }
}
