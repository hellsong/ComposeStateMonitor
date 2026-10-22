package io.github.hellsong.composemonitor.samplekmp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.hellsong.composemonitor.helper.ComposableMonitor

@Composable
fun App() {
    var text by remember { mutableStateOf("haha!") }
    var count by remember { mutableStateOf(0) }

    Column(Modifier.safeDrawingPadding().padding(16.dp)) {
        BasicText("Click Me!", Modifier.clickable {
            count++
            text = "haha! count = $count"
        })
        MainScreen(text)
        CounterLabel(count)
        BasicText(rememberLabel(count))
    }
}

@ComposableMonitor
@Composable
fun MainScreen(text: String) {
    var text2 by remember { mutableStateOf("Hello World!") }
    var clicks by remember { mutableStateOf(0) }

    BasicText("$text $text2!")
    BasicText("Click Me too!", Modifier.clickable {
        clicks++
        text2 = "Hello World! count = $clicks"
    })
}

/**
 * Has an early return. Each exit reports separately, so taking a different exit than last time logs
 * "activated" for that exit instead of a recomposition reason.
 */
@ComposableMonitor
@Composable
fun CounterLabel(count: Int) {
    if (count >= 10) {
        BasicText("That's enough clicks")
        return
    }
    BasicText("count: $count")
}

/** Returns a value, so it can never skip and runs whenever [App] recomposes. */
@ComposableMonitor
@Composable
fun rememberLabel(count: Int): String {
    val prefix = remember { mutableStateOf("clicks") }
    return "${prefix.value}: $count"
}
