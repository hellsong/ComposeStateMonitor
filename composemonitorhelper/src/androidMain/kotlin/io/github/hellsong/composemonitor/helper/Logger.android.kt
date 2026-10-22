package io.github.hellsong.composemonitor.helper

import android.util.Log

internal actual fun defaultLogger(tag: String, message: String) {
    Log.d(tag, message)
}
