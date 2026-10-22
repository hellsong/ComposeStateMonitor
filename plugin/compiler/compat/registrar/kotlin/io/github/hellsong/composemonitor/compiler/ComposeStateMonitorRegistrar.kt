package io.github.hellsong.composemonitor.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration

/** Kotlin 2.3+: registrars have an ID, and the Gradle plugin orders us before Compose with -Xcompiler-plugin-order. */
class ComposeStateMonitorRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        registerMonitorExtension(configuration) { IrGenerationExtension.registerExtension(it) }
    }
}
