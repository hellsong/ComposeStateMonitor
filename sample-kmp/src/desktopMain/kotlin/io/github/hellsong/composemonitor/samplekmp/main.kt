package io.github.hellsong.composemonitor.samplekmp

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Compose State Monitor") {
        App()
    }
}
