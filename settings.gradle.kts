enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "composeStateMonitor"
include(":sample")
include(":sample-kmp")
// Also supplies io.github.hellsong:composemonitor-compiler-kNN to KGP: the subprojects' group and names match them.
includeBuild("plugin")
include(":composemonitorhelper")
// Opt-in Kotlin x Gradle matrix against the published artifacts: ./gradlew :compat-test:test -PcompatTest
include(":compat-test")
