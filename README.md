# Compose State Monitor

Logs **why a `@Composable` function recomposed**. Annotate a composable with `@ComposableMonitor`, and a compiler plugin makes it report its parameters and `State` locals every time it runs. Whenever one of them changed, you get a log line naming it and showing the old and new value.

Works on every Compose Multiplatform target: Android, desktop (JVM), iOS, wasmJs and js.

```
MainScreen recomposed because
	 `text` changed from `haha!` to `haha! count = 1`,
CounterLabel recomposed because
	 `count` changed from `0` to `1`,
```

Logs use the tag `ComposeMonitorInspector`: `Log.d` (logcat) on Android and `println` elsewhere.

## Setup

Requirements:

- Kotlin 2.0.0 or newer, with the Compose compiler Gradle plugin (`org.jetbrains.kotlin.plugin.compose`). The compiler IR API isn't stable, so the plugin ships one build per Kotlin line (2.0, 2.1, 2.1.20, 2.2, 2.3, 2.4) and picks the right one. On a Kotlin version newer than the newest line it warns and uses that line; if that build no longer works with the new compiler, it warns and leaves your code uninstrumented rather than failing the build.
- Gradle 7.6 or newer.
- Compose runtime 1.6.11 / Compose Multiplatform 1.6.11 or newer.

**1. Apply the Gradle plugin.**

```kotlin
// build.gradle.kts of your app / shared module
plugins {
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.hellsong.composemonitor") version "0.2.0"
}

composeStateMonitor {
    // Only instrument composables under these packages. Empty (default) = every @ComposableMonitor function.
    includeClassPackage = listOf("com.example.app")
}
```

The plugin is published to Maven Central, so make sure `mavenCentral()` is in `pluginManagement { repositories { ... } }` in `settings.gradle.kts`.

**2. Add the runtime library.** It holds the `@ComposableMonitor` annotation.

```kotlin
// Kotlin Multiplatform
kotlin.sourceSets.commonMain.dependencies {
    implementation("io.github.hellsong:composemonitorhelper:0.2.0")
}

// Android-only module
dependencies {
    implementation("io.github.hellsong:composemonitorhelper:0.2.0")
}
```

**3. Annotate your composables.**

```kotlin
import io.github.hellsong.composemonitor.helper.ComposableMonitor

@ComposableMonitor
@Composable
fun CounterLabel(count: Int) {
    BasicText("count: $count")
}
```

Run the app and read the logs:

```shell
adb logcat -s ComposeMonitorInspector      # Android
./gradlew :your-app:run                    # desktop: logs go to stdout
```

## What gets reported

- Regular and context parameters of the function.
- `State` locals declared directly in the function body before the exit, such as `var x by remember { mutableStateOf(0) }`. The name in the log is `x`.
- Every `return` of the function (and the end of a `Unit` body) reports separately. If the function takes a different exit than last time, you get ``activated on `CounterLabel` `` instead of a recomposition reason.
- Lambdas, local functions and `try` blocks are not instrumented, and neither are `State` locals declared in nested blocks.
- The annotated function must be `@Composable`, not `inline` or `expect`, and have a body. Otherwise the compiler plugin warns and skips it.

A log line like ``recomposed, but reason is unknown`` means the function recomposed without any tracked parameter or `State` local changing, for example because it reads state that isn't tracked.

## Configuration

```kotlin
composeStateMonitor {
    enabled = true                       // master switch
    includeClassPackage = listOf("...")  // package or class name prefixes
    androidBuildTypes = setOf("debug")   // Android build types that are instrumented
    includeTestCompilations = false      // also instrument test compilations
    verbose = false                      // print each transformed function's IR as a compiler warning
}
```

Before Gradle 8.2, the Kotlin DSL can't assign properties with `=`; use `includeClassPackage.set(listOf("..."))` and so on.

**Release builds.** Only Android has a debug/release split at compile time: with the default `androidBuildTypes`, release builds contain no monitoring code. Desktop, iOS and web outputs are instrumented whenever `enabled` is true, so gate them from Gradle:

```kotlin
composeStateMonitor {
    enabled = providers.gradleProperty("composeMonitor").map(String::toBoolean).orElse(false)
}
```

Then build with `-PcomposeMonitor=true` when you want the logs.

**Custom logger.** Redirect the output, for example into your own logging library:

```kotlin
import io.github.hellsong.composemonitor.helper.ComposeMonitorInspectorConfig

ComposeMonitorInspectorConfig.init(
    tag = "Recomposition",
    logger = { tag, message -> MyLogger.d(tag, message) },
)
```

## Samples

- `sample/`: Android-only demo app (`./gradlew :sample:installDebug`).
- `sample-kmp/`: Compose Multiplatform demo for Android, desktop, iOS and wasm (`./gradlew :sample-kmp:run`).

## How it works

A Kotlin compiler plugin rewrites each `@ComposableMonitor` function's IR so every exit passes its parameters and `State` locals to the runtime's `CollectAndPrintValues`. The runtime compares them with the previous values and logs what changed. The idea and the change tracker are adapted from [Rebugger](https://github.com/theapache64/rebugger). See [`docs/implementation.md`](docs/implementation.md) for details.

## Publishing

Artifacts are published to Maven Central under `io.github.hellsong`: `composemonitor` (Gradle plugin), `composemonitor-compiler-k20` … `composemonitor-compiler-k24` (compiler plugin per Kotlin line) and `composemonitorhelper` (runtime). Put `mavenCentralUsername`, `mavenCentralPassword`, `signingInMemoryKey`, `signingInMemoryKeyId` and `signingInMemoryKeyPassword` in `~/.gradle/gradle.properties`, then run from macOS:

```shell
./gradlew -p plugin publishToMavenCentral   # Gradle plugin + every compiler line
cd runtime-publish && ./gradlew publishToMavenCentral   # runtime, built with Kotlin 2.0 (needs JDK 17+ and Xcode)
```

The runtime is published from `runtime-publish/`, a separate build with an older Gradle and Kotlin 2.0, so that every supported Kotlin version can read its iOS/JS/Wasm klibs. Each command stages an upload. Review them and click **Publish** in the Central portal.

Before a release, `./gradlew :compat-test:test -PcompatTest` builds and runs a test project with every supported Kotlin line and Gradle versions from 7.6 up, against the artifacts as they will be published.

## License

Apache License 2.0, see [`LICENSE`](LICENSE).
