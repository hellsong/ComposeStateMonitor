package fixture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.hellsong.composemonitor.helper.ComposableMonitor

// Compiled for js, wasmJs and iosSimulatorArm64 by CompatibilityTest's multiplatform rows: the plugin has to
// instrument it, and the published runtime's klibs have to be readable by that row's Kotlin.
@ComposableMonitor
@Composable
fun Greeting(text: String, count: Int) {
    val clicks = remember { mutableStateOf(0) }
    if (text.isEmpty()) return
    clicks.value
}
