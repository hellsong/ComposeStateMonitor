package io.github.hellsong.composemonitor.helper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

private class Ref<T>(var value: T)

/**
 * Logs which entries of [trackMap] changed since the last applied composition of this call site.
 * The previous map is committed, and the message logged, in a [SideEffect], so a discarded composition does neither.
 */
@Composable
@NonSkippableComposable
fun ComposeMonitorInspector(
    trackMap: Map<String, Any?>,
    logger: (tag: String, message: String) -> Unit = { tag, message ->
        ComposeMonitorInspectorConfig.logger.invoke(
            tag,
            message
        )
    },
    composableName: String? = null
) {
    val previous = remember { Ref<Map<String, Any?>?>(null) }
    val message = describeRecomposition(composableName, previous.value, trackMap)
    SideEffect {
        previous.value = trackMap
        logger(ComposeMonitorInspectorConfig.tag, message)
    }
}

/** Builds the log line for one composition; [old] is `null` when the call site enters composition. */
internal fun describeRecomposition(name: String?, old: Map<String, Any?>?, new: Map<String, Any?>): String {
    if (old == null) return "activated on `$name`"
    val changeLog = buildString {
        for ((key, newValue) in new) {
            val oldValue = old[key]
            val reason = when {
                key !in old -> "`$key` added: `$newValue`"
                oldValue != newValue -> "`$key` changed from `$oldValue` to `$newValue`, "
                // Boxed primitives and strings can be new instances of an unchanged value; Compose compares them by
                // equality, so a new instance never causes a recomposition.
                oldValue !== newValue && !oldValue.isValueLike() ->
                    "`$key` instance changed, but content remains the same -> `$oldValue`"

                else -> null
            }
            if (reason != null) append("\n\t ").append(reason)
        }
    }
    return if (changeLog.isNotEmpty()) {
        "$name recomposed because $changeLog"
    } else {
        "$name recomposed, but reason is unknown. No tracked parameter or State local changed " +
            "(an untracked state read, a CompositionLocal or a non-skippable call can cause this)."
    }
}

private fun Any?.isValueLike(): Boolean =
    this is Number || this is Boolean || this is Char || this is String || this is Enum<*> || this == Unit

/** `Log.d` on Android, stdout elsewhere. */
internal expect fun defaultLogger(tag: String, message: String)

object ComposeMonitorInspectorConfig {

    /*Default values*/
    private const val DEFAULT_TAG = "ComposeMonitorInspector"

    /**/
    var tag = DEFAULT_TAG
    var logger: (tag: String, message: String) -> Unit = ::defaultLogger


    /**
     * To override ComposeMonitorInspector's default variables
     */
    fun init(
        tag: String = DEFAULT_TAG,
        logger: (tag: String, message: String) -> Unit = ::defaultLogger,
    ) {
        ComposeMonitorInspectorConfig.tag = tag
        ComposeMonitorInspectorConfig.logger = logger
    }

}