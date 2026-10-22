package io.github.hellsong.composemonitor.helper

internal actual fun defaultLogger(tag: String, message: String) {
    println("$tag: $message")
}
