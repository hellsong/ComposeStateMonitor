plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
}

// Used by the samples and tests of this build. The published artifact is built from the same sources by
// ../runtime-publish, with Kotlin 2.0 so that every supported Kotlin version can read it.

group = "io.github.hellsong"
version = libs.versions.composemonitorplugin.get()

kotlin {
    androidLibrary {
        namespace = "io.github.hellsong.composemonitor.helper"
        compileSdk = 36
        minSdk = 23
    }
    jvm()
    // Compose Multiplatform 1.11 no longer publishes iosX64.
    iosArm64()
    iosSimulatorArm64()
    js { browser() }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.mp.runtime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
