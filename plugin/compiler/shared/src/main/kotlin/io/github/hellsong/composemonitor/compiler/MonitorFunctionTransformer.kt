package io.github.hellsong.composemonitor.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrStatementsBuilder
import org.jetbrains.kotlin.ir.builders.at
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isSubtypeOfClass
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * Rewrites one `@ComposableMonitor` function so that every exit first calls
 *
 * ```
 * val values = monitorNewList()
 * monitorAdd(values, "param", param)          // each regular/context parameter
 * monitorAdd(values, "state", state)          // each State local declared so far
 * CollectAndPrintValues("FunctionName", values)
 * ```
 *
 * Runs before the Compose compiler, which then lowers the injected call like any other.
 *
 * Only `State` locals declared directly in the function body are tracked. That keeps the list shape fixed for a
 * given exit point, which `ComposeMonitorInspector` needs because it remembers old values by position.
 *
 * This source is compiled once per supported Kotlin line, so it only uses IR APIs whose JVM signatures are the same
 * in every line; anything that differs goes through the compat sources (see `ParameterCompat.kt`). Existing IR is
 * never modified until a replacement is complete, so if a newer compiler throws a `LinkageError` halfway, the
 * function is still valid (if partly instrumented).
 */
class MonitorFunctionTransformer(
    context: IrPluginContext,
    private val symbols: MonitorSymbols,
    private val function: IrSimpleFunction,
) {
    private class Tracked(val name: String, val declaration: IrValueDeclaration)

    private val builder = DeclarationIrBuilder(context, function.symbol)

    fun transform() {
        val body = function.body as IrBlockBody
        val tracked = function.monitoredParameters()
            .mapTo(mutableListOf()) { Tracked(it.name.asString(), it) }

        val statements = mutableListOf<IrStatement>()
        for (statement in body.statements) {
            val returnTransformer = ReturnTransformer(tracked.toList())
            when {
                statement is IrReturn && statement.returnTargetSymbol == function.symbol -> {
                    // Keep a top-level return as a direct body statement: the Compose compiler rewrites the trailing
                    // return of value-returning functions and expects to find it there.
                    statement.transformChildrenVoid(returnTransformer)
                    statements += reportAndReturn(statement, tracked).statements
                }
                // Skipped for the same reasons as in ReturnTransformer.
                statement is IrFunction || statement is IrClass || statement is IrTry -> statements += statement
                else -> {
                    // Not statement.transform(): its JVM signature changed in Kotlin 2.2.
                    statement.transformChildrenVoid(returnTransformer)
                    statements += statement
                }
            }
            trackStates(statement, tracked)
        }

        val last = statements.lastOrNull()
        val exitsExplicitly = last is IrReturn || last is IrThrow || (last is IrExpression && last.type.isNothing())
        if (function.returnType.isUnit() && !exitsExplicitly) {
            statements += builder.at(body.endOffset, body.endOffset).irBlock {
                emitReport(tracked)
            }
        }
        body.statements.clear()
        body.statements.addAll(statements)
    }

    // ir.types.isSubtypeOfClass is deprecated in favour of ir.util.isSubtypeOfClass, which Kotlin 2.0 lacks.
    @Suppress("DEPRECATION")
    private fun trackStates(statement: IrStatement, tracked: MutableList<Tracked>) {
        when (statement) {
            is IrVariable ->
                if (statement.type.isSubtypeOfClass(symbols.state)) {
                    tracked += Tracked(statement.name.asString(), statement)
                }
            // `var x by remember { mutableStateOf(..) }`: track the backing `x$delegate` under the name `x`.
            is IrLocalDelegatedProperty -> {
                // Nullable since Kotlin 2.3.
                val delegate: IrVariable? = statement.delegate
                if (delegate != null && delegate.type.isSubtypeOfClass(symbols.state)) {
                    tracked += Tracked(statement.name.asString(), delegate)
                }
            }
            // Top-level destructuring declarations.
            is IrComposite -> statement.statements.forEach { trackStates(it, tracked) }
            else -> Unit
        }
    }

    private fun IrStatementsBuilder<*>.emitReport(tracked: List<Tracked>) {
        val values = irTemporary(irCall(symbols.newList), nameHint = "monitorValues")
        for (value in tracked) {
            +irCall(symbols.add).apply {
                setArgument(0, irGet(values))
                setArgument(1, irString(value.name))
                setArgument(2, irGet(value.declaration))
            }
        }
        +irCall(symbols.collect).apply {
            setArgument(0, irString(function.name.asString()))
            setArgument(1, irGet(values))
        }
    }

    private fun reportAndReturn(expression: IrReturn, tracked: List<Tracked>): IrContainerExpression =
        builder.at(expression.startOffset, expression.endOffset).irBlock(resultType = expression.type) {
            val value = expression.value
            if (value is IrGetObjectValue) {
                emitReport(tracked)
                +expression
            } else {
                // Evaluate the returned value before reporting, so the report sees the final state. A new return
                // instead of changing the old one's value keeps the old one valid until this block replaces it.
                val result = irTemporary(value, nameHint = "monitorReturn")
                emitReport(tracked)
                +irReturn(irGet(result))
            }
        }

    /** Prepends the report to each nested `return` from [function] within one top-level statement. */
    private inner class ReturnTransformer(private val tracked: List<Tracked>) : IrElementTransformerVoid() {
        private var tryDepth = 0

        // Lambdas and local declarations have their own returns. Non-local returns from inline lambdas are not logged.
        override fun visitFunction(declaration: IrFunction): IrStatement = declaration

        override fun visitClass(declaration: IrClass): IrStatement = declaration

        // Compose does not support composable calls inside try/catch, so leave returns there alone.
        override fun visitTry(aTry: IrTry): IrExpression {
            tryDepth++
            aTry.transformChildrenVoid(this)
            tryDepth--
            return aTry
        }

        override fun visitReturn(expression: IrReturn): IrExpression {
            expression.transformChildrenVoid(this)
            if (expression.returnTargetSymbol != function.symbol || tryDepth > 0) return expression
            return reportAndReturn(expression, tracked)
        }
    }
}
