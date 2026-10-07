# wasmtime-kmp

[![Maven Central](https://img.shields.io/maven-central/v/dev.brahmkshatriya.wasmtime/lib?label=Maven%20Central)](https://central.sonatype.com/artifact/dev.brahmkshatriya.wasmtime/lib)
[![CI](https://github.com/brahmkshatriya/wasmtime-kmp/actions/workflows/publish.yml/badge.svg)](https://github.com/brahmkshatriya/wasmtime-kmp/actions/workflows/publish.yml)

Run WebAssembly from Kotlin Multiplatform with Wasmtime.

| Use case | API |
| --- | --- |
| Run arbitrary Wasm | `Wasmtime.load(...)` and the low-level runtime |
| Build Kotlin plugins/extensions | Generated typed contracts with the Gradle plugins |

Supported hosts include Android, Linux, Windows, macOS, iOS, JVM on Linux, and Browser/WasmJS.

Replace `VERSION` below with the version shown by the Maven Central badge. `mavenCentral()` must be available to both plugin management and normal dependencies.

## Install

```kotlin
kotlin {
    sourceSets.commonMain.dependencies {
        implementation("dev.brahmkshatriya.wasmtime:lib:VERSION")
    }
}
```

## Run arbitrary Wasm

```kotlin
val instance = Wasmtime.load(wasmBytes)
try {
    val add = instance.functionI32("add")
    println(add(20, 22))
} finally {
    instance.close()
}
```

`wasmBytes` can come from resources, disk, network, a database, or any packaging format you choose.

The low-level API supports `i32`, `i64`, `f32`, and `f64`, exported memory access, host imports, and async calls. For C/Rust/Go-style pointer ABIs, `cAbi()` adds helpers for strings, primitive arrays, nullable pointers, allocation/release, and packed pointer/length values. You can always use `instance.function(...)` and `instance.memory(...)` directly for a custom ABI.

## Typed Kotlin extensions

Use this when both the host and extension are Kotlin. Your application calls a normal Kotlin interface; generated code handles the Wasm boundary.

### 1. Define a shared contract

```kotlin
interface Plugin {
    val name: String
    fun version(): Int

    suspend fun greeting(user: String): Greeting
    suspend fun items(): List<Item>
}

@Serializable
data class Greeting(val message: String)

@Serializable
data class Item(val id: String, val title: String)
```

Generated contracts support synchronous and `suspend` functions, `val`/`var` properties, inherited non-generic interfaces, nullable values, and concrete serializable generic types such as `List<Item>` or `Map<String, Item>`.

Contract values are encoded with CBOR. Open type parameters such as `fun <T> value(): T` and generic superinterfaces are not generated.

### 2. Implement the extension

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

```kotlin
object MyPlugin : Plugin {
    override val name = "Example"
    override fun version() = 1

    override suspend fun greeting(user: String) =
        Greeting("Hello, $user from Wasm")

    override suspend fun items() = listOf(Item("1", "First"))
}
```

Build a standalone bundle with:

```shell
./gradlew :plugin:exportWasmtimeExtension
```

The bundle is written under `build/wasmtime/extension/` and contains `extension.wasm` plus its manifest.

### 3. Configure the host

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

This generates `PluginWasmtimeProxy` and the shared Kotlin/Wasm runtime.

To let Gradle also package an extension into application resources:

```kotlin
wasmtimeHost {
    contractInterface.set("com.example.Plugin")
    bundleExtension(project(":plugin"))

    runtimeDependencies {
        useExtensionApi("pluginApi")
    }
}
```

`bundleExtension(...)` is optional. Without it, the library does not change your extension packaging.

### 4. Load the extension

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
    println(loaded.api.name)
    println(loaded.api.greeting("Ada").message)
} finally {
    loaded.shutdown()
}
```

The loader validates the manifest, API version, contract, and required host capabilities before running guest code.

## Host capabilities

Extensions can request services from the host instead of implementing privileged behavior themselves.

| Capability | Guest API | Purpose |
| --- | --- | --- |
| `Http` | `ExtensionHttp` | Network requests through the host |
| `Credentials` | `credential = "name"` | Host-managed secrets |
| `PersistentStorage` | `ExtensionStorage.persistentPath(...)` | Persistent data |
| `CacheStorage` | `ExtensionStorage.cachePath(...)` | Cache data |
| `TemporaryStorage` | `ExtensionStorage.temporaryPath(...)` | Temporary files |
| `Logging` | `ExtensionLog` | Structured logging |
| `Streaming` | `ExtensionResources` | Pull large named resources in chunks |
| `HostResources` | `ExtensionHostResource` / callback / stream | Host-owned callbacks, streams, and opaque resources |

Declare required capabilities in the extension:

```kotlin
wasmtimeExtension {
    capabilities("Http", "PersistentStorage", "Logging")
}
```

Provide them while loading:

```kotlin
val services = WasmtimeHostServices(
    http = WasmtimeHttpHandler { request -> myHttp(request) },
    storage = WasmtimeExtensionStorage("/my/extensions/example"),
    logger = WasmtimeExtensionLogger { event -> println(event.message) },
)
```

Loading fails early when a declared capability is missing.

## Callbacks, streams, and Flow

Serializable contract values cross the boundary by value. Stateful or executable objects use opaque resource handles instead. This is the mechanism for callbacks, pagers, feeds, iterators, and flows.

Guest-owned resources are registered with `ExtensionRemoteResources` and wrapped by the host with `loaded.remoteResource(...)`, `remoteCallback(...)`, or `remoteStream(...)`.

Host-owned resources are registered with `WasmtimeHostResourceRegistry` and exposed to the guest through `ExtensionHostResource`, `ExtensionHostCallback`, or `ExtensionHostStream`.

```kotlin
val resources = WasmtimeHostResourceRegistry()
val services = WasmtimeHostServices(remoteResources = resources)

val streamHandle = resources.registerFlow(scope, events)
```

Both stream directions support `Flow<ByteArray>` through `asFlow()`. Resource payloads are byte-level so applications can layer their own serializable DTOs on top.

## Lifecycle and errors

```kotlin
object MyPlugin : Plugin, WasmtimeExtensionLifecycle {
    override suspend fun onLoad() = ExtensionStorage.prepare()
    override suspend fun onUnload() = ExtensionLog.info("Closing")
}
```

Use `loaded.shutdown()` to run `onUnload()` and release guest resources. Use `close()` only for immediate transport cleanup.

Guest failures become `WasmtimeExtensionException`. Suspend calls propagate cancellation through the extension ABI. A non-suspending contract member must also remain non-suspending inside Wasm; attempting to suspend from it fails the call.

## Execution limits

```kotlin
val limits = WasmtimeLimits(
    maxModuleBytes = 16 * 1024 * 1024,
    maxMemoryBytes = 64L * 1024 * 1024,
    fuel = 100_000_000,
    maxExecutionMillis = 30_000,
)
```

Use limits when running untrusted modules or extensions.

## Platform notes

| Host | Support |
| --- | --- |
| Android ARM64 / x86_64 | Yes |
| Linux x64 / ARM64 | Yes |
| Windows x64 | Yes |
| macOS x64 / ARM64 | Yes |
| iOS ARM64 | Yes |
| JVM on Linux x64 / ARM64 | Yes |
| Browser / WasmJS | Yes |

Browser/WasmJS keeps each typed extension in a persistent isolated Worker, so guest state and resource handles survive across calls. Worker messaging is asynchronous, so synchronous typed contract members (`fun`, `val`, and `var`) are not supported there; use `suspend` members for calls that cross the Wasm boundary. A hard execution timeout or cancellation terminates that worker session so CPU-bound Wasm cannot continue running in the background.

A Browser/WasmJS host import called from a module start function also cannot inspect module-owned exported memory before instantiation has completed.

### JVM on Linux

JVM/Linux needs the API JAR and the matching native runtime:

```kotlin
implementation("dev.brahmkshatriya.wasmtime:lib-jvm:VERSION")
runtimeOnly("dev.brahmkshatriya.wasmtime:lib-jvm:VERSION:linux-x64")
```

Use `linux-arm64` on ARM64. Kotlin/Native targets do not need this extra runtime dependency.

## Testing

`withWasmtimeExtension(...)` owns the transport lifecycle and accepts test host services:

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

## Demo

The `demo` directory contains working extension/host examples for Android, Linux, JVM, and Browser/WasmJS. Its public API is intentionally shaped like Echo's extension system: one root music client is composed from smaller client/provider capabilities instead of exposing one flat RPC interface.

```kotlin
interface MusicExtensionClient :
    ExtensionClient,
    HomeFeedClient,
    TrackClient,
    MessageFlowProvider,
    DiagnosticsClient
```

Serializable metadata, tracks, tabs, shelves, settings DTOs, and pages cross the boundary as CBOR values. `Feed` carries an opaque guest-owned pager handle; the host reconstructs that as `RemoteFeed` and loads subsequent pages through the same persistent Wasm instance. Settings and the message channel are host-owned resources injected before `onInitialize()`, mirroring Echo's provider-injection lifecycle without trying to pass JVM `Settings` or `MutableSharedFlow` objects into Wasm.

The two demo extensions also retain HTTP, persistent storage, host callbacks/flows, packaged resources, credentials, and structured failure checks so the example continues to exercise the lower-level runtime capabilities.

```shell
./gradlew :demo:apps:linux:linkDebugExecutableLinuxX64
./demo/apps/linux/build/bin/linuxX64/debugExecutable/wasmtime-kmp-demo.kexe --smoke-extension
```

## License

See [LICENSE](LICENSE).
