# How the Compose State Monitor works

The monitor logs **why a `@Composable` function recomposed**. You annotate a composable with `@ComposableMonitor`, and on every recomposition it logs which parameters or `State` locals changed. It works on every Compose Multiplatform target: Android, desktop (JVM), iOS, wasmJs and js.

```kotlin
@ComposableMonitor
@Composable
fun MainScreen(text: String) {
    var text2 by remember { mutableStateOf("Hello World!") }
    ...
}
```

```
ComposeMonitorInspector: activated on `MainScreen`
ComposeMonitorInspector: MainScreen recomposed because
	 `text` changed from `haha!` to `haha! count = 1`,
```

## Overview

Three artifacts work together:

| Artifact | Module | Role |
|---|---|---|
| `io.github.hellsong:composemonitor` | `plugin/` | Gradle plugin (Java). Picks the compiler plugin build for the project's Kotlin version, decides which compilations get instrumented and passes options to the compiler plugin. |
| `io.github.hellsong:composemonitor-compiler-kNN` | `plugin/compiler/` | Kotlin compiler plugin, one build per supported Kotlin line. Rewrites the IR of `@ComposableMonitor` functions. |
| `io.github.hellsong:composemonitorhelper` | `composemonitorhelper/` (sources), `runtime-publish/` (published build) | KMP runtime library. Holds the annotation, the functions the generated code calls, and the change tracker. |

Supported: Kotlin 2.0.0 and newer, Gradle 7.6 and newer, Compose runtime 1.6.11 and newer. See [Kotlin and Gradle versions](#kotlin-and-gradle-versions).

The pipeline for one source file:

```
 Kotlin source ──► FIR (frontend) ──► IR
                                       │
                                       ▼
                ┌──────────────────────────────────────┐
                │ composemonitor IR extension          │  inserts plain @Composable calls
                │ (MonitorFunctionTransformer)         │  before every exit
                └──────────────────────────────────────┘
                                       │   -Xcompiler-plugin-order
                                       ▼
                ┌──────────────────────────────────────┐
                │ Compose compiler IR extension        │  adds $composer/$changed, groups,
                │                                      │  skipping, to user code AND ours
                └──────────────────────────────────────┘
                                       │
                                       ▼
            JVM / Android bytecode, Kotlin/Native, wasm, js backends
```

Because the instrumentation happens in IR, before any backend runs, the same code serves all targets. The previous version rewrote JVM bytecode with ASM, which could only reach Android.

## 1. Gradle plugin

Source: `plugin/src/main/java/io/github/hellsong/composemonitor/gradle/`.

It is written in Java and compiled against the oldest supported KGP API (2.0.0) with Java 8 bytecode. A Kotlin plugin would carry Kotlin metadata and stdlib calls that the older embedded Kotlin of older Gradle versions can't read.

`ComposeStateMonitorGradlePlugin` implements KGP's `KotlinCompilerPluginSupportPlugin`. KGP calls it for every Kotlin compilation in the project.

### Configuration

```kotlin
plugins {
    alias(libs.plugins.compose.compiler)       // required
    alias(libs.plugins.composeStateMonitor)
}

composeStateMonitor {
    includeClassPackage = listOf("your.package") // empty = every @ComposableMonitor function
    enabled = true                               // master switch
    androidBuildTypes = setOf("debug")           // Android build types to instrument
    includeTestCompilations = false
    verbose = false                              // print transformed IR as compiler warnings
}
```

All properties are lazy Gradle `Property`s (`ComposeStateMonitorExtension`), so they can be wired to providers, for example a Gradle property.

### `apply`

- Creates the `composeStateMonitor` extension.
- **Picks the compiler plugin build** once the Kotlin plugin is applied (`KotlinVersionSupport`). It takes the line with the highest minimum version that isn't newer than the project's KGP version, ignoring suffixes such as `-RC`. Below Kotlin 2.0.0 it fails the build. Above the newest line's minor version it warns that the version is untested and uses the newest line.

### `isApplicable(compilation)`

A compilation is instrumented only if all of these hold:

1. `enabled` is true.
2. It is not a metadata (`common`) compilation. These never reach an IR backend.
3. It is not a test compilation (`test`, or a name ending in `Test`), unless `includeTestCompilations` is set.
4. For Android (`androidJvm`) compilations, the compilation name is the variant name, such as `debug` or `freeDebug`. It must equal or end with one of `androidBuildTypes`, so flavored variants count. The one exception is the AGP KMP library plugin's single `main` compilation, which has no build types and is always applicable.

It throws if `org.jetbrains.kotlin.plugin.compose` isn't applied, because without the Compose compiler the injected composable calls would never be lowered.

### `applyToCompilation(compilation)`

- Passes the compiler plugin options `enabled`, `verbose` and one `includePackage` per configured package.
- On Kotlin 2.3+, adds `-Xcompiler-plugin-order=io.github.hellsong.composemonitor>androidx.compose.compiler.plugins.kotlin` to the compile task. See [Ordering](#ordering-relative-to-the-compose-compiler).

`getPluginArtifact()` returns `io.github.hellsong:composemonitor-compiler-kNN:<version>` for the selected line. Inside this repository the composite build substitutes the `:composemonitor-compiler-kNN` subproject for those coordinates automatically, because its group and project name match. Kotlin/Native uses the embeddable compiler too, so one artifact per line serves every target.

`BuildConfig` (generated by the `com.github.gmazzo.buildconfig` plugin from `plugin/build.gradle.kts`) holds the group, version, plugin ID, the first Kotlin version with `-Xcompiler-plugin-order`, and the table of compiler lines.

## 2. Compiler plugin

Source: `plugin/compiler/shared/src/main/kotlin/io/github/hellsong/composemonitor/compiler/`, plus the compat sources in `plugin/compiler/compat/`. See [Kotlin and Gradle versions](#kotlin-and-gradle-versions) for how they are combined.

### Entry points

- `ComposeStateMonitorCommandLineProcessor` parses the `-P plugin:io.github.hellsong.composemonitor:<option>=<value>` options into `MonitorConfigurationKeys`.
- The registrar (`supportsK2 = true`) registers `ComposeStateMonitorIrExtension` as an `IrGenerationExtension`, unless `enabled` is false. Which registrar class that is depends on the Kotlin line, because each one also has to run before the Compose compiler (see [Ordering](#ordering-relative-to-the-compose-compiler)).
- Both are discovered through `META-INF/services`. The command line processor reports `pluginId = "io.github.hellsong.composemonitor"`, and so does the registrar on Kotlin 2.3+.

### `ComposeStateMonitorIrExtension`

For each file in the module:

1. **Collect** every `IrSimpleFunction` annotated with `@ComposableMonitor` whose fully qualified name matches the package filter: `fqName == prefix` or `fqName.startsWith("$prefix.")`. A prefix can therefore be a package or a single function.
2. **Validate** each function. It warns and leaves the function untouched if it isn't `@Composable`, is `inline`, is `expect`, or has no block body.
3. **Ordering guard.** If the function already has a `$composer` parameter, the Compose compiler has already lowered it. That means the plugin order is wrong, so it reports an error instead of producing broken code.
4. **Resolve runtime symbols** through `MonitorSymbols` and the line's `SymbolLookup` (`finderForSource(file)` on 2.4+, `referenceClass`/`referenceFunctions` before). If the runtime is missing or has an unexpected shape, report a compiler error and skip the file.
5. **Transform** with `MonitorFunctionTransformer`. If `verbose` is set, print the resulting IR (`function.dump()`) as a warning.

If any of this throws a `LinkageError`, which is what an IR API change in a newer, untested Kotlin version looks like, the extension stops and reports a warning instead of failing the build. The transformer never modifies existing IR before its replacement is complete, so the IR is still valid at that point, if partly instrumented. Registration is guarded the same way.

### `MonitorSymbols`

Looks up the runtime ABI in `io.github.hellsong.composemonitor.helper`:

| Symbol | Lookup | Check |
|---|---|---|
| `ComposableMonitor` | `ClassId` | annotation match |
| `androidx.compose.runtime.State` | `ClassId` | used to recognise tracked locals |
| `monitorNewList` | `CallableId` | 0 regular parameters |
| `monitorAdd` | `CallableId` | 3 regular parameters |
| `CollectAndPrintValues` | `CallableId` | 2 regular parameters, `@Composable` |

### `MonitorFunctionTransformer`

For one function, it inserts a *report* before every exit:

```kotlin
val monitorValues = monitorNewList()
monitorAdd(monitorValues, "text", text)        // each regular / context parameter
monitorAdd(monitorValues, "text2", text2$delegate) // each State local declared so far
CollectAndPrintValues("MainScreen", monitorValues)
```

**Exits that get a report:**

- every `return` whose target is the function, including returns nested in `if`/`when` blocks;
- the end of the body, for Unit functions whose last statement isn't already a `return` or `throw`.

For a `return` with a value, the value is evaluated into a temporary first (`monitorReturn`), then the report runs, then a new `return` returns the temporary. That keeps evaluation order and the returned value unchanged. A `return` that is a direct top-level statement of the body stays a top-level statement, because the Compose compiler rewrites the trailing return of value-returning composables and expects to find it there.

**What gets tracked:**

- Regular and context parameters, under their declared names.
- `State` locals declared **directly in the function body, before the statement containing that exit**:
  - `val s = remember { mutableStateOf(..) }` is tracked as `s` (any `IrVariable` whose type is a subtype of `State`);
  - `var x by remember { mutableStateOf(..) }` is an `IrLocalDelegatedProperty`; its backing `x$delegate` variable is tracked under the name `x`;
  - top-level destructuring declarations (`IrComposite`) are flattened.

The body is walked statement by statement, so an exit only sees states declared before it. States declared inside nested blocks are deliberately not tracked: if they were, a given exit could pass different sets of values on different recompositions, so a state would show up as "added" or silently disappear from the comparison (see [Runtime](#3-runtime-library)).

**What is left alone:**

- returns inside lambdas and local functions or classes, because they return from something else. Non-local returns from inline lambdas are therefore not reported;
- returns inside `try` blocks, because Compose doesn't allow composable calls inside `try`.

The transformer only *inserts* IR and never copies or rebuilds existing calls; the only new node that replaces an existing one is that `return`. Inserted calls use the source offsets of the exit they belong to, because the Compose compiler derives group keys and source information from call offsets. Every inserted call targets a non-generic top-level function, so no type arguments are needed, and backends box primitive arguments to `Any?` themselves.

### Ordering relative to the Compose compiler

The injected `CollectAndPrintValues(...)` call is an ordinary `@Composable` call with no `$composer`. It is only valid **before** the Compose compiler's IR pass, which then lowers it like any user-written call: it adds `$composer`/`$changed` and wraps it in a group. On the JVM the final call is:

```
INVOKESTATIC io/github/hellsong/composemonitor/helper/MonitorRuntimeKt.CollectAndPrintValues
    (Ljava/lang/String;Ljava/util/List;Landroidx/compose/runtime/Composer;I)V
```

IR extensions run in the order they are registered in the project. How the plugin gets ahead of the Compose compiler depends on the Kotlin line:

| Kotlin | Mechanism | Compat source |
|---|---|---|
| 2.3+ | `-Xcompiler-plugin-order=<before>><after>` orders compiler plugins by `pluginId`. The Gradle plugin adds it to every instrumented compilation, with the Compose compiler's ID `androidx.compose.compiler.plugins.kotlin` second. | `registrar` |
| 2.1–2.2 | No such flag. Every `CompilerPluginRegistrar`, the Compose compiler's included, adds its extensions to one shared `ExtensionStorage`, which is then registered in list order. The registrar inserts its extension at index 0 of that list. | `registrar-legacy` |
| 2.0 | The Compose compiler is a legacy `ComponentRegistrar`, which registers straight into the project before any `CompilerPluginRegistrar` runs. The plugin is a `ComponentRegistrar` too and registers with `LoadingOrder.FIRST`. | `registrar-component` |

If the order is ever broken, the `$composer` guard turns the problem into a clear compiler error.

## 3. Runtime library

Source: `composemonitorhelper/src/commonMain/kotlin/io/github/hellsong/composemonitor/helper/`.

`composemonitorhelper` is a KMP library: `com.android.kotlin.multiplatform.library` for Android, plus `jvm`, `iosX64`, `iosArm64`, `iosSimulatorArm64`, `js` and `wasmJs`. It depends only on `org.jetbrains.compose.runtime:runtime` (and coroutines, which that uses).

The published artifact is built by `runtime-publish/`, a separate Gradle build with its own wrapper (Gradle 8.13), Kotlin 2.0.21 and Compose runtime 1.6.11, from the same sources. A compiler can read klibs from older compilers but not from newer ones: a Kotlin 2.3 compiler can't write iOS/JS/Wasm klibs that Kotlin 2.0 or 2.1 accepts. The `composemonitorhelper` module of the main build compiles the same sources with the main build's Kotlin and Compose versions, for the samples and tests. It isn't published.

### `ComposableMonitor.kt`

```kotlin
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class ComposableMonitor
```

`BINARY` retention keeps the annotation in klibs and class files, but it isn't visible to reflection.

### `MonitorRuntime.kt`: the compiler ABI

These functions are marked `@InternalComposeMonitorApi` (a `@RequiresOptIn` marker), because only generated code should call them.

- `monitorNewList()` returns a fresh `MutableList<Pair<String, Any?>>`.
- `monitorAdd(list, name, value)` appends `name to value`.
- `CollectAndPrintValues(composableName, nameValueList)` is `@Composable @NonSkippableComposable`. It converts the list to an ordered map (`toTrackMap`) and calls `ComposeMonitorInspector`.

`toTrackMap` unwraps every `State<*>` to its current value **inside `Snapshot.withoutReadObservation`**, and strips any trailing `$delegate` from names. Reading `state.value` normally would subscribe `CollectAndPrintValues`'s own restart scope to the state. A change to that state would then recompose only the logger, which would report a change that the monitored function never saw.

`@NonSkippableComposable` makes the call run whenever the monitored function runs, even when its arguments look unchanged, so the comparison always happens.

### `ComposeMonitorInspector.kt`: the change tracker

`ComposeMonitorInspector` is adapted from the Rebugger library. It is `@NonSkippableComposable` and holds no state, so it never recomposes on its own.

- It remembers the track map of the last applied composition in a plain holder (one `remember` slot per call site).
- With no previous map, the call site has just entered composition, and it logs `activated on \`<name>\``.
- Otherwise it compares each entry with the previous value under the same key:
  - key not there before: logs `` `key` added: `new` ``;
  - `old != new`: logs `` `key` changed from `old` to `new` ``;
  - `old !== new` but equal: logs `` `key` instance changed, but content remains the same ``. Numbers, booleans, chars, strings, enums and `Unit` are skipped here, because boxing creates new instances of unchanged values and Compose compares these types by equality anyway.
- If something changed, it logs `<name> recomposed because <changes>`. If the function recomposed but nothing tracked changed, it logs `<name> recomposed, but reason is unknown`.
- The message is built during composition (`describeRecomposition`, a pure function). It is logged, and the new map is stored as the baseline, in a `SideEffect`, so a composition that is discarded neither logs nor moves the baseline.

Each exit of a monitored function is a separate call site with its own slot. When a recomposition takes a different exit than last time, the new exit logs "activated" rather than a reason.

### Logging

`ComposeMonitorInspectorConfig` holds the tag (default `ComposeMonitorInspector`) and the logger function. Use `ComposeMonitorInspectorConfig.init(tag, logger)` to replace them, for example to capture logs in tests.

The default logger is the only platform-specific code (`internal expect fun defaultLogger`):

| Source set | Implementation |
|---|---|
| `androidMain` | `android.util.Log.d(tag, message)` |
| `jvmMain`, `iosMain`, `webMain` (js + wasmJs) | `println("$tag: $message")` |

On Android, read logs with `adb logcat -s ComposeMonitorInspector`. On desktop they go to stdout, on iOS to the Xcode console, and on the web to the browser console.

## Release builds

Only Android has a debug/release split at compile time, which `androidBuildTypes` uses. Desktop jars, iOS frameworks and web bundles each come from a single compilation, so they are instrumented whenever `enabled` is true. Gate those builds from Gradle, for example:

```kotlin
composeStateMonitor {
    enabled = providers.gradleProperty("composeMonitor").map(String::toBoolean).orElse(false)
}
```

The same applies to libraries built with AGP's KMP library plugin, whose single `main` Android compilation is not debug-gated.

## Limitations

- Kotlin versions newer than the newest compiler line are untested. They use the newest line, and if its IR API calls no longer link, the plugin warns and leaves the code uninstrumented.
- Not tracked or reported:
  - `State` declared inside nested blocks;
  - returns inside lambdas, local declarations or `try` blocks, including non-local returns from inline lambdas;
  - inline composables, `expect` declarations and composable lambdas.
- The injected calls add slot-table groups, so the Layout Inspector shows extra nodes in instrumented builds.

## Tests

| Command | What it checks |
|---|---|
| `./gradlew -p plugin compilerTests` | Compiler plugin, with kctfork, once per Kotlin line against that line's compiler and Compose compiler. Loads both plugins through `-Xplugin` with Compose listed first, so the line's ordering mechanism must fix the order. Runs a real `Recomposer` against a stub runtime and checks the reported values for every exit, the package filter, skip warnings, preserved return values, and (2.3+) the error when the order is reversed. `-PcomposeMonitor.testKotlin.kNN=2.x.y` tests a line against another patch release. |
| `./gradlew :composemonitorhelper:allTests` | `toTrackMap` unwrapping, `$delegate` stripping and ordering. |
| `./gradlew :sample-kmp:desktopTest` | End to end: instruments `sample-kmp`'s `App`, clicks a button with `runComposeUiTest`, and asserts on the logged reasons. Needs `enabled = true` in `sample-kmp/build.gradle.kts`. |
| `./gradlew :compat-test:test -PcompatTest` | End to end against the published artifacts (`build/compat-repo`): a JVM project per Kotlin × Gradle combination from Kotlin 2.0.0 / Gradle 7.6 to the newest line, run and checked for the logged reason. `-PcompatTest.multiplatform` adds js/wasmJs/iOS compile rows. |

## Keeping things in sync

- **Runtime ABI.** If you rename or change `monitorNewList`, `monitorAdd`, `CollectAndPrintValues` or `ComposableMonitor`, update `MonitorSymbols` and the runtime stub in `MonitorFunctionTransformerTest`.
- **Plugin ID** `io.github.hellsong.composemonitor` must match in `PLUGIN_ID` (compiler), `compilerPluginId` in `plugin/build.gradle.kts`, and the `-Xcompiler-plugin-order` constraint.
- **New Kotlin versions.** See below.

## Kotlin and Gradle versions

The compiler IR API has no compatibility guarantee, not even between patch releases: for example, 2.1.20 compiled `IrGenerationExtension` with JVM default methods, so a class built against 2.1.0 calls its removed `DefaultImpls`. So the compiler plugin is built once per Kotlin line, each against the lowest version it covers:

| Artifact | Kotlin | Compat sources |
|---|---|---|
| `composemonitor-compiler-k20` | 2.0.x | `params-legacy`, `lookup-reference`, `registrar-component` |
| `composemonitor-compiler-k21` | 2.1.0–2.1.10 | `params-legacy`, `lookup-reference`, `registrar-legacy` |
| `composemonitor-compiler-k2120` | 2.1.20–2.1.x | `params-legacy`, `lookup-reference`, `registrar-legacy` |
| `composemonitor-compiler-k22` | 2.2.x | `params`, `lookup-reference`, `registrar-legacy` |
| `composemonitor-compiler-k23` | 2.3.x | `params`, `lookup-reference`, `registrar` |
| `composemonitor-compiler-k24` | 2.4.x and newer | `params`, `lookup-finder`, `registrar` |

The table lives in `compilerLines` in `plugin/build.gradle.kts`, which configures the `plugin/compiler/kNN` projects and generates the Gradle plugin's `BuildConfig` from it. Each line compiles `plugin/compiler/shared` plus its compat sources, with `languageVersion`/`apiVersion` set to the line (the plugin runs next to the user's compiler and its stdlib) and Java 8 bytecode.

The shared code only uses IR APIs whose JVM signatures are the same in every line, for example `transformChildrenVoid` instead of `transform`, which changed in 2.2, and `IrElementTransformerVoid` instead of the visitor classes. The rest is in the compat sources:

| Compat source | What differs |
|---|---|
| `params-legacy` / `params` | `valueParameters`/`putValueArgument` (gone in 2.4) vs `parameters`/`IrParameterKind`/`arguments` (2.2+) |
| `lookup-reference` / `lookup-finder` | `IrPluginContext.referenceClass`/`referenceFunctions` vs `finderForSource` (missing in 2.3.0) |
| `registrar-component` / `registrar-legacy` / `registrar` | how the plugin runs before the Compose compiler, see [Ordering](#ordering-relative-to-the-compose-compiler) |

To support a new Kotlin version:

1. Add a `plugin/compiler/kNN` directory (with the one-line `build.gradle.kts`) and a `compilerLines` row: the version, the kctfork release built for it, and a Compose runtime its compiler can read.
2. Run `./gradlew -p plugin compilerTests`. If the line doesn't compile, add compat sources.
3. Check that each line's classes link against every patch release it covers, not just the one its tests run on. Run its tests against other patches with `-PcomposeMonitor.testKotlin.kNN=…`, and compare the compiler members its classes reference (`javap -c`) with each `kotlin-compiler-embeddable` jar.
4. Add a row to `CompatibilityTest` and run `./gradlew :compat-test:test -PcompatTest`.

The main build itself (samples, tests) uses the Kotlin version in `gradle/libs.versions.toml`.
