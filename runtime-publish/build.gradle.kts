plugins {
    // Kotlin 2.0.21 is the oldest supported Kotlin line (see the top of settings.gradle.kts).
    kotlin("multiplatform") version "2.0.21"
    kotlin("plugin.compose") version "2.0.21"
    id("com.android.kotlin.multiplatform.library") version "8.10.0"
    // The newest release that supports KGP 2.0 (0.36+ needs KGP 2.2 and Gradle 9).
    id("com.vanniktech.maven.publish") version "0.35.0"
}

group = "io.github.hellsong"
version = libs.versions.composemonitorplugin.get()

val sources = layout.projectDirectory.dir("../composemonitorhelper/src")

kotlin {
    jvmToolchain(17)

    androidLibrary {
        namespace = "io.github.hellsong.composemonitor.helper"
        compileSdk = 35
        minSdk = 21
    }
    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    js { browser() }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain {
            kotlin.srcDir(sources.dir("commonMain/kotlin"))
            dependencies {
                implementation(libs.compose.mp.runtime.floor)
                implementation(libs.kotlinx.coroutines.core.floor)
            }
        }
        commonTest {
            kotlin.srcDir(sources.dir("commonTest/kotlin"))
            dependencies {
                implementation(kotlin("test"))
            }
        }
        androidMain { kotlin.srcDir(sources.dir("androidMain/kotlin")) }
        jvmMain { kotlin.srcDir(sources.dir("jvmMain/kotlin")) }
        iosMain { kotlin.srcDir(sources.dir("iosMain/kotlin")) }
        // Kotlin 2.0's default hierarchy has no shared web source set.
        jsMain { kotlin.srcDir(sources.dir("webMain/kotlin")) }
        wasmJsMain { kotlin.srcDir(sources.dir("webMain/kotlin")) }
    }
}

// Bytecode for JDK 11+, like the Compose runtime itself.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    pom {
        name = "Compose State Monitor runtime"
        description = "Runtime library for Compose State Monitor: the @ComposableMonitor annotation and the recomposition logger."
    }
}

// A file repository that the main build's compat-test installs the fixture builds' runtime from.
publishing {
    repositories.maven {
        name = "compat"
        url = uri(layout.projectDirectory.dir("../build/compat-repo"))
    }
}
