package io.github.hellsong.composemonitor.compiler

import androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class MonitorFunctionTransformerTest {

    @Test
    fun `reports params and top-level states at every exit`() {
        val result = compile(SCREEN)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

        assertEquals(
            listOf(
                // Initial composition.
                "Screen(text=a, count=0, local=1, plain=p, after=2)",
                "Screen(text=early, count=0, local=1, plain=p)",
                "label(text=t, s=x)",
                "label(text=, s=x)",
                // After `text` changes from "a" to "b". `nested` is declared in an inner block and never tracked.
                // The unchanged Screen("early") is skipped; label() returns a value, so it can't skip.
                "Screen(text=b, count=0, local=1, plain=p, after=2)",
                "label(text=t, s=x)",
                "label(text=, s=x)",
            ),
            runHarness(result),
        )
    }

    @Test
    fun `non-unit return value is preserved`() {
        val result = compile(SCREEN)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        runHarness(result)
        val harness = result.classLoader.loadClass("sample.HarnessKt")
        assertEquals("tx", harness.getMethod("getLastLabel").invoke(null))
        assertEquals("empty", harness.getMethod("getLastEmptyLabel").invoke(null))
    }

    @Test
    fun `injected call is lowered by the Compose compiler`() {
        val result = compile(SCREEN)
        val classFile = File(result.outputDirectory, "sample/ScreenKt.class").readBytes().toString(Charsets.ISO_8859_1)
        assertContains(classFile, "CollectAndPrintValues")
        assertContains(classFile, "(Ljava/lang/String;Ljava/util/List;Landroidx/compose/runtime/Composer;I)V")
    }

    @Test
    fun `package filter skips other packages`() {
        val result = compile(SCREEN, includePackages = listOf("other.pkg"))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertEquals(emptyList(), runHarness(result))
    }

    @Test
    fun `package filter matches function prefix`() {
        val result = compile(SCREEN, includePackages = listOf("sample.Screen"))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertTrue(runHarness(result).all { it.startsWith("Screen(") })
    }

    @Test
    fun `unsupported functions are left alone with a warning`() {
        val result = compile(
            SourceFile.kotlin(
                "Unsupported.kt",
                """
                package sample

                import androidx.compose.runtime.Composable
                import io.github.hellsong.composemonitor.helper.ComposableMonitor

                @ComposableMonitor
                fun notComposable(x: Int): Int = x + 1

                @ComposableMonitor
                @Composable
                inline fun InlineComposable(content: @Composable () -> Unit) { content() }

                @Composable
                fun Unannotated(x: Int) {}
                """,
            ),
            withHarness = false,
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertContains(result.messages, "ignored on notComposable: it is not @Composable")
        assertContains(result.messages, "ignored on InlineComposable: inline functions are not supported")
        assertFalse(result.messages.contains("Unannotated"))
    }

    @Test
    fun `running after the Compose compiler fails the build`() {
        // Below Kotlin 2.3 the plugin always puts itself first, see the legacy ComposeStateMonitorRegistrar.
        assumeTrue(SUPPORTS_PLUGIN_ORDER)
        val result = compile(SCREEN, monitorFirst = false)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "ran after the Compose compiler")
    }

    private fun compile(
        source: SourceFile,
        includePackages: List<String> = emptyList(),
        monitorFirst: Boolean = true,
        withHarness: Boolean = true,
    ): JvmCompilationResult {
        val composeId = "androidx.compose.compiler.plugins.kotlin"
        val order = if (monitorFirst) "$PLUGIN_ID>$composeId" else "$composeId>$PLUGIN_ID"
        return KotlinCompilation().apply {
            sources = listOfNotNull(RUNTIME_STUB, source, HARNESS.takeIf { withHarness })
            // Compose is listed first on purpose: -Xcompiler-plugin-order (Kotlin 2.3+) or the registrar has to fix
            // the order.
            pluginClasspaths = listOf(COMPOSE_PLUGIN_JAR) + MONITOR_PLUGIN_CLASSPATH
            kotlincArguments = listOfNotNull(
                "-Xcompiler-plugin-order=$order".takeIf { SUPPORTS_PLUGIN_ORDER },
                "-Xverify-ir=error".takeIf { SUPPORTS_VERIFY_IR },
            ) + includePackages.flatMap { listOf("-P", "plugin:$PLUGIN_ID:includePackage=$it") }
            jvmTarget = "17"
            inheritClassPath = true
            messageOutputStream = System.out
        }.compile()
    }

    @Suppress("UNCHECKED_CAST")
    private fun runHarness(result: JvmCompilationResult): List<String> =
        result.classLoader.loadClass("sample.HarnessKt").getMethod("runHarness").invoke(null) as List<String>

    private companion object {
        val COMPOSE_PLUGIN_JAR = File(ComposePluginRegistrar::class.java.protectionDomain.codeSource.location.toURI())

        val MONITOR_PLUGIN_CLASSPATH = System.getProperty("composemonitor.pluginClasspath")
            .split(File.pathSeparator)
            .map(::File)
            .filter(File::exists)

        /** Set by plugin/build.gradle.kts from the compiler line this test runs for. */
        val SUPPORTS_PLUGIN_ORDER = System.getProperty("composemonitor.supportsPluginOrder").toBoolean()
        val SUPPORTS_VERIFY_IR = System.getProperty("composemonitor.supportsVerifyIr").toBoolean()

        /** Same signatures as composemonitorhelper's MonitorRuntime.kt; records each call instead of logging. */
        val RUNTIME_STUB = SourceFile.kotlin(
            "MonitorRuntime.kt",
            """
            package io.github.hellsong.composemonitor.helper

            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.NonSkippableComposable
            import androidx.compose.runtime.State
            import androidx.compose.runtime.snapshots.Snapshot

            annotation class ComposableMonitor

            val records = mutableListOf<String>()

            fun monitorNewList(): MutableList<Pair<String, Any?>> = ArrayList()

            fun monitorAdd(list: MutableList<Pair<String, Any?>>, name: String, value: Any?) {
                list.add(name to value)
            }

            // Reads states without observation like the real runtime, so this scope never recomposes on its own.
            // Non-skippable like the real one: Compose 2.4 would otherwise skip calls whose list compares equal.
            @Composable
            @NonSkippableComposable
            fun CollectAndPrintValues(composableName: String, nameValueList: List<Pair<String, Any?>>) {
                records += nameValueList.joinToString(prefix = "${'$'}composableName(", postfix = ")") { (name, value) ->
                    "${'$'}name=" + Snapshot.withoutReadObservation { if (value is State<*>) value.value else value }
                }
            }
            """,
        )

        val SCREEN = SourceFile.kotlin(
            "Screen.kt",
            """
            package sample

            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.getValue
            import androidx.compose.runtime.mutableStateOf
            import androidx.compose.runtime.remember
            import androidx.compose.runtime.setValue
            import io.github.hellsong.composemonitor.helper.ComposableMonitor

            @ComposableMonitor
            @Composable
            fun Screen(text: String, count: Int) {
                var local by remember { mutableStateOf(1) }
                val plain = remember { mutableStateOf("p") }
                if (text == "early") return
                val after = remember { mutableStateOf(2) }
                if (count > 0) {
                    val nested = remember { mutableStateOf(3) }
                    nested.value
                }
            }

            @ComposableMonitor
            @Composable
            fun label(text: String): String {
                val s = remember { mutableStateOf("x") }
                if (text.isEmpty()) return "empty"
                return text + s.value
            }
            """,
        )

        /** Composes [SCREEN] with a real Recomposer, then changes one input and recomposes. */
        val HARNESS = SourceFile.kotlin(
            "Harness.kt",
            """
            package sample

            import androidx.compose.runtime.AbstractApplier
            import androidx.compose.runtime.BroadcastFrameClock
            import androidx.compose.runtime.Composition
            import androidx.compose.runtime.Recomposer
            import androidx.compose.runtime.mutableStateOf
            import androidx.compose.runtime.snapshots.Snapshot
            import io.github.hellsong.composemonitor.helper.records
            import kotlinx.coroutines.launch
            import kotlinx.coroutines.runBlocking
            import kotlinx.coroutines.yield

            private class UnitApplier : AbstractApplier<Unit>(Unit) {
                override fun insertTopDown(index: Int, instance: Unit) {}
                override fun insertBottomUp(index: Int, instance: Unit) {}
                override fun remove(index: Int, count: Int) {}
                override fun move(from: Int, to: Int, count: Int) {}
                override fun onClear() {}
            }

            var lastLabel: String? = null
            var lastEmptyLabel: String? = null

            fun runHarness(): List<String> = runBlocking {
                records.clear()
                val clock = BroadcastFrameClock()
                val recomposer = Recomposer(coroutineContext + clock)
                val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
                val text = mutableStateOf("a")
                val composition = Composition(UnitApplier(), recomposer)
                composition.setContent {
                    Screen(text.value, 0)
                    Screen("early", 0)
                    lastLabel = label("t")
                    lastEmptyLabel = label("")
                }
                text.value = "b"
                Snapshot.sendApplyNotifications()
                yield()
                clock.sendFrame(0L)
                recomposer.awaitIdle()
                composition.dispose()
                recomposer.cancel()
                runner.join()
                records.toList()
            }
            """,
        )
    }
}
