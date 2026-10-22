plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.composeStateMonitor)
}

composeStateMonitor {
    includeClassPackage = listOf("io.github.hellsong.composemonitor.sample")
}

android {
    namespace = "io.github.hellsong.composemonitor.sample"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.hellsong.composemonitor.sample"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
}

kotlin {
    jvmToolchain(libs.versions.gradle.jvmToolchain.get().toInt())
}

dependencies {
    implementation(dependencies.platform(libs.compose.bom))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle)
    implementation(libs.bundles.compose)

    implementation(project(":composemonitorhelper"))
}
