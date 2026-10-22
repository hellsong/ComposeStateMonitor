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
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
rootProject.name = "plugin"

// One compiler plugin per supported Kotlin line, configured from compilerLines in build.gradle.kts. The project name
// is the artifactId, which lets the root composite build substitute io.github.hellsong:composemonitor-compiler-kNN.
file("compiler").listFiles { dir -> dir.name.matches(Regex("k\\d+")) }!!.sorted().forEach { dir ->
    include(":composemonitor-compiler-${dir.name}")
    project(":composemonitor-compiler-${dir.name}").projectDir = dir
}
