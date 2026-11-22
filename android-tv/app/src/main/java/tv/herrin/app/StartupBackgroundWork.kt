package tv.herrin.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/** Only low-priority personal scanning waits. Home/config/cache/poster requests stay parallel.
 * A lease follows the real startup curtain, including overlapping Activity lifecycles. */
internal object StartupBackgroundWork {
    private val owners = MutableStateFlow(0)
    fun hold(): AutoCloseable {
        owners.update { it + 1 }
        val closed = AtomicBoolean(false)
        return AutoCloseable { if (closed.compareAndSet(false,true)) owners.update { it - 1 } }
    }
    suspend fun awaitReady() {
        if (owners.value > 0) owners.first { it == 0 }
    }
}
