package io.github.hellsong.composemonitor.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId

// Kotlin 2.0–2.3: IrPluginContext lookups. finderForSource is missing in 2.3.0 (but present in 2.3.21).

@Suppress("DEPRECATION", "UNUSED_PARAMETER")
internal fun symbolLookup(context: IrPluginContext, file: IrFile): SymbolLookup = object : SymbolLookup {
    override fun findClass(classId: ClassId): IrClassSymbol? = context.referenceClass(classId)

    override fun findFunctions(callableId: CallableId): Collection<IrSimpleFunctionSymbol> =
        context.referenceFunctions(callableId)
}
