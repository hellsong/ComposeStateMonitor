package io.github.hellsong.composemonitor.compiler

import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression

// Kotlin 2.0–2.1: the parameter API from before IrParameterKind. Context receivers come first in valueParameters.

/** Regular and context parameters. */
internal fun IrFunction.monitoredParameters(): List<IrValueParameter> = valueParameters

internal fun IrFunction.regularParameterCount(): Int = valueParameters.size - contextReceiverParametersCount

internal fun IrFunction.allParameters(): List<IrValueParameter> =
    listOfNotNull(dispatchReceiverParameter, extensionReceiverParameter) + valueParameters

/** Sets the argument of value parameter [index]. Only used for the runtime functions, which have no receivers. */
internal fun IrCall.setArgument(index: Int, value: IrExpression) {
    putValueArgument(index, value)
}
