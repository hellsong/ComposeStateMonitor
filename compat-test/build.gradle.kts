import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(gradleTestKit())
    testImplementation(libs.kotlin.test)
}

// Opt-in: it downloads several Gradle distributions and Kotlin versions, and builds runtime-publish.
val compatTestEnabled = providers.gradleProperty("compatTest").isPresent
val compatRepo = rootProject.layout.buildDirectory.dir("compat-repo")
// Gradle 7.6 can't run on a newer JDK. Auto-detected from the usual install locations.
val jdk17 = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }

/** runtime-publish needs an older Gradle than this build, so it runs through its own wrapper. */
val publishRuntime by tasks.registering(Exec::class) {
    val dir = rootDir.resolve("runtime-publish")
    workingDir(dir)
    commandLine(dir.resolve("gradlew").path, "publishAllPublicationsToCompatRepository", "--console=plain")
    val sdkDir = rootDir.resolve("local.properties").takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.reader().use(::load) }.getProperty("sdk.dir") }
    doFirst {
        environment("JAVA_HOME", jdk17.get().metadata.installationPath.asFile.path)
        if (sdkDir != null && System.getenv("ANDROID_HOME") == null) environment("ANDROID_HOME", sdkDir)
    }
}

tasks.test {
    enabled = compatTestEnabled
    if (compatTestEnabled) {
        dependsOn(gradle.includedBuild("plugin").task(":publishAllToCompatRepo"), publishRuntime)
    }
    inputs.dir(compatRepo).optional()
    outputs.upToDateWhen { false }
    systemProperty("compat.repo", compatRepo.get().asFile.path)
    systemProperty("compat.version", libs.versions.composemonitorplugin.get())
    systemProperty("compat.multiplatform", providers.gradleProperty("compatTest.multiplatform").isPresent)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dcompat.javaHome=${jdk17.get().metadata.installationPath.asFile.path}")
    })
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
