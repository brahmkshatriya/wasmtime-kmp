# wasmtime-kmp

[![Maven Central](https://img.shields.io/maven-central/v/dev.brahmkshatriya.wasmtime/lib?label=Maven%20Central)](https://central.sonatype.com/artifact/dev.brahmkshatriya.wasmtime/lib)
[![CI](https://github.com/brahmkshatriya/wasmtime-kmp/actions/workflows/publish.yml/badge.svg)](https://github.com/brahmkshatriya/wasmtime-kmp/actions/workflows/publish.yml)

Run WebAssembly from Kotlin Multiplatform using Wasmtime.

Use as much or as little of the library as you need:

- load arbitrary Wasm bytes and call exports directly;
- generate a typed Kotlin proxy for Kotlin/Wasm extensions;
- optionally let Gradle build and package extension projects for you;
- optionally expose host services such as HTTP, storage, credentials, logging, and streams.

Replace `VERSION` below with the version shown by the Maven Central badge.

Make sure `mavenCentral()` is available in both `pluginManagement.repositories` and your normal dependency repositories.

## Quick start: run arbitrary Wasm

Add the runtime to `commonMain`:

```kotlin
kotlin {
    sourceSets.commonMain.dependencies {
        implementation("dev.brahmkshatriya.wasmtime:lib:VERSION")
    }
}
```

Then load any Wasm bytes you provide:

```kotlin
val instance = Wasmtime.load(wasmBytes)
try {
    val add = instance.functionI32("add")
    println(add(20, 22))
} finally {
    instance.close()
}
```

`wasmtime-kmp` does not require a particular resource format or packaging strategy. `wasmBytes` can come from app resources, disk, a download, a database, or anywhere else.

Use `invokeAsync` / `callI32Async` when a Wasm call can wait on asynchronous host work.

## Supported hosts

| Host | Support |
| --- | --- |
| Android ARM64 / x86_64 | Yes |
| Linux x64 / ARM64 | Yes |
| Windows x64 | Yes |
| macOS x64 / ARM64 | Yes |
| iOS ARM64 | Yes |
| JVM on Linux x64 / ARM64 | Yes |
| Browser (Wasm/JS) | Yes |

## Optional: typed Kotlin extensions

If both sides are Kotlin, you can describe the extension as a normal suspend interface and let the Gradle plugins generate the Wasm adapter and host proxy.

### 1. Share a contract

```kotlin
interface Plugin {
    suspend fun greeting(name: String): Greeting
}

@kotlinx.serialization.Serializable
data class Greeting(val message: String)
```

Contract parameters and return values should use concrete serializable types. `Unit` is supported.

### 2. Implement the extension

Apply the extension plugin:

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
    extensionId.set("example")
    contractInterface.set("com.example.Plugin")
    implementationClass.set("com.example.MyPlugin")

    compileOnlyDependencies {
        useExtensionApi("pluginApi")
    }
}
```

Implement the interface normally:

```kotlin
object MyPlugin : Plugin {
    override suspend fun greeting(name: String) =
        Greeting("Hello, $name from Wasm")
}
```

Build the standalone extension with:

```shell
./gradlew :plugin:exportWasmtimeExtension
```

The exported bundle is under `build/wasmtime/extension/` and contains `extension.wasm` plus its manifest. You are still free to package or distribute that output however you want.

### 3. Generate a host proxy

Apply the host plugin:

```kotlin
plugins {
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
```

This generates `PluginWasmtimeProxy` and the shared Kotlin/Wasm runtime. It does not change how your app packages extension Wasm.

## Optional: automatic extension packaging

If you want Gradle to build an extension project and put it into your application resources, opt in explicitly:

```kotlin
wasmtimeHost {
    contractInterface.set("com.example.Plugin")
    bundleExtension(project(":plugin"))

    runtimeDependencies {
        useExtensionApi("pluginApi")
    }
}
```

`bundleExtension(...)` enables the convenience packaging layer. It generates `WasmtimeBundledExtensions` and wires the generated runtime/extension resources into KMP resources (and Compose resources when used).

Without `bundleExtension(...)`:

- `WasmtimeBundledExtensions` is not generated;
- extension resource assembly tasks are not registered;
- your app's resource packaging is untouched.

Load a bundled extension like this:

```kotlin
val runtime = loadWasmtimeBundledRuntime(
    manifestResourcePath = WasmtimeBundledExtensions.runtimeManifestResourcePath,
    readResource = Res::readBytes,
)

val loaded = WasmtimeBundledExtensions.example.load(
    readResource = Res::readBytes,
    runtime = runtime,
    services = WasmtimeHostServices(),
    expectedContract = PluginWasmtimeProxy.CONTRACT,
    proxyFactory = ::PluginWasmtimeProxy,
)

try {
    println(loaded.api.greeting("Ada").message)
} finally {
    loaded.shutdown()
}
```

The bundled loader validates the manifest, API version, contract, and requested host capabilities before running the extension.

## Host services

Typed extensions can request host capabilities. The host decides which services to provide through `WasmtimeHostServices`.

| Service | Guest API | Typical use |
| --- | --- | --- |
| HTTP | `ExtensionHttp` | Make network requests through the host |
| Credentials | `credential = "name"` | Let the host attach secrets without exposing them to guest code |
| Storage | `ExtensionStorage` | Persistent, cache, and temporary extension data |
| Logging | `ExtensionLog` | Structured extension logs |
| Resources | `ExtensionResources` | Pull large host-owned data in bounded chunks |

For example, grant HTTP on the host:

```kotlin
val services = WasmtimeHostServices(
    http = WasmtimeHttpHandler { request ->
        WasmtimeHttpResponse(
            statusCode = 200,
            body = myHttp(request),
        )
    },
)
```

and use it in the extension:

```kotlin
val response = ExtensionHttp.get("https://api.example.com/items")
```

Storage is namespaced under `/extension`:

```kotlin
ExtensionStorage.prepare()
val settings = ExtensionStorage.persistentPath("settings.json")
val cache = ExtensionStorage.cachePath("feed.json")
val temp = ExtensionStorage.temporaryPath("work.tmp")
```

Capabilities are declared by the extension, for example:

```kotlin
wasmtimeExtension {
    capabilities("Http", "PersistentStorage", "Logging")
}
```

Loading fails early when a required capability has not been provided by the host.

## Lifecycle and errors

Extensions can implement lifecycle hooks:

```kotlin
object MyPlugin : Plugin, WasmtimeExtensionLifecycle {
    override suspend fun onLoad() = ExtensionStorage.prepare()
    override suspend fun onUnload() = ExtensionLog.info("Closing")
}
```

Guest exceptions are surfaced as `WasmtimeExtensionException` with the remote type, message, and guest stack information when available.

Use `shutdown()` when you want the suspend `onUnload()` lifecycle to run. Use `close()` when you only need immediate transport cleanup.

## Limits

Use `WasmtimeLimits` to bound untrusted execution:

```kotlin
val limits = WasmtimeLimits(
    maxModuleBytes = 16 * 1024 * 1024,
    maxMemoryBytes = 64L * 1024 * 1024,
    fuel = 100_000_000,
    maxExecutionMillis = 30_000,
)
```

Limits can be used with bundled extensions, manually loaded extensions, and the lower-level runtime APIs.

## Testing extensions

`withWasmtimeExtension(...)` owns the transport lifecycle and lets tests provide fake host services:

```kotlin
withWasmtimeExtension(
    wasm = extensionBytes,
    runtime = runtime,
    services = testServices,
    proxyFactory = ::PluginWasmtimeProxy,
) { plugin ->
    check(plugin.greeting("test").message.isNotBlank())
}
```

## JVM on Linux

JVM/Linux needs the API JAR plus the native runtime for the machine running the app:

```kotlin
implementation("dev.brahmkshatriya.wasmtime:lib-jvm:VERSION")
runtimeOnly("dev.brahmkshatriya.wasmtime:lib-jvm:VERSION:linux-x64")
```

Use the `linux-arm64` classifier on ARM64. Kotlin/Native targets do not need this extra runtime dependency.

## Demo

The `demo` directory contains real extension/host examples for Android, Linux, JVM, and the browser. It explicitly uses `bundleExtension(...)` to demonstrate the optional automatic-packaging path.

```shell
./gradlew :demo:apps:linux:linkDebugExecutableLinuxX64
./demo/apps/linux/build/bin/linuxX64/debugExecutable/wasmtime-kmp-demo.kexe --smoke-extension
```

## License

See [LICENSE](LICENSE).
