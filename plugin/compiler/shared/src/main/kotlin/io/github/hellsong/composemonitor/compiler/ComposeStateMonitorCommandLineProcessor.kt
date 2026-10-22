package io.github.hellsong.composemonitor.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration

class ComposeStateMonitorCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption(OPTION_ENABLED, "<true|false>", "Instrument @ComposableMonitor functions", required = false),
        CliOption(
            OPTION_INCLUDE_PACKAGE, "<fqname>", "Only instrument functions under this package or class",
            required = false, allowMultipleOccurrences = true,
        ),
        CliOption(OPTION_VERBOSE, "<true|false>", "Dump the transformed IR", required = false),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            OPTION_ENABLED -> configuration.put(MonitorConfigurationKeys.ENABLED, value.toBoolean())
            // Not appendList(): it is an interface default method whose JVM shape changed between Kotlin lines.
            OPTION_INCLUDE_PACKAGE -> configuration.put(
                MonitorConfigurationKeys.INCLUDE_PACKAGES,
                configuration.getList(MonitorConfigurationKeys.INCLUDE_PACKAGES) + value,
            )
            OPTION_VERBOSE -> configuration.put(MonitorConfigurationKeys.VERBOSE, value.toBoolean())
            else -> throw CliOptionProcessingException("Unknown option: ${option.optionName}")
        }
    }

    private companion object {
        const val OPTION_ENABLED = "enabled"
        const val OPTION_INCLUDE_PACKAGE = "includePackage"
        const val OPTION_VERBOSE = "verbose"
    }
}
