package io.github.hellsong.composemonitor.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * Called by every line's registrar, which passes its way of adding the extension as [register]. Does nothing when the
 * plugin is disabled, and only warns if [register] fails on a Kotlin version newer than this plugin build.
 */
fun registerMonitorExtension(configuration: CompilerConfiguration, register: (IrGenerationExtension) -> Unit) {
    if (!configuration.get(MonitorConfigurationKeys.ENABLED, true)) return
    val messageCollector = configuration.messageCollector()
    try {
        register(
            ComposeStateMonitorIrExtension(
                includePackages = configuration.getList(MonitorConfigurationKeys.INCLUDE_PACKAGES),
                verbose = configuration.get(MonitorConfigurationKeys.VERBOSE, false),
                messageCollector = messageCollector,
            )
        )
    } catch (e: LinkageError) {
        reportUnsupportedKotlin(messageCollector, e)
    }
}

// CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY does not exist in Kotlin 2.0; the CLI key aliases it in later versions.
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
private fun CompilerConfiguration.messageCollector(): MessageCollector =
    get(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)

private fun reportUnsupportedKotlin(messageCollector: MessageCollector, e: LinkageError) {
    messageCollector.report(
        CompilerMessageSeverity.WARNING,
        "$PLUGIN_ID does not support Kotlin ${KotlinCompilerVersion.VERSION}, so @ComposableMonitor functions " +
            "are not instrumented. Update the io.github.hellsong.composemonitor plugin. ($e)",
    )
}

class ComposeStateMonitorIrExtension(
    private val includePackages: List<String>,
    private val verbose: Boolean,
    private val messageCollector: MessageCollector,
) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        try {
            transformModule(moduleFragment, pluginContext)
        } catch (e: LinkageError) {
            // A Kotlin version newer than this plugin build changed an IR API. MonitorFunctionTransformer leaves valid
            // IR behind when that happens, so stop here and let the build go on without (full) instrumentation.
            reportUnsupportedKotlin(messageCollector, e)
        }
    }

    private fun transformModule(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        for (file in moduleFragment.files) {
            val targets = collectMonitoredFunctions(file).filter { canTransform(file, it) }
            if (targets.isEmpty()) continue

            val problems = mutableListOf<String>()
            val symbols = MonitorSymbols.resolve(symbolLookup(pluginContext, file), problems)
            if (symbols == null) {
                report(
                    CompilerMessageSeverity.ERROR, file, targets.first(),
                    "@ComposableMonitor needs a compatible composemonitorhelper runtime on the classpath: " +
                        problems.joinToString("; "),
                )
                continue
            }

            for (function in targets) {
                MonitorFunctionTransformer(pluginContext, symbols, function).transform()
                if (verbose) report(CompilerMessageSeverity.WARNING, file, function, function.dump())
            }
        }
    }

    private fun collectMonitoredFunctions(file: IrFile): List<IrSimpleFunction> {
        val result = mutableListOf<IrSimpleFunction>()
        // A transformer that changes nothing: unlike the IR visitor classes, it exists in every supported Kotlin line.
        file.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
                if (declaration.hasAnnotation(COMPOSABLE_MONITOR_FQ_NAME) && isIncluded(declaration)) {
                    result += declaration
                }
                return super.visitSimpleFunction(declaration)
            }
        })
        return result
    }

    private fun isIncluded(function: IrSimpleFunction): Boolean {
        if (includePackages.isEmpty()) return true
        val fqName = function.fqNameWhenAvailable?.asString() ?: return false
        return includePackages.any { fqName == it || fqName.startsWith("$it.") }
    }

    private fun canTransform(file: IrFile, function: IrSimpleFunction): Boolean {
        val skipReason = when {
            !function.hasAnnotation(COMPOSABLE_FQ_NAME) -> "it is not @Composable"
            function.isInline -> "inline functions are not supported"
            function.isExpect -> "it is an expect declaration"
            function.body !is IrBlockBody -> "it has no body"
            else -> null
        }
        if (skipReason != null) {
            report(CompilerMessageSeverity.WARNING, file, function, "@ComposableMonitor is ignored on ${function.name}: $skipReason")
            return false
        }
        // The injected call is an ordinary @Composable call, so it must be in place before the Compose compiler
        // adds $composer/$changed. See registerIrExtension in the compat sources for how each line ensures that.
        if (function.allParameters().any { it.name.asString() == "\$composer" }) {
            report(
                CompilerMessageSeverity.ERROR, file, function,
                "$PLUGIN_ID ran after the Compose compiler. " +
                    "Pass -Xcompiler-plugin-order=$PLUGIN_ID>androidx.compose.compiler.plugins.kotlin",
            )
            return false
        }
        return true
    }

    private fun report(severity: CompilerMessageSeverity, file: IrFile, function: IrSimpleFunction, message: String) {
        val entry = file.fileEntry
        val location = CompilerMessageLocation.create(
            entry.name,
            entry.getLineNumber(function.startOffset) + 1,
            entry.getColumnNumber(function.startOffset) + 1,
            null,
        )
        messageCollector.report(severity, message, location)
    }
}
