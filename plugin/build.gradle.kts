import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.publish)
    alias(libs.plugins.mavenPublish)
    alias(libs.plugins.buildconfig)
}

val compilerPluginId = "io.github.hellsong.composemonitor"
val pluginVersion = libs.versions.composemonitorplugin.get()

allprojects {
    group = "io.github.hellsong"
    version = pluginVersion
}

/**
 * The compiler plugin is built once per Kotlin line, because the IR API has no compatibility guarantee, not even
 * between patch releases. Each line compiles the shared sources in compiler/shared plus the compat sources listed here
 * against its [minKotlin], and its tests run against [testKotlin] (override with `-PcomposeMonitor.testKotlin.kNN=2.x.y`).
 * The Gradle plugin picks the line with the highest [minKotlin] that is not newer than the build's Kotlin.
 *
 * To support a new Kotlin version: add a `compiler/kNN` directory and a row here, plus compat sources if an API
 * changed. Check that the line's classes link against every patch release it covers (see CLAUDE.md).
 */
data class CompilerLine(
    val id: String,
    val minKotlin: String,
    val testKotlin: String,
    /** kctfork release built for [testKotlin]. */
    val kctfork: String,
    /** Compose runtime for the tests. Must be readable by [testKotlin]. */
    val composeRuntime: String,
    val compat: List<String>,
) {
    val artifactId = "composemonitor-compiler-$id"
}

/**
 * First Kotlin version with -Xcompiler-plugin-order. Older lines order themselves before the Compose compiler from
 * their registrar (compat/registrar-component for 2.0, compat/registrar-legacy for 2.1–2.2).
 */
val pluginOrderKotlin = "2.3.0"

val compilerLines = listOf(
    CompilerLine("k20", "2.0.0", "2.0.21", "0.6.0", "1.7.3", listOf("params-legacy", "lookup-reference", "registrar-component")),
    CompilerLine("k21", "2.1.0", "2.1.10", "0.7.1", "1.7.3", listOf("params-legacy", "lookup-reference", "registrar-legacy")),
    // 2.1.20 compiled IrGenerationExtension with JVM default methods: classes built against 2.1.0 call its removed
    // DefaultImpls.
    CompilerLine("k2120", "2.1.20", "2.1.21", "0.7.1", "1.7.3", listOf("params-legacy", "lookup-reference", "registrar-legacy")),
    CompilerLine("k22", "2.2.0", "2.2.21", "0.11.1", "1.8.2", listOf("params", "lookup-reference", "registrar-legacy")),
    CompilerLine("k23", "2.3.0", "2.3.21", "0.12.1", libs.versions.compose.multiplatform.get(), listOf("params", "lookup-reference", "registrar")),
    CompilerLine("k24", "2.4.0", "2.4.21", "0.14.0", libs.versions.compose.multiplatform.get(), listOf("params", "lookup-finder", "registrar")),
)

fun versionAtLeast(version: String, min: String): Boolean {
    fun parts(v: String) = v.substringBefore('-').split('.').map(String::toInt)
    return parts(version).zip(parts(min)).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } ?: true
}

val compatRepo = layout.projectDirectory.dir("../build/compat-repo")

gradlePlugin {
    plugins {
        create("ComposeStateMonitorPlugin") {
            id = compilerPluginId
            displayName = "ComposeStateMonitorPlugin"
            description = "Logs why @ComposableMonitor functions recompose, on every Compose Multiplatform target"
            tags = listOf("compose", "kotlin-multiplatform", "recomposition")
            implementationClass = "io.github.hellsong.composemonitor.gradle.ComposeStateMonitorGradlePlugin"
        }
    }
}

buildConfig {
    packageName("io.github.hellsong.composemonitor.gradle")
    useJavaOutput()
    buildConfigField("GROUP", project.group.toString())
    buildConfigField("VERSION", project.version.toString())
    buildConfigField("COMPILER_PLUGIN_ID", compilerPluginId)
    buildConfigField("PLUGIN_ORDER_KOTLIN_VERSION", pluginOrderKotlin)
    // "minKotlin=artifactId;..." in ascending order.
    buildConfigField("COMPILER_LINES", compilerLines.joinToString(";") { "${it.minKotlin}=${it.artifactId}" })
}

dependencies {
    // The oldest supported KGP, so the plugin only uses API that every supported version has.
    compileOnly(libs.kotlin.gradle.api.floor)
}

// Java, not Kotlin: no Kotlin metadata or stdlib that an old Gradle's embedded Kotlin would have to read.
tasks.withType<JavaCompile>().configureEach {
    options.release = 8
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true)
}

java {
    withSourcesJar()
    withJavadocJar()
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates(artifactId = "composemonitor")
    pom {
        name = "Compose State Monitor Gradle plugin"
        description = "Gradle plugin that logs why @ComposableMonitor functions recompose, on every Compose Multiplatform target."
    }
}

val compilerDir = layout.projectDirectory.dir("compiler")
val jvmToolchainVersion = libs.versions.gradle.jvmToolchain.get().toInt()

subprojects {
    val line = compilerLines.singleOrNull { it.artifactId == name }
        ?: error("$path has no entry in compilerLines")
    val testKotlin = providers.gradleProperty("composeMonitor.testKotlin.${line.id}").getOrElse(line.testKotlin)
    val lineKotlinVersion = KotlinVersion.fromVersion(line.minKotlin.split('.').take(2).joinToString("."))

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "com.vanniktech.maven.publish")

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(jvmToolchainVersion)
        sourceSets.named("main") {
            kotlin.srcDir(compilerDir.dir("shared/src/main/kotlin"))
            line.compat.forEach { kotlin.srcDir(compilerDir.dir("compat/$it/kotlin")) }
        }
        sourceSets.named("test") {
            kotlin.srcDir(compilerDir.dir("shared/src/test/kotlin"))
        }
        compilerOptions {
            // The plugin runs inside the user's compiler, next to that compiler's stdlib.
            jvmTarget = JvmTarget.JVM_1_8
            languageVersion = lineKotlinVersion
            apiVersion = lineKotlinVersion
            freeCompilerArgs.add("-Xsuppress-version-warnings")
            optIn.addAll(
                "org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi",
                "org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI",
            )
        }
    }
    extensions.configure<SourceSetContainer> {
        named("main") {
            resources.srcDir(compilerDir.dir("shared/src/main/resources"))
            // The registrar variants bring their own META-INF/services entry.
            line.compat.forEach { resources.srcDir(compilerDir.dir("compat/$it/resources")) }
        }
    }
    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release = 8
    }

    dependencies {
        "compileOnly"("org.jetbrains.kotlin:kotlin-compiler-embeddable:${line.minKotlin}")
        "compileOnly"("org.jetbrains.kotlin:kotlin-stdlib:${line.minKotlin}")

        "testImplementation"("org.jetbrains.kotlin:kotlin-compiler-embeddable:$testKotlin")
        "testImplementation"("org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:$testKotlin")
        "testImplementation"("org.jetbrains.kotlin:kotlin-stdlib:$testKotlin")
        "testImplementation"("org.jetbrains.kotlin:kotlin-test-junit:$testKotlin")
        "testImplementation"("dev.zacsweers.kctfork:core:${line.kctfork}")
        "testImplementation"("org.jetbrains.compose.runtime:runtime-desktop:${line.composeRuntime}")
    }
    // The tests run the line's compiler in-process, so nothing newer may leak onto the classpath (e.g. a
    // stdlib pulled in by a dependency).
    configurations.matching { it.name.startsWith("test") }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") useVersion(testKotlin)
        }
    }

    tasks.named<Test>("test") {
        // Tests load the plugin through -Xplugin, like Gradle does, so the plugin ordering is exercised.
        val pluginClasspath = project.extensions.getByType<SourceSetContainer>()["main"].output
        inputs.files(pluginClasspath)
        inputs.property("testKotlin", testKotlin)
        systemProperty("composemonitor.supportsPluginOrder", versionAtLeast(line.minKotlin, pluginOrderKotlin))
        systemProperty("composemonitor.supportsVerifyIr", versionAtLeast(testKotlin, "2.0.21"))
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOf("-Dcomposemonitor.pluginClasspath=${pluginClasspath.asPath}")
        })
    }

    extensions.configure<MavenPublishBaseExtension> {
        publishToMavenCentral()
        if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
        coordinates(artifactId = line.artifactId)
        pom {
            name.set("Compose State Monitor compiler plugin for Kotlin ${line.minKotlin}+")
            description.set("Kotlin compiler plugin that instruments @ComposableMonitor functions to log why they recomposed.")
        }
    }
}

// A file repository that compat-test (in the root build) installs the fixture builds' plugin from.
allprojects {
    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "compat"
                url = uri(compatRepo)
            }
        }
    }
}

tasks.register("publishAllToCompatRepo") {
    description = "Publishes the Gradle plugin and every compiler line to build/compat-repo."
    dependsOn(allprojects.map { p -> p.tasks.matching { it.name == "publishAllPublicationsToCompatRepository" } })
}

tasks.register("compilerTests") {
    description = "Runs the compiler plugin tests of every Kotlin line."
    dependsOn(subprojects.map { "${it.path}:test" })
}

tasks.named("check") { dependsOn("compilerTests") }
