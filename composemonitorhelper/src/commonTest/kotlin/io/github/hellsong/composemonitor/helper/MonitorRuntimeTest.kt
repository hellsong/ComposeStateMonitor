package io.github.hellsong.composemonitor.helper

import androidx.compose.runtime.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals

class MonitorRuntimeTest {

    @Test
    fun unwrapsStatesAndStripsDelegateSuffix() {
        val values = listOf<Pair<String, Any?>>(
            "text" to "a",
            "count\$delegate" to mutableStateOf(1),
            "plain" to mutableStateOf("p"),
            "nullable" to null,
        )
        assertEquals(
            mapOf("text" to "a", "count" to 1, "plain" to "p", "nullable" to null),
            values.toTrackMap(),
        )
    }

    @Test
    fun keepsInsertionOrder() {
        // ComposeMonitorInspector reports changes in map order, which should follow the declaration order.
        val values = listOf<Pair<String, Any?>>("b" to 1, "a" to 2, "c" to 3)
        assertEquals(listOf("b", "a", "c"), values.toTrackMap().keys.toList())
    }
}
