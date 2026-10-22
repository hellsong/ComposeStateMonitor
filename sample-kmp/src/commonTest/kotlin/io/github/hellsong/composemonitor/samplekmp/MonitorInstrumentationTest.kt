package io.github.hellsong.composemonitor.samplekmp

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.github.hellsong.composemonitor.helper.ComposeMonitorInspectorConfig
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MonitorInstrumentationTest {

    @Test
    fun logsWhyMonitoredComposablesRecomposed() = runComposeUiTest {
        val logs = mutableListOf<String>()
        ComposeMonitorInspectorConfig.init(logger = { _, message -> logs += message })

        setContent { App() }
        onNodeWithText("Click Me!").performClick()
        waitForIdle()

        val log = logs.joinToString("\n")
        assertTrue("MainScreen recomposed because" in log && "`text` changed" in log, log)
        assertTrue("CounterLabel recomposed because" in log && "`count` changed from `0` to `1`" in log, log)
        assertTrue("rememberLabel recomposed because" in log, log)
        // MainScreen's own states didn't change, and reading them must not recompose the logger by itself.
        assertTrue(logs.none { it.startsWith("MainScreen recomposed, but reason is unknown") }, log)
    }
}
