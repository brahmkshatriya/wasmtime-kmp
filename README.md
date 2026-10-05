# wasmtime-kmp

[![Maven Central](https://img.shields.io/maven-central/v/dev.brahmkshatriya.wasmtime/lib?label=Maven%20Central)](https://central.sonatype.com/artifact/dev.brahmkshatriya.wasmtime/lib)
[![CI](https://github.com/brahmkshatriya/wasmtime-kmp/actions/workflows/publish.yml/badge.svg)](https://github.com/brahmkshatriya/wasmtime-kmp/actions/workflows/publish.yml)

Run Kotlin/Wasm extensions inside Kotlin Multiplatform apps.

`wasmtime-kmp` gives you:

- a Kotlin API for loading and calling Wasm;
- a Gradle plugin for building Kotlin/Wasm-WASI extensions;
- a Gradle plugin for preparing the shared runtime used by those extensions;
- generated Kotlin proxies, so your host app talks to an extension through a normal Kotlin interface;
- optional HTTP and persistent-storage permissions that the host controls explicitly.

The current release is shown by the Maven Central badge above. In the snippets below, replace `VERSION` with that value.

## Supported hosts

| Host | Available |
| --- | --- |
| Android ARM64 / x86_64 | Yes |
| Linux x64 / ARM64 | Yes |
| Windows x64 | Yes |
| macOS x64 / ARM64 | Yes |
| iOS ARM64 | Yes |
| JVM on Linux x64 / ARM64 | Yes |
| Browser (Wasm/JS) | Yes |

Extensions are built as Kotlin/Wasm-WASI modules.

## Add it to your project

The Gradle plugins and runtime are published to Maven Central.

In `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
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
```

A host module applies the host plugin and depends on the runtime:

```kotlin
plugins {
    kotlin("multiplatform")
    id("dev.brahmkshatriya.wasmtime.host") version "VERSION"
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation("dev.brahmkshatriya.wasmtime:lib:VERSION")
    }
}
```

An extension module applies the extension plugin:

```kotlin
plugins {
    id("dev.brahmkshatriya.wasmtime.extension") version "VERSION"
}
```

Use the same `VERSION` for the library and both plugins.

## 1. Define a shared Kotlin contract

Create a small module that is used by both the host and the extension.

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class ExtensionEntry

interface Plugin {
    suspend fun greeting(): Greeting
}

@kotlinx.serialization.Serializable
data class Greeting(val message: String)
```

The generated contract bridge currently supports public, zero-argument `suspend` functions returning `Unit` or a concrete serializable type.

## 2. Build an extension

Apply the extension plugin and tell it which shared API to use:

```kotlin
plugins {
    id("dev.brahmkshatriya.wasmtime.extension") version "VERSION"
}

wasmtime {
    extensionApi("pluginApi") {
        add(project(":shared"))
    }
}

wasmtimeExtension {
    contractInterface.set("com.example.Plugin")
    entryPointAnnotation.set("com.example.ExtensionEntry")

    compileOnlyDependencies {
        useExtensionApi("pluginApi")
    }
}
```

Then implement the contract normally:

```kotlin
@ExtensionEntry
object MyPlugin : Plugin {
    override suspend fun greeting(): Greeting = Greeting("Hello from Wasm")
}
```

Build the final Wasm file with:

```bash
./gradlew :plugin:exportWasmtimeExtension
```

The output is written to `plugin/build/wasmtime/`.

You only implement the Kotlin interface. The Gradle plugin generates the Wasm-facing glue code for you.

## 3. Prepare the host

Apply the host plugin in the module that will load the extension:

```kotlin
plugins {
    kotlin("multiplatform")
    id("dev.brahmkshatriya.wasmtime.host") version "VERSION"
}

wasmtime {
    extensionApi("pluginApi") {
        add(project(":shared"))
    }
}

wasmtimeHost {
    contractInterface.set("com.example.Plugin")

    runtimeDependencies {
        useExtensionApi("pluginApi")
    }
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation("dev.brahmkshatriya.wasmtime:lib:VERSION")
        implementation(project(":shared"))
    }
}
```

Build the shared runtime with:

```bash
./gradlew :host:buildWasmtimeRuntime
```

The task writes the shared runtime files under `host/build/wasmtime/runtime/`. The included `runtime.tsv` file tells the library which files to load and in what order.

Package that directory and the extension `.wasm` file with your app resources. The demo in this repository shows one way to do that for Compose Multiplatform.

## 4. Load and call the extension

The host plugin generates `PluginWasmtimeProxy` from the shared `Plugin` interface.

Load the generated runtime manifest, create a transport, then use the generated proxy like a normal Kotlin object:

```kotlin
val runtime = loadWasmtimeRuntime(runtimeManifestText) { fileName ->
    readRuntimeResource(fileName)
}

val transport = createWasmtimeExtensionTransport(
    wasm = pluginWasmBytes,
    runtime = runtime,
)

val plugin: Plugin = PluginWasmtimeProxy(transport)
val greeting = plugin.greeting()
```

If your extension does not need network or persistent storage, that is all you need to grant it.

## HTTP access

Extensions do not get unrestricted network access. To allow HTTP, provide a `WasmtimeHttpHandler` and decide which requests are allowed:

```kotlin
val transport = createWasmtimeExtensionTransport(
    wasm = pluginWasmBytes,
    runtime = runtime,
    httpHandler = WasmtimeHttpHandler { request ->
        require(request.method == "GET")
        require(request.url.startsWith("https://api.example.com/"))

        val response = myHttpClient.get(request.url)
        WasmtimeHttpResponse(
            statusCode = response.status.value,
            body = response.bodyAsBytes(),
        )
    },
)
```

Treat this handler as a permission boundary. Validate the destination and redirect behavior before forwarding an extension request.

Kotlin/Wasm extensions can use the published Ktor WASI client normally when you want a Ktor API inside the guest. The demo shows this setup.

## Persistent storage

Grant one directory to an extension with `WasmtimeStorage`:

```kotlin
val storage = WasmtimeStorage(
    backingPath = appDataDirectory,
    guestPath = "/data",
)

val transport = createWasmtimeExtensionTransport(
    wasm = pluginWasmBytes,
    runtime = runtime,
    storage = storage,
)
```

Inside the extension, `/data` behaves like its persistent directory. The extension does not get general access to the host filesystem.

Storage quotas can be changed with `maxBytes`, `maxEntries`, and `maxFileBytes`. Set a quota to `0` only when you intentionally want to disable that particular limit.

## Resource limits

`WasmtimeLimits` controls the main execution limits:

```kotlin
val limits = WasmtimeLimits(
    maxModuleBytes = 16 * 1024 * 1024,
    maxMemoryBytes = 64L * 1024 * 1024,
    fuel = 100_000_000,
    maxExecutionMillis = 30_000,
)
```

Pass it when creating the extension transport:

```kotlin
val transport = createWasmtimeExtensionTransport(
    wasm = pluginWasmBytes,
    runtime = runtime,
    limits = limits,
)
```

The defaults are intended for plugin-style workloads. Tighten them for your application when you know the expected workload.

## Loading plain Wasm without the extension plugins

You can use the runtime directly for a simple Wasm module:

```kotlin
val instance = Wasmtime.load(wasmBytes)
try {
    val add = instance.functionI32("add")
    val result = add(20, 22)
} finally {
    instance.close()
}
```

`functionI32` resolves an exported `(i32, i32) -> i32` function once so it can be reused efficiently.

Use `invokeAsync` / `callI32Async` when the Wasm call can wait on an asynchronous host capability.

## JVM on Linux

On JVM/Linux, add the normal API JAR plus one small native runtime JAR for the machine that will run your app:

```kotlin
implementation("dev.brahmkshatriya.wasmtime:lib-jvm:VERSION")
runtimeOnly("dev.brahmkshatriya.wasmtime:lib-jvm:VERSION:linux-x64")
```

For Linux ARM64, use the `linux-arm64` classifier instead.

Kotlin Multiplatform native targets do not need this extra classifier.

## Example project

The `demo` directory contains two real Kotlin/Wasm extensions and host apps for Android, Linux, JVM, and the browser.

Useful commands:

```bash
# Build both extensions and the shared runtime
./gradlew \
  :demo:plugin1:exportWasmtimeExtension \
  :demo:plugin2:exportWasmtimeExtension \
  :demo:client:buildWasmtimeRuntime

# Linux app
./gradlew :demo:apps:linux:linkReleaseExecutableLinuxX64

# JVM app
./gradlew :demo:apps:jvm:run

# Browser app
./gradlew :demo:apps:web:wasmJsBrowserDevelopmentRun
```

The demo also shows:

- Ktor HTTP from inside a Wasm/WASI extension;
- host-side URL filtering;
- per-extension persistent storage;
- packaging the generated runtime and extension Wasm files as app resources.

## Building this repository

Use JDK 21.

The normal verification entry points are Gradle tasks in this repository. Platform-specific native artifacts are built on matching CI runners before the Maven repository is merged and checked.

The project version is not stored in source. For a local publication, provide it explicitly:

```bash
export VERSION=your-version
export WASMTIME_KMP_VERSION="$VERSION"
export WASMTIME_MAVEN_REPOSITORY="$PWD/release/maven-repository"

./gradlew -PVERSION="$VERSION" publishWasmtimeCommonToMavenRepository
```

A Git tag named `v<version>` is the release source of truth in CI. The tag build creates and verifies the complete Maven repository before uploading that exact repository to Maven Central.

## License

Apache License 2.0.
