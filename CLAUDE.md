# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Gradle plugin, Kotlin compiler plugin and Compose Multiplatform runtime library that log **why a `@Composable` function recomposed**, on every Compose Multiplatform target (Android, desktop/JVM, iOS, wasmJs, js). You annotate a `@Composable` with `@ComposableMonitor`. The compiler plugin rewrites the function's IR so every exit reports its parameters and `State` locals to `ComposeMonitorInspector`, a tracker adapted from the Rebugger library. The tracker logs value changes under the tag `ComposeMonitorInspector`: through `Log.d` (logcat) on Android and `println` elsewhere.

`README.md` is the user-facing setup guide; `docs/implementation.md` describes the design in depth, including how the Kotlin version support works.

Supported by users: Kotlin 2.0.0+, Gradle 7.6+, Compose runtime 1.6.11+. That support shapes most of the build layout below.

## Build layout

- `plugin/`: **included build** (`includeBuild("plugin")` in root `settings.gradle.kts`), not a subproject. It reuses the root `gradle/libs.versions.toml` through its own `settings.gradle.kts`. Changes to plugin sources apply on the next sample build without publishing.
  - root project: the Gradle plugin `io.github.hellsong.composemonitor` (`ComposeStateMonitorGradlePlugin`, a `KotlinCompilerPluginSupportPlugin`). It is **Java** (Java 8 bytecode), compiled against KGP API 2.0.0, so old Gradle versions can load it; keep it that way. Its `BuildConfig` constants are generated from `plugin/build.gradle.kts`.
  - `:composemonitor-compiler-kNN` (`k20`, `k21`, `k2120`, `k22`, `k23`, `k24`): the compiler plugin, **one build per Kotlin line**. Each line project is just a directory `plugin/compiler/kNN` with a stub build file. Everything is configured from the `compilerLines` table in `plugin/build.gradle.kts`: the shared sources in `plugin/compiler/shared`, the line's compat sources from `plugin/compiler/compat/<name>/{kotlin,resources}`, `compileOnly` against the line's lowest Kotlin, `languageVersion`/`apiVersion` = the line, and tests against the line's Kotlin with a matching kctfork. KGP resolves `io.github.hellsong:composemonitor-compiler-kNN`, and the composite build substitutes the subproject because group and project name match. Don't rename the line projects.
- `composemonitorhelper/`: KMP runtime library (AGP KMP library plugin plus jvm, iosArm64, iosSimulatorArm64, js, wasmJs). It holds the `@ComposableMonitor` annotation, the functions injected code calls (`MonitorRuntime.kt`) and `ComposeMonitorInspector`. Only `defaultLogger` is platform-specific (`expect`/`actual`). This module is **not published**; the samples and tests use it.
- `runtime-publish/`: a **separate Gradle build** (own wrapper, Gradle 8.13, Kotlin 2.0.21, CMP runtime 1.6.11, vanniktech 0.35.0) that compiles `composemonitorhelper/src` and publishes `io.github.hellsong:composemonitorhelper`, also for `iosX64`. Kotlin 2.0 because a newer compiler can't write klibs that Kotlin 2.0/2.1 accept. It reads the root version catalog. Run it with its own `./gradlew` and JDK 17 (it needs the Android SDK via `ANDROID_HOME` or a `local.properties`). Its sources must stay compilable with Kotlin 2.0 and Compose 1.6.11: no newer language features or Compose APIs in `composemonitorhelper/src`.
- `compat-test/`: opt-in TestKit matrix (`-PcompatTest`). It publishes the plugin, all lines and the runtime (`runtime-publish`) to `build/compat-repo`, then builds and runs a fixture project for Kotlin 2.0.0 … 2.4.x × Gradle 7.6 … 9.x. Gradle 7.6 rows run on JDK 17 (toolchain lookup).
- `sample/`: the Android-only demo app.
- `sample-kmp/`: the Compose Multiplatform demo. Shared composables live in `commonMain/App.kt`, with Android, desktop, iOS framework and wasm entry points. `commonTest` holds an end-to-end UI test that asserts on the logged reasons.

Versions come from `gradle/libs.versions.toml` (Kotlin 2.3.21, Compose Multiplatform 1.11.1, AGP 8.10.0, JVM toolchain 21, Gradle 9.1.0 wrapper). The root `build.gradle.kts` applies the JVM toolchain to every subproject. Keep the androidx `compose-bom` on the same runtime line as Compose Multiplatform. CMP 1.11 needs `minSdk` 23 and doesn't publish `iosX64`.

## Commands

```shell
./gradlew -p plugin build                        # Gradle plugin + all compiler lines + their tests
./gradlew -p plugin compilerTests                # compiler-plugin tests of every line (kctfork)
./gradlew -p plugin :composemonitor-compiler-k20:test -PcomposeMonitor.testKotlin.k20=2.0.0   # a line vs another patch
./gradlew :compat-test:test -PcompatTest         # Kotlin x Gradle matrix against the published artifacts (slow)
./gradlew :composemonitorhelper:allTests
./gradlew :sample-kmp:desktopTest                # end-to-end UI test; needs enabled = true in sample-kmp/build.gradle.kts
./gradlew :sample-kmp:run                        # desktop app, logs to stdout
./gradlew :sample:installDebug                   # then: adb logcat -s ComposeMonitorInspector
./gradlew :sample-kmp:linkDebugFrameworkIosSimulatorArm64 :sample-kmp:wasmJsBrowserDistribution
./gradlew -p plugin publishToMavenLocal && (cd runtime-publish && ./gradlew publishToMavenLocal)
```

Native targets need full Xcode, not just CommandLineTools. If `xcode-select -p` points at CommandLineTools, prefix the command with `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`. Linking iOS test executables needs an Xcode whose SDK is new enough for CMP 1.11's UIKit code.

To inspect instrumentation:
- Set `composeStateMonitor { verbose = true }`, which prints each transformed function's IR as a compiler warning.
- Or `javap -c -p sample/build/tmp/kotlin-classes/debug/.../MainActivityKt.class | grep -E "monitorAdd|CollectAndPrintValues"`. Release classes must contain neither.

## Architecture

```kotlin
plugins { alias(libs.plugins.composeStateMonitor) }   // plus org.jetbrains.kotlin.plugin.compose, which is required
composeStateMonitor {
    includeClassPackage = listOf("your.package")       // empty = every @ComposableMonitor function
    // enabled, androidBuildTypes (default "debug"), includeTestCompilations, verbose
}
```

1. **Gradle plugin** (`plugin/src/main/java/.../gradle/`):
   - **Compiler line selection** (`KotlinVersionSupport`, table from `BuildConfig.COMPILER_LINES`). It picks the line with the highest minimum version ≤ the project's KGP version. Below 2.0.0 it fails; above the newest line's minor it warns and uses the newest line. It logs the choice at info level.
   - **`isApplicable`** skips metadata and test compilations. For Android it matches the variant name against `androidBuildTypes` (it ends with the build type, so flavors such as `freeDebug` count). The AGP KMP library plugin's single `main` compilation is always applicable.
   - **`applyToCompilation`** passes `enabled`, `verbose` and one `includePackage` option per package. On Kotlin 2.3+ it also adds `-Xcompiler-plugin-order=io.github.hellsong.composemonitor>androidx.compose.compiler.plugins.kotlin`.
2. **Compiler plugin** (`plugin/compiler/`):
   - **Entry points.** The line's registrar and `ComposeStateMonitorCommandLineProcessor` are loaded from `META-INF/services` (the registrar's service file is in its compat source) and register `ComposeStateMonitorIrExtension` through `registerMonitorExtension`.
   - **Forward compatibility.** `LinkageError`s during registration or transformation (an IR API change in an untested Kotlin) become a warning and the code stays uninstrumented. `MonitorFunctionTransformer` must never modify existing IR before its replacement is complete, so the IR stays valid when that happens.
   - **Function filter.** It collects `@ComposableMonitor` functions that match the package filter: `fqName == prefix` or `startsWith("$prefix.")`. It warns and skips functions that aren't `@Composable`, are inline or `expect`, or have no body.
   - **`MonitorFunctionTransformer`** inserts, before every `return` targeting the function and at the end of a Unit body:
     ```
     val l = monitorNewList(); monitorAdd(l, "name", value)...; CollectAndPrintValues("Fn", l)
     ```
   - **What gets tracked.** Regular and context parameters, plus `State` locals declared **directly in the body before that exit**. For `var x by remember { mutableStateOf() }` it tracks the delegate under the name `x`. Nested-block locals are deliberately excluded, so each call site always passes the same list shape (see below).
   - **What is not covered.** Returns inside lambdas, local declarations and `try` blocks are left alone.
3. **Compose compiler** then lowers the injected `CollectAndPrintValues` call like any user call, adding `$composer`/`$changed`.
4. **Runtime** (`composemonitorhelper`):
   - **`CollectAndPrintValues`** is `@NonSkippableComposable`. It unwraps `State` values inside `Snapshot.withoutReadObservation` and strips any `$delegate` suffix. Reading the values normally would make the logger's own scope recompose and log changes the monitored function never saw.
   - **`ComposeMonitorInspector`** remembers the previous track map in one slot and compares entries by key. It logs, and stores the new baseline, in a `SideEffect`. It holds no state, so it never recomposes by itself. Value types (numbers, strings, enums…) never produce "instance changed".
   - **"activated on `X`"** is logged when a call site first enters composition, for example when a different exit is taken.

### Coupling to keep in sync

- **Runtime ABI.**
  - `MonitorSymbols` finds `monitorNewList`, `monitorAdd` and `CollectAndPrintValues` by `CallableId` in `io.github.hellsong.composemonitor.helper`, checking their regular-parameter counts and `@Composable`. It finds the annotation by `ClassId`.
  - If you rename or change any of these in `MonitorRuntime.kt` / `ComposableMonitor.kt`, update `MonitorSymbols` and the runtime stub in `MonitorFunctionTransformerTest`.
  - A missing or mismatched runtime is reported as a compiler error.
- **Plugin ID** `io.github.hellsong.composemonitor` appears in several places that must match:
  - `PLUGIN_ID` (compiler)
  - `compilerPluginId` in `plugin/build.gradle.kts`, which feeds `BuildConfig`
  - the `-Xcompiler-plugin-order` constraint
- **Ordering.** The transform must run before Compose's IR pass. If it doesn't, the function already has a `$composer` parameter, and the extension reports an error instead of transforming. Each line gets there differently: `-Xcompiler-plugin-order` on 2.3+ (compat `registrar`); insertion at index 0 of the shared `ExtensionStorage` on 2.1–2.2 (`registrar-legacy`); a legacy `ComponentRegistrar` with `LoadingOrder.FIRST` on 2.0, where Compose itself is a `ComponentRegistrar` (`registrar-component`).
- **Cross-version IR rules for `plugin/compiler/shared`.** It is compiled against every line, so use only APIs with the same JVM signature everywhere, and put the rest in compat sources (`params*`, `lookup-*`, `registrar*`). Known traps: `IrElement.transform/accept` (descriptor changed in 2.2; use `transformChildrenVoid`), the IR visitor classes (use `IrElementTransformerVoid`), `IrConst` (generic before 2.2), `hasAnnotation(ClassId)` (use `FqName`), `CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY` (missing in 2.0; use `CLIConfigurationKeys`), `IrLocalDelegatedProperty.delegate` (nullable since 2.3), `CompilerConfiguration.appendList`, and inline helpers such as `deepCopyWithSymbols` (inlined internals broke between 2.0.0 and 2.0.21).
- **Patch releases can break the ABI.** 2.1.20 compiled `IrGenerationExtension` with JVM default methods, hence the separate `k2120` line. Lines are compiled against their lowest version. After changing compiler code, check each line's classes against every patch it covers: run the tests with `-PcomposeMonitor.testKotlin.kNN=…`, and compare the compiler members referenced by `javap -c` on `plugin/compiler/kNN/build/classes` with each `kotlin-compiler-embeddable` jar.
- **New Kotlin version.** Add `plugin/compiler/kNN` + a `compilerLines` row (kctfork built for that Kotlin, a Compose runtime its compiler can read), compat sources if needed, and a `CompatibilityTest` row. The main build's own Kotlin (`kotlin` in the catalog) is independent of this. The compiler tests use a runtime stub with `@NonSkippableComposable` like the real one: Compose 2.4 skips equal-argument calls otherwise.

### Release builds on non-Android targets

Only Android has a debug/release split at compile time. iOS, desktop and web outputs are instrumented whenever `enabled` is true. Gate them from Gradle, for example:

```kotlin
enabled = providers.gradleProperty("composeMonitor").map(String::toBoolean).orElse(false)
```

## Publishing

Artifacts, versioned by `composemonitorplugin` in the catalog:
- `io.github.hellsong:composemonitor`, the Gradle plugin. Its artifactId is set with `coordinates(artifactId = ...)` so the plugin marker points at it.
- `io.github.hellsong:composemonitor-compiler-kNN`, one per compiler line.
- `io.github.hellsong:composemonitorhelper`, the KMP runtime, published **from `runtime-publish/`** on macOS so the iOS klibs are included.

All apply `com.vanniktech.maven.publish` and target Maven Central (namespace `io.github.hellsong`). The shared POM fields (`POM_*`) are in `gradle.properties`, `plugin/gradle.properties` and `runtime-publish/gradle.properties`, because the separate builds don't read the root file. Keep them in sync. Per-module name and description are in each `mavenPublishing {}` block.
- `publishToMavenLocal` works without credentials. Signing is applied only when the `signingInMemoryKey` Gradle property is set.
- To release, put `mavenCentralUsername`, `mavenCentralPassword`, `signingInMemoryKey`, `signingInMemoryKeyId` and `signingInMemoryKeyPassword` in `~/.gradle/gradle.properties`. Run `./gradlew :compat-test:test -PcompatTest` first. Then, from macOS, run `./gradlew -p plugin publishAndReleaseToMavenCentral` and `cd runtime-publish && ./gradlew publishAndReleaseToMavenCentral` (with JDK 17+ and full Xcode). Use `publishToMavenCentral` instead to stage the uploads and release them from the portal.
- Plugin Portal publishing (`:plugin:publishPlugins`) is optional and needs `gradle.publish.key` / `gradle.publish.secret`, as described in the README.
