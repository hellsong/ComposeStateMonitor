package io.github.hellsong.composemonitor.helper

/**
 * Logs why the annotated `@Composable` function recomposed. Requires the
 * `io.github.hellsong.composemonitor` Gradle plugin.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class ComposableMonitor
