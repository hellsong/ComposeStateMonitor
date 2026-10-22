package io.github.hellsong.composemonitor.compat

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Builds and runs a small project with each supported Kotlin and Gradle version, using the plugin, compiler plugin and
 * runtime as published (to build/compat-repo). Run with `./gradlew :compat-test:test -PcompatTest`.
 */
@RunWith(Parameterized::class)
class CompatibilityTest(private val row: Row) {

    data class Row(
        val kotlin: String,
        val gradle: String,
        val composeRuntime: String,
        val expectedCompiler: String,
        val dsl: Dsl = Dsl.KOTLIN,
        /** Compile for js, wasmJs and iosSimulatorArm64 instead of running on the JVM. */
        val multiplatform: Boolean = false,
    ) {
        override fun toString() =
            "Kotlin $kotlin, Gradle $gradle, Compose $composeRuntime, $dsl DSL" + if (multiplatform) ", multiplatform" else ""
    }

    enum class Dsl { KOTLIN, GROOVY }

    @Test
    fun instruments() {
        assumeTrue("multiplatform rows need -PcompatTest.multiplatform", !row.multiplatform || MULTIPLATFORM)
        val dir = Files.createTempDirectory("compat-fixture").toFile()
        try {
            writeFixture(dir)
            val tasks = if (row.multiplatform) {
                listOf("compileKotlinJs", "compileKotlinWasmJs", "compileKotlinIosSimulatorArm64")
            } else {
                listOf("run")
            }
            val output = GradleRunner.create()
                .withGradleVersion(row.gradle)
                .withProjectDir(dir)
                .withArguments(tasks + listOf("--info", "--stacktrace"))
                .forwardStdError(System.err.writer())
                .build()
                .output

            assertContains(output, "using ${row.expectedCompiler} for Kotlin ${row.kotlin}")
            assertFalse("does not support Kotlin" in output, output)
            assertFalse("ran after the Compose compiler" in output, output)
            if (!row.multiplatform) {
                assertContains(output, "ComposeMonitorInspector: Greeting recomposed because")
                assertContains(output, "`text` changed from `a` to `b`")
                assertContains(output, "fixture done")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun writeFixture(dir: File) {
        val resource = if (row.multiplatform) "fixture-kmp" else "fixture"
        File(javaClass.getResource("/$resource")!!.toURI()).copyRecursively(dir)
        File(dir, "gradle.properties").writeText(
            """
            org.gradle.java.home=${JAVA_HOME.replace("\\", "\\\\")}
            org.gradle.jvmargs=-Xmx2g
            kotlin.compiler.execution.strategy=in-process
            kotlin.mpp.stability.nowarn=true
            """.trimIndent()
        )
        val repos = """
            maven { url = uri("${COMPAT_REPO.toURI()}") }
            mavenCentral()
            google()
            gradlePluginPortal()
        """
        if (row.dsl == Dsl.GROOVY) {
            File(dir, "settings.gradle").writeText(
                """
                pluginManagement { repositories { $repos } }
                rootProject.name = 'fixture'
                """.trimIndent()
            )
            File(dir, "build.gradle").writeText(
                """
                plugins {
                    id 'org.jetbrains.kotlin.jvm' version '${row.kotlin}'
                    id 'org.jetbrains.kotlin.plugin.compose' version '${row.kotlin}'
                    id 'io.github.hellsong.composemonitor' version '$VERSION'
                    id 'application'
                }
                repositories { $repos }
                dependencies {
                    implementation 'io.github.hellsong:composemonitorhelper:$VERSION'
                    implementation 'org.jetbrains.compose.runtime:runtime-desktop:${row.composeRuntime}'
                }
                composeStateMonitor { includeClassPackage = ['fixture'] }
                application { mainClass = 'fixture.MainKt' }
                """.trimIndent()
            )
            return
        }
        File(dir, "settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { $repos } }
            rootProject.name = "fixture"
            """.trimIndent()
        )
        // Property.set() rather than `=`: assignment needs Gradle 8.2+.
        val build = if (row.multiplatform) {
            """
            plugins {
                kotlin("multiplatform") version "${row.kotlin}"
                kotlin("plugin.compose") version "${row.kotlin}"
                id("io.github.hellsong.composemonitor") version "$VERSION"
            }
            repositories { $repos }
            kotlin {
                js { browser() }
                @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
                wasmJs { browser() }
                iosSimulatorArm64()
                sourceSets.commonMain.dependencies {
                    implementation("io.github.hellsong:composemonitorhelper:$VERSION")
                    implementation("org.jetbrains.compose.runtime:runtime:${row.composeRuntime}")
                }
            }
            composeStateMonitor { includeClassPackage.set(listOf("fixture")) }
            """
        } else {
            """
            plugins {
                kotlin("jvm") version "${row.kotlin}"
                kotlin("plugin.compose") version "${row.kotlin}"
                id("io.github.hellsong.composemonitor") version "$VERSION"
                application
            }
            repositories { $repos }
            dependencies {
                implementation("io.github.hellsong:composemonitorhelper:$VERSION")
                implementation("org.jetbrains.compose.runtime:runtime-desktop:${row.composeRuntime}")
            }
            composeStateMonitor { includeClassPackage.set(listOf("fixture")) }
            application { mainClass.set("fixture.MainKt") }
            """
        }
        File(dir, "build.gradle.kts").writeText(build.trimIndent())
    }

    companion object {
        private val COMPAT_REPO = File(System.getProperty("compat.repo"))
        private val VERSION = System.getProperty("compat.version")
        private val JAVA_HOME = System.getProperty("compat.javaHome")
        private val MULTIPLATFORM = System.getProperty("compat.multiplatform").toBoolean()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun rows() = listOf(
            // The oldest supported versions. Gradle 7.6 runs on JDK 17 at most, hence JAVA_HOME.
            Row("2.0.0", "7.6.4", "1.6.11", "composemonitor-compiler-k20"),
            Row("2.0.21", "7.6.4", "1.6.11", "composemonitor-compiler-k20", Dsl.GROOVY),
            Row("2.1.10", "8.10.2", "1.7.3", "composemonitor-compiler-k21"),
            Row("2.1.21", "8.10.2", "1.7.3", "composemonitor-compiler-k2120"),
            Row("2.2.0", "8.14.3", "1.8.2", "composemonitor-compiler-k22"),
            Row("2.2.21", "8.14.3", "1.8.2", "composemonitor-compiler-k22"),
            Row("2.3.21", "9.1.0", "1.11.1", "composemonitor-compiler-k23"),
            Row("2.4.21", "9.8.1", "1.11.1", "composemonitor-compiler-k24"),
            Row("2.0.21", "8.10.2", "1.6.11", "composemonitor-compiler-k20", multiplatform = true),
            Row("2.4.21", "9.8.1", "1.11.1", "composemonitor-compiler-k24", multiplatform = true),
        )
    }
}
