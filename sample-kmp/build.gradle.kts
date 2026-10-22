import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.composeStateMonitor)
}

composeStateMonitor {
    includeClassPackage = listOf("io.github.hellsong.composemonitor.samplekmp")
    enabled = false
}

kotlin {
    androidTarget()
    jvm("desktop")
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        // Embed in an Xcode app and show MainViewController().
        it.binaries.framework {
            baseName = "SampleKmp"
            isStatic = true
        }
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":composemonitorhelper"))
            implementation(libs.compose.mp.runtime)
            implementation(libs.compose.mp.foundation)
            implementation(libs.compose.mp.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.compose.mp.ui.test)
        }
        androidMain.dependencies {
            implementation(libs.compose.activity)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

android {
    namespace = "io.github.hellsong.composemonitor.samplekmp"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.hellsong.composemonitor.samplekmp"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
}

compose.desktop {
    application {
        mainClass = "io.github.hellsong.composemonitor.samplekmp.MainKt"
    }
}
