package io.github.hellsong.composemonitor.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId

// Kotlin 2.4+: DeclarationFinder, which replaces the deprecated IrPluginContext lookups.

internal fun symbolLookup(context: IrPluginContext, file: IrFile): SymbolLookup {
    val finder = context.finderForSource(file)
    return object : SymbolLookup {
        override fun findClass(classId: ClassId): IrClassSymbol? = finder.findClass(classId)

        override fun findFunctions(callableId: CallableId): Collection<IrSimpleFunctionSymbol> =
            finder.findFunctions(callableId)
    }
}
