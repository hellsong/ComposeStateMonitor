package io.github.hellsong.composemonitor.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.extensions.LoadingOrder
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Kotlin 2.0: there is no -Xcompiler-plugin-order, and the Compose compiler is a legacy [ComponentRegistrar] that adds
 * its IR extension to the project before any CompilerPluginRegistrar runs. So register the same way, but with
 * [LoadingOrder.FIRST], to see @Composable calls before the Compose compiler lowers them.
 */
@Suppress("DEPRECATION")
class ComposeStateMonitorComponentRegistrar : ComponentRegistrar {
    override val supportsK2: Boolean = true

    override fun registerProjectComponents(project: MockProject, configuration: CompilerConfiguration) {
        registerMonitorExtension(configuration) { extension ->
            project.extensionArea
                .getExtensionPoint(IrGenerationExtension.extensionPointName)
                .registerExtension(extension, LoadingOrder.FIRST, project)
        }
    }
}
