package io.github.hellsong.composemonitor.compiler

import org.jetbrains.kotlin.config.CompilerConfigurationKey

/** Must match `COMPILER_PLUGIN_ID` in the Gradle plugin and the `-Xcompiler-plugin-order` constraint. */
const val PLUGIN_ID = "io.github.hellsong.composemonitor"

object MonitorConfigurationKeys {
    val ENABLED = CompilerConfigurationKey<Boolean>("enable compose state monitor")
    val INCLUDE_PACKAGES = CompilerConfigurationKey<List<String>>("package or class prefixes to monitor")
    val VERBOSE = CompilerConfigurationKey<Boolean>("dump transformed IR")
}
