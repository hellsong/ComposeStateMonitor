package io.github.hellsong.composemonitor.compiler

import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val HELPER_PACKAGE = FqName("io.github.hellsong.composemonitor.helper")
private val COMPOSE_RUNTIME_PACKAGE = FqName("androidx.compose.runtime")

val COMPOSABLE_MONITOR_FQ_NAME: FqName = HELPER_PACKAGE.child(Name.identifier("ComposableMonitor"))
val COMPOSABLE_FQ_NAME: FqName = COMPOSE_RUNTIME_PACKAGE.child(Name.identifier("Composable"))
private val STATE_CLASS_ID = ClassId(COMPOSE_RUNTIME_PACKAGE, Name.identifier("State"))

/** Finds runtime declarations. The lookup API differs between Kotlin lines, see `symbolLookup` in the compat sources. */
interface SymbolLookup {
    fun findClass(classId: ClassId): IrClassSymbol?
    fun findFunctions(callableId: CallableId): Collection<IrSimpleFunctionSymbol>
}

/**
 * Runtime functions the injected code calls. Their signatures must match `MonitorRuntime.kt`
 * in `composemonitorhelper`.
 */
class MonitorSymbols(
    val state: IrClassSymbol,
    val newList: IrSimpleFunctionSymbol,
    val add: IrSimpleFunctionSymbol,
    val collect: IrSimpleFunctionSymbol,
) {
    companion object {
        /** Returns null and fills [problems] if the runtime is missing or has an unexpected shape. */
        fun resolve(lookup: SymbolLookup, problems: MutableList<String>): MonitorSymbols? {
            val state = lookup.findClass(STATE_CLASS_ID)
            if (state == null) problems += "class $STATE_CLASS_ID not found; is the Compose runtime on the classpath?"
            val newList = lookup.findRuntimeFunction("monitorNewList", problems) { owner.regularParameterCount() == 0 }
            val add = lookup.findRuntimeFunction("monitorAdd", problems) { owner.regularParameterCount() == 3 }
            val collect = lookup.findRuntimeFunction("CollectAndPrintValues", problems) {
                owner.regularParameterCount() == 2 && owner.hasAnnotation(COMPOSABLE_FQ_NAME)
            }
            if (state == null || newList == null || add == null || collect == null) return null
            return MonitorSymbols(state, newList, add, collect)
        }

        private fun SymbolLookup.findRuntimeFunction(
            name: String,
            problems: MutableList<String>,
            predicate: IrSimpleFunctionSymbol.() -> Boolean,
        ): IrSimpleFunctionSymbol? {
            val callableId = CallableId(HELPER_PACKAGE, Name.identifier(name))
            val match = findFunctions(callableId).singleOrNull(predicate)
            if (match == null) problems += "function $callableId not found or has an unexpected signature"
            return match
        }
    }
}
