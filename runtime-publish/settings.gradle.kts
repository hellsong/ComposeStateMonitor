// Publishes io.github.hellsong:composemonitorhelper. It is built with Kotlin 2.0 so that its klibs (iOS, JS, Wasm)
// can be read by every supported Kotlin version: a newer compiler can't write klibs that older ones accept. That needs
// an older Gradle than the main build, hence a separate build. It compiles the sources of ../composemonitorhelper,
// which the main build uses for the samples and tests.
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
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
rootProject.name = "composemonitorhelper"
