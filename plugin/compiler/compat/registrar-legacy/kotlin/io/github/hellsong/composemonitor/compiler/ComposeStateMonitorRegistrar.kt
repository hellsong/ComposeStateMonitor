package io.github.hellsong.composemonitor.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Kotlin 2.0–2.2: there is no -Xcompiler-plugin-order. Every registrar adds to the same storage, and IR extensions run
 * in list order, so put ours first to see @Composable calls before the Compose compiler lowers them.
 */
class ComposeStateMonitorRegistrar : CompilerPluginRegistrar() {
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        registerMonitorExtension(configuration) { extension ->
            val registered = registeredExtensions[IrGenerationExtension]
            try {
                @Suppress("UNCHECKED_CAST")
                if (registered.isNullOrEmpty()) IrGenerationExtension.registerExtension(extension)
                else (registered as MutableList<Any>).add(0, extension)
            } catch (e: RuntimeException) {
                // Not a mutable list after all: run after Compose, which ComposeStateMonitorIrExtension reports as an error.
                IrGenerationExtension.registerExtension(extension)
            }
        }
    }
}
