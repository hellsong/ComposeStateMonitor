package io.github.hellsong.composemonitor.compiler

import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression

// Kotlin 2.2+: one parameter list with IrParameterKind. The old valueParameters API is gone in 2.4.

/** Regular and context parameters. */
internal fun IrFunction.monitoredParameters(): List<IrValueParameter> =
    parameters.filter { it.kind == IrParameterKind.Regular || it.kind == IrParameterKind.Context }

internal fun IrFunction.regularParameterCount(): Int = parameters.count { it.kind == IrParameterKind.Regular }

internal fun IrFunction.allParameters(): List<IrValueParameter> = parameters

/** Sets the argument of parameter [index]. Only used for the runtime functions, which have no receivers. */
internal fun IrCall.setArgument(index: Int, value: IrExpression) {
    arguments[index] = value
}
