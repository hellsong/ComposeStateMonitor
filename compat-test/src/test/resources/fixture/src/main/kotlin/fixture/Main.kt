package fixture

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import io.github.hellsong.composemonitor.helper.ComposableMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

@ComposableMonitor
@Composable
fun Greeting(text: String, count: Int) {
    val clicks = remember { mutableStateOf(0) }
    if (text.isEmpty()) return
    clicks.value
}

private class UnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) {}
    override fun insertBottomUp(index: Int, instance: Unit) {}
    override fun remove(index: Int, count: Int) {}
    override fun move(from: Int, to: Int, count: Int) {}
    override fun onClear() {}
}

/** Composes [Greeting], changes its `text`, and recomposes. The monitor logs to stdout. */
fun main() = runBlocking {
    withTimeout(60_000) {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val text = mutableStateOf("a")
        val composition = Composition(UnitApplier(), recomposer)
        composition.setContent { Greeting(text.value, 1) }
        // The inspector itself writes state while composing, so keep sending frames rather than waiting for idle.
        suspend fun frames() = repeat(5) {
            Snapshot.sendApplyNotifications()
            clock.sendFrame(System.nanoTime())
            delay(20)
        }
        frames()
        text.value = "b"
        frames()
        composition.dispose()
        recomposer.cancel()
        runner.join()
    }
    println("fixture done")
}
