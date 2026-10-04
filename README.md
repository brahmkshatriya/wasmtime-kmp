# wasmtime-kmp

Experimental runtime host for Kotlin Multiplatform. The library loads arbitrary Wasm bytes at runtime; Android, Linux, Windows, macOS, iOS, and JVM use Wasmtime, while the Wasm/JS browser target uses the browser's WebAssembly engine behind the same Kotlin API. The demo shows two Kotlin/Wasm-WASI plugins selected by one shared client UI.

The Kotlin package/group is `dev.brahmkshatriya.wasmtime`.

## Repository layout

```text
wasmtime-kmp/
  lib/                         Host KMP runtime + native Wasmtime bridge
    guest-runtime/             Internal guest coroutine/operation KLIB (wired automatically)
  gradle-plugin/               Reusable host + extension Gradle plugins
  demo/
    apps/
      android/                 Android app shell
      linux/                   Linux native app shell
      jvm/                     Compose Desktop/JVM app shell
      web/                     Compose Wasm/JS browser app shell
    client/                    Shared Compose UI + Wasmtime host capabilities
    shared/                    Consumer Plugin/Product API + @ExtensionEntry annotation
    plugin1/                   Ktor guest GET https://dummyjson.com/products/1
    plugin2/                   Ktor guest GET https://dummyjson.com/products/2
```

All four app modules are intentionally thin shells. Plugin selection, Wasm lifecycle, capability policy, host-side Ktor networking, result decoding, and UI state live in `demo:client` common code. The shells only provide their app-private storage root. Target source sets select the host Ktor engine: OkHttp on Android/JVM, Curl on Linux Native, and the JS/fetch engine on Wasm/JS.

## Gradle SDK

The reusable SDK owns all generic Wasm build mechanics and the wire ABI. Consumer modules directly declare their extension API, which interface is the contract, and how an implementation object is marked. The reusable plugin build exposes:

```kotlin
id("dev.brahmkshatriya.wasmtime.host")
id("dev.brahmkshatriya.wasmtime.extension")
```

The host-facing DSL is typed; consumers do not need to manipulate the backing `wasmtimeRuntime` Gradle configuration directly:

```kotlin
plugins {
    id("dev.brahmkshatriya.wasmtime.host")
}

wasmtimeHost {
    runtimeDependencies {
        add(projects.shared)
        add(libs.somePublishedWasmWasiLibrary)
    }
}
```

Reusable API sets can be declared once and consumed from both roles:

```kotlin
wasmtime {
    extensionApi("myApi") {
        add(projects.shared)
    }
}

wasmtimeHost {
    runtimeDependencies {
        useExtensionApi("myApi")
    }
}

wasmtimeExtension {
    compileOnlyDependencies {
        useExtensionApi("myApi")
    }
}
```

`buildWasmtimeRuntime` resolves the Wasm/WASI KLIB variants directly from that dependency graph; it no longer needs a dummy host `wasmWasi` compilation. Each KLIB is compiled as its own open-world Wasm module with IR DCE and `-Xwasm-included-module-only`, metadata is stripped, unreachable GC type groups are pruned, outputs are validated, imports are inspected for topological ordering, and the `<kotlin>` shared-memory bridge is inserted automatically.

The extension plugin automatically adds the SDK's internal `guest-runtime` KLIB as `compileOnly`. The host plugin automatically includes the same KLIB once in the shared runtime. Consumers never declare that implementation dependency themselves. `exportWasmtimeExtension` owns extension KLIB compilation, the explicit open-world final link against compile-only libraries, DCE, metadata stripping, GC type pruning, validation, and the final `build/wasmtime/<name>.wasm` artifact.

For automatic contract generation, modules configure the generic plugins directly. `demo:client` declares the host side:

```kotlin
plugins {
    id("dev.brahmkshatriya.wasmtime.host")
}

wasmtime {
    extensionApi("demo") {
        add(projects.demo.shared)
    }
}

wasmtimeHost {
    contractInterface.set("dev.brahmkshatriya.wasmtime.demo.shared.Plugin")
    runtimeDependencies {
        useExtensionApi("demo")
    }
}
```

Each extension module declares the matching extension side:

```kotlin
plugins {
    id("dev.brahmkshatriya.wasmtime.extension")
}

wasmtime {
    extensionApi("demo") {
        add(projects.demo.shared)
    }
}

wasmtimeExtension {
    contractInterface.set("dev.brahmkshatriya.wasmtime.demo.shared.Plugin")
    entryPointAnnotation.set("dev.brahmkshatriya.wasmtime.demo.shared.ExtensionEntry")
    compileOnlyDependencies {
        useExtensionApi("demo")
    }
}
```

`wasmtime-kmp` then reads the compiled API KLIB in an isolated Kotlin 2.4.20 ABI-reader process, derives the contract methods and return types, assigns deterministic method IDs from full signatures, generates the guest dispatcher/exports, and generates the host proxy implementing the Kotlin interface. The consumer does not define export names, status codes, polling, result/error transport, method IDs, or adapter source.

The current automatic contract generator supports zero-argument `suspend` methods with kotlinx-serializable return types (plus `Unit`). Contracts outside that subset fail at generation with a targeted error instead of silently producing an incompatible ABI. Argument transport can be added later without changing the existing lifecycle/status protocol.

The actual Wasm import names remain the final compatibility contract. Host and extension projects do not need project references to one another; they need ABI-compatible Kotlin/KLIB versions and the same consumer-defined API set.

## Plugin contract

`demo:shared` defines the typed API seen by the host and extensions:

```kotlin
interface Plugin {
    suspend fun getProductDetails(): Product
}
```

An extension source file only implements that interface:

```kotlin
private const val PRODUCT_URL = "https://dummyjson.com/products/1"
private val client = HttpClient()
private val json = Json { ignoreUnknownKeys = true }

@ExtensionEntry
object ProductPlugin : Plugin {
    override suspend fun getProductDetails(): Product {
        val responseJson = client.get(PRODUCT_URL).bodyAsText()
        return json.decodeFromString<Product>(responseJson)
    }
}
```

The generic Wasmtime extension plugin scans Kotlin source for exactly one top-level object carrying the consumer-selected annotation, validates the configured contract from its compiled KLIB, and generates the small core-Wasm adapter. The generic host plugin generates the matching Kotlin proxy from the same contract. Host applications give that proxy a transport created with `createWasmtimeExtensionTransport(...)`, supplying only normal Wasmtime inputs such as limits, capabilities, storage, and runtime modules; method IDs and instance lifecycle remain SDK-owned. Extension authors do not configure a generated-entry task, implementation class name, `main()`, `@WasmExport`, method IDs, status values, or the wire ABI. Another consumer can choose a different annotation and contract without writing adapter code.

On the app side, `loadPlugin(...)` still returns the shared Kotlin interface:

```kotlin
val plugin: Plugin = loadPlugin(selectedPlugin)
val product = plugin.getProductDetails()
```

Because the output is a core Wasm module, callable Wasm exports still have to exist. They are generated build artifacts rather than extension-authored API.

## Shared extension runtime

Reusable guest-side scheduling is no longer duplicated into every extension. The SDK-internal `lib/guest-runtime` KLIB contains `WasmtimeExtensionCall`, which owns the coroutine dispatcher, virtual timer queue, cancellation state, and result/error byte buffers. The generic Gradle plugins wire it automatically: host runtimes include it once, while extensions see it as compile-only.

With the current demo graph:

- plugin1 is **17,021 bytes** and plugin2 is **17,023 bytes** (about **16.6 KiB** each), down from the original 63,521 bytes;
- the shared guest-runtime module is **15,276 bytes** once;
- the complete pruned runtime is **4,294,334 bytes** (about **4.095 MiB**) across 21 Wasm files.

The GC pruner is bundled inside the Gradle plugin and pins `wasmparser` / `wasm-encoder` 0.258.0. Gradle builds one shared helper at `build/wasmtime/tools/wasm-type-pruner` and reuses it for host and extension tasks. It preserves recursive type groups, remaps retained type indices, and validates every rewritten module. Binaryen `wasm-opt` is not used for these open-world modules.

Kotlin's open-world graph uses one linear memory exported by `<kotlin>`. The bundled host-memory bridge imports `<kotlin>.memory`, re-exports it as `memory`, and forwards `wasi_snapshot_preview1` and `ktor_wasi`. Native Wasmtime and the browser backend both consume the same ordered runtime manifest.

## Suspend calls

Suspend functions use a fixed SDK-owned start/poll/cancel/result/error ABI rather than `runBlocking` or an immediate-completion assumption. Contract methods are dispatched by deterministic integer method IDs; the export names and `PENDING` / `SUCCESS` / `FAILURE` / `CANCELLED` values live only inside `wasmtime-kmp`. The generated guest adapter delegates to `WasmtimeExtensionCall`, while the generated host proxy delegates through `WasmtimeExtensionTransport` and `WasmtimeInstance.callExtension(...)`.

Host capabilities are asynchronous too. On Android and Linux Native, the native bridge defines `ktor_wasi.request_execute` with Wasmtime's async host-function API and executes Wasm through `wasmtime_func_call_async`. JVM uses the same Wasmtime future path, but starts Java/Kotlin host work through a small native worker-thread trampoline because HotSpot cannot safely be entered from Wasmtime's alternate fiber stack. The actual `WasmtimeHttpHandler` still runs as a normal coroutine. On Wasm/JS, the equivalent suspension boundary uses JS Promise Integration (`WebAssembly.Suspending` and `WebAssembly.promising`). In every case `invokeAsync(...)` suspends the Kotlin caller while the host capability is pending; there is no `runBlocking` bridge.

That means ordinary suspension points such as `delay`, `withTimeout`, and structured child coroutines that inherit the plugin coroutine context can survive across multiple Wasmtime calls. Host cancellation closes the Wasmtime call future, which disposes the pending async host operation; generated guest cancellation is also propagated through the cancel export before the instance is closed. Exceptions are retained by the guest operation and returned to the host as an error result.

Internally, the fixed protocol has `PENDING`, `SUCCESS`, `FAILURE`, and `CANCELLED` states; these values are not part of the consumer API. Result and error bytes are read only after the operation reaches a terminal state. A plugin author still writes only a normal Kotlin `suspend` implementation.

The current core-Wasm ABI is private to this runtime. The WASI Component Model/WIT is the eventual standardized replacement once the Kotlin toolchain can provide the same workflow without adding the external Rust/wit-bindgen toolchain this project intentionally avoids.

## Ktor on Wasm/WASI

The published Ktor WASI fork adds a `wasmWasi` target to the core modules required by `ktor-client-core` and provides a `ktor-client-wasi` engine that registers itself as the default engine on Wasm/WASI. `demo:shared` exposes the published WASI dependency only to its guest source set, while host targets keep using upstream Ktor:

```kotlin
sourceSets.wasmWasiMain.dependencies {
    api("dev.brahmkshatriya.ktorwasi:ktor-client-wasi:3.6.0")
    api("org.jetbrains.kotlinx:kotlinx-io-core:0.9.1")
}
```

An extension declares the host-provided contract/runtime as `compileOnly` and can use Ktor, coroutines, serialization, and kotlinx-io normally without embedding those libraries into its final Wasm module:

```kotlin
private val client = HttpClient()

val responseJson = client
    .get("https://dummyjson.com/products/1")
    .bodyAsText()
```

The Ktor WASI engine performs each granted host HTTP capability inside the current guest execution. Extension-level suspension is independent of that transport: the shared extension runtime's coroutine dispatcher is what allows the exported `suspend` contract to pause and resume across Wasmtime calls.

The `Wasi` engine transports arbitrary HTTP method strings, request headers, buffered request bodies, status codes, response headers, and buffered response bodies. Standard Ktor requests such as GET, POST, PUT, PATCH, DELETE, HEAD, and OPTIONS therefore use the same guest API. The current transport is buffered rather than streaming; protocol upgrades such as WebSockets are not implemented yet.

## Network capability

The guest does not receive direct socket access. The flow is:

```text
plugin.wasm
  -> generated async start/poll adapter
  -> Plugin.getProductDetails()
  -> HttpClient()
  -> imported ktor_wasi host functions
  -> WasmtimeHttpHandler supplied by the loader
  -> host policy check
  -> common host HttpClient()
     -> Android Ktor OkHttp engine
     -> Linux Ktor Curl engine
     -> JVM Ktor OkHttp engine
     -> Wasm/JS Ktor JS/fetch engine
  -> status + headers + response body returned to Ktor in the guest
  -> plugin parses DummyJSON and completes its coroutine
  -> generated adapter marks SUCCESS and serializes Product
  -> host reads Product JSON after the operation completes
```

The demo host currently restricts the destination URL to:

```text
https://dummyjson.com/products/<number>
```

That is a demo capability policy, not a transport limitation. The transport itself still supports arbitrary HTTP methods and buffered bodies, but the demo host now deliberately grants only GET/no-body requests to the allowlisted product URL and disables host-client automatic redirects. Other consumers can grant a broader policy explicitly.

The imported `ktor_wasi` ABI is HTTP-semantic-agnostic at the native layer: it transfers request metadata + body and response metadata + body. Metadata contains method/status and repeated header pairs. Payloads use bulk linear-memory copy imports (`request_metadata_copy`, `request_body_copy`, `response_metadata_copy`, and `response_body_copy`); the older byte-at-a-time imports remain only as compatibility fallbacks. Plain default Wasm/WASI `HttpClient()` GET requests also use a buffered Ktor fast path, while configured clients, custom pipeline interceptors, request bodies, and other non-default behavior fall back to the full Ktor pipeline.

## Shared model

`demo/shared` is compiled for Android, Linux x64, JVM, Wasm/JS, and `wasmWasi`. It contains the common `@Serializable Product` model, the `Plugin` contract / `@ExtensionEntry` marker, and the Ktor API dependencies inherited by both plugins.

Inside each guest:

```kotlin
val product = json.decodeFromString<Product>(responseJson)
val result = json.encodeToString(product)
```

The client decodes the returned serialized `Product` using the same shared model.

## Runtime HTTP API

`wasmtime-kmp` exposes an optional host HTTP capability:

```kotlin
val instance = Wasmtime.load(
    wasm = wasmBytes,
    runtime = WasmtimeRuntime(
        runtimeModules.map { (name, bytes) ->
            WasmtimeRuntimeModule(name, bytes)
        }
    ),
    httpHandler = WasmtimeHttpHandler { request ->
        require(request.url.startsWith("https://example.com/"))

        val response = hostHttpClient.request(request.url) {
            method = HttpMethod(request.method)
            request.headers.forEach { header ->
                headers.append(header.name, header.value)
            }
            setBody(request.body)
        }

        WasmtimeHttpResponse(
            statusCode = response.status.value,
            headers = response.headers.entries().flatMap { (name, values) ->
                values.map { value -> WasmtimeHttpHeader(name, value) }
            },
            body = response.bodyAsBytes(),
        )
    },
)
```

If no HTTP handler is supplied, guest HTTP requests fail instead of receiving ambient network access. `WasmtimeHttpHandler` is a capability boundary: the handler must validate every destination it actually connects to. In particular, a forwarding HTTP client should disable opaque automatic redirects (or validate every redirect hop), otherwise an allowlisted URL can redirect to localhost, a private/link-local address, or another disallowed origin. The demo disables host-side redirects and accepts only `GET https://dummyjson.com/products/<number>`; guest Ktor redirects therefore return through the handler and are revalidated as a fresh capability request. Request/response buffers are bounded by the configured runtime limits.

## Sandboxed storage

Filesystem access is also an explicit load capability. If `storage` is omitted, no directory is preopened and the guest has no persistent filesystem access. Granting storage exposes only the configured guest mount, normally `/data`:

```kotlin
val instance = Wasmtime.load(
    wasm = wasmBytes,
    storage = WasmtimeStorage(
        backingPath = pluginStoragePath,
        guestPath = "/data",
    ),
)
```

On Android, Linux, macOS, iOS, Windows, and JVM, `backingPath` is a host filesystem directory. POSIX targets resolve paths relative to one preopened directory FD. Windows uses an equivalent handle-anchored compatibility layer that rejects Windows drive/separator escapes and opens components with reparse-point following disabled. Absolute paths and `.` / `..` components are rejected, and the backing directory is locked for the lifetime of a mount so concurrent instances cannot race quota accounting. POSIX read-only mounts use a shared read lock; the current Windows shim conservatively serializes read-only and writable mounts with the same cross-process named mutex. The default persistent-storage policy is 64 MiB total, 4,096 entries, and 16 MiB per regular file; all three quotas are configurable on `WasmtimeStorage` and `0` disables that individual quota. Existing contents are scanned before instantiation and an already-over-quota or unsupported tree is rejected.

On Wasm/JS, `backingPath` is relative to the browser's Origin Private File System (OPFS). Generated untrusted extension calls execute in a dedicated Web Worker and writable storage is held under an exclusive Web Lock for the duration of the call, preventing concurrent workers/tabs in the same origin from racing quota accounting. The browser shim applies the same storage-size/file-size/entry policy and rejects absolute/parent-traversal paths. It covers the Preview-1 operations needed by the current `kotlinx-io` path; it is not a complete Preview-1 filesystem implementation.

The demo isolates storage by plugin id:

```text
<app storage>/plugins/plugin1  -> guest /data
<app storage>/plugins/plugin2  -> guest /data
```

Guest code uses the normal `kotlinx-io` API rather than a Wasmtime-specific storage API:

```kotlin
val path = Path("/data/counter.txt")

SystemFileSystem.sink(path).buffered().use { sink ->
    sink.write("hello".encodeToByteArray())
}

val bytes = SystemFileSystem.source(path).buffered().use { source ->
    source.readByteArray()
}
```

Both demo plugins persist a counter in `/data/counter.txt`; the product title includes the observed counter so repeated launches visibly prove persistence while the two plugins remain isolated.

The Wasm/WASI guest resolves the published Ktor fork from Maven Central at `dev.brahmkshatriya.ktorwasi:ktor-client-wasi:3.6.0`. Storage uses the upstream `kotlinx-io 0.9.1` artifact from Maven Central. Wasmtime's Preview-1 path decoder accepts the single trailing NUL length form emitted by kotlinx-io 0.9.1 while still rejecting embedded NULs and sandbox escapes.

## Runtime backend

Native builds use a compact Wasmtime `49.0.1` C API build generated by
`lib/src/native/build-slim-wasmtime.sh`. It keeps the execution features used by
this runtime—Cranelift, async host calls, WebAssembly GC with the DRC collector,
threads, and parallel compilation—while omitting unrelated C API features such
as Wasmtime's WASI implementation, the Component Model API, WASI HTTP, Winch,
WAT parsing, cache, profiling, coredumps, debug builtins, logging, and
symbolization helpers. The restricted Preview-1 surface needed by Kotlin/Wasm is
implemented directly by `lib/src/native/wasi_lite.c`, including `/data`
sandboxing, clocks, randomness, polling, and stdio.

The compact build keeps Wasmtime's execution/runtime crates at release `-O3`
with fat LTO and one codegen unit. Cranelift/compiler implementation crates use
`opt-level=s` by default to reduce native size without changing the optimization
level used for generated guest machine code. Linux/JVM linking additionally uses
section GC and identical-code folding when LLVM lld is available. The Linux demo
also hides symbols from static archives with `--exclude-libs,ALL`, which lets
section GC discard otherwise-exported archive code, and strips the final release
ELF.

The local Ktor Curl fork links Linux Native against the system `libcurl` instead
of embedding static `libcurl`, nghttp2, OpenSSL, and crypto archives in every
executable. This keeps the desktop binary substantially smaller, but makes
`libcurl.so.4` a runtime dependency. This is consistent with the Linux desktop
app already depending on system GUI/runtime libraries; use a static Curl cinterop
instead if a more self-contained executable is required.

No system-wide Rust installation is required. The compact build script always
uses a pinned private Rust `1.96.0` toolchain under `lib/src/native/.deps/`, so it
does not install targets or toolchains into a user's global rustup state. It
builds the archives once and reuses them on subsequent builds. Set
`WASMTIME_USE_FULL=1` to force the unmodified official Wasmtime C API archive
instead.

| Target | Runtime backend |
| --- | --- |
| Android arm64-v8a | Wasmtime C API through JNI |
| Android x86_64 | Wasmtime C API through JNI |
| Linux x86_64 | Wasmtime C API through Kotlin/Native cinterop |
| Linux ARM64 | Wasmtime C API through Kotlin/Native cinterop; cross-build/runtime smoke is verified under Kotlin/Native's bundled AArch64 QEMU |
| Windows x86_64 (`mingwX64`) | Wasmtime C API through Kotlin/Native cinterop; the compact GNU-targeted Wasmtime archive and Win32 sandbox shim are embedded in the published cinterop KLIB |
| macOS x86_64 | Wasmtime C API through Kotlin/Native cinterop; built with Xcode/macOS SDK on a macOS host |
| macOS ARM64 | Wasmtime C API through Kotlin/Native cinterop; built with Xcode/macOS SDK on a macOS host |
| iOS ARM64 | Wasmtime C API through Kotlin/Native cinterop using the `pulley64` interpreter target; Cranelift translates Wasm to Pulley bytecode, so guest code does not require native executable/JIT mappings |
| JVM on Linux x86_64 / ARM64 | Wasmtime C API through JNI; the JVM API JAR is architecture-neutral and the matching `linux-x64` or `linux-arm64` native classifier is added at runtime |
| Wasm/JS browser | Browser `WebAssembly` API + minimal WASI Preview1, OPFS, and `ktor_wasi` imports required by these plugins |

The Wasmtime-backed targets create a separate store per plugin instance with hard memory, table-element, instance/table/memory-count, fuel, and execution-time limits. Wasmtime allocates its WebAssembly GC heap through the same memory allocator/resource limiter, so `maxMemoryBytes` bounds both linear-memory growth and Wasm-GC heap growth (per memory/heap). Finite fuel is the normal compute bound. When fuel is explicitly disabled while `maxExecutionMillis` remains nonzero, direct loads automatically select an epoch-instrumented engine so native/JVM/Android still get hard wall-clock preemption; async host waits are deadline-checked as well. This keeps epoch instrumentation off the normal finite-fuel path. Host-side Preview-1 calls are separately bounded so guest code cannot hide arbitrarily large work inside an unmetered host call. Output, HTTP response, module size, `poll_oneoff`, and persistent-storage work also have explicit limits.

The browser does not have Wasmtime fuel metering, so `fuel` itself remains unenforceable there. The generated extension transport compensates by running the entire untrusted extension call in a dedicated Web Worker with a hard wall-clock timer: timeout or coroutine cancellation terminates the worker. Before compilation, the worker rejects oversized modules and rewrites standard defined linear-memory/table maxima to `maxMemoryBytes` / `maxTableElements`, rejecting unsupported memory/table encodings rather than executing them without the configured bound. Browser suspend-capable guest imports require JSPI, writable OPFS storage requires Web Locks, and networking remains subject to normal browser CORS policy. The lower-level direct synchronous `Wasmtime.load` / `callI32` Wasm/JS API cannot be forcibly preempted once JavaScript has entered a synchronous Wasm call and therefore remains an advanced trusted-embedding API; `createWasmtimeExtensionTransport` is the hardened browser path intended for hostile third-party extensions.

### Security/resource policy

The defaults are intentionally finite and can be changed per load:

```kotlin
val limits = WasmtimeLimits(
    maxModuleBytes = 16 * 1024 * 1024,
    maxMemoryBytes = 64L * 1024 * 1024,
    fuel = 100_000_000L,
    maxExecutionMillis = 30_000L,
    maxTableElements = 1_000_000L,
    maxHostCallBytes = 1 * 1024 * 1024,
    maxOutputBytes = 1L * 1024 * 1024,
    maxHttpResponseBytes = 16 * 1024 * 1024,
    maxWasiPollMillis = 1_000L,
)

val storage = WasmtimeStorage(
    backingPath = pluginStoragePath,
    maxBytes = 64L * 1024 * 1024,
    maxEntries = 4_096,
    maxFileBytes = 16L * 1024 * 1024,
)
```

A zero value disables the corresponding limit where documented. stdout/stderr are byte-count limited and sanitized to printable ASCII plus newline/carriage-return/tab, preventing terminal escape/control sequences from being emitted by a hostile guest. Writable native storage holds an exclusive non-blocking OS lock on the sandbox root (read-only mounts use a shared lock), so separate instances cannot race quota accounting. The native regression suite is available as `./gradlew :lib:testNativeSecurity`; it exercises path traversal, symlink and pre-seeded hard-link escapes, storage quota enforcement and concurrent mounts, table/linear-memory/Wasm-GC limits, oversized host calls, bounded polling and HTTP buffers, CPU-loop execution deadlines, close-vs-call lifetime races, repeated instance/thread teardown, cache/storage separation, corrupt-cache recovery, and rejection of unsafe writable cache directories.

### Compiled Wasmtime module cache

Wasmtime-backed hosts automatically persist compiled machine-code modules between process launches. The bridge hashes each original Wasm module with SHA-256 and stores the result of `wasmtime_module_serialize` as a private `.cwasm` artifact; the cache key also distinguishes epoch-instrumented from normal finite-fuel code. Cache files are native-code-equivalent trusted data, so loading never reopens the original attacker-influenced pathname after validation: the bridge opens with `O_NOFOLLOW`, validates that descriptor (regular file, current euid, private write permissions, bounded size), deserializes through `/proc/self/fd/<fd>` so Wasmtime reads the already-open inode, then rechecks the same descriptor. A bounded exact-descriptor memory-copy path remains as a fallback if procfs descriptor paths are unavailable. Corrupt/incompatible or unstable entries are discarded and rebuilt.

The cache deliberately does **not** enable Wasmtime's optional `cache` feature. Cache directories must be private (current owner and not group/world writable); artifacts are written through `mkstemp` with mode `0600`, fsynced, and atomically renamed. Guest storage is rejected if it overlaps the compiled-cache tree. Cache growth is bounded to 128 entries / 128 MiB total (and 64 MiB per serialized module), pruning older entries instead of growing indefinitely. This applies to the extension and every shared runtime module, so application code and extensions do not need cache-specific APIs.

On the current Linux x86_64 benchmark, the 21-module runtime plus extension loads in about **60.6 ms** from a warm serialized cache versus **3.32 s** with caching disabled (about **55x faster**). Filling an empty cache takes about **3.42 s**, so the first-run cost stays close to the original compile path. The current graph occupies about 55 MiB in the local compiled cache; these files are runtime-generated and are not packaged in the application.

The default cache is under the platform user/app cache location (`$XDG_CACHE_HOME/wasmtime-kmp` or `~/.cache/wasmtime-kmp` on Linux/JVM and the POSIX-native fallback under `$HOME` on Apple hosts). On Android the JNI loader passes `java.io.tmpdir/wasmtime-kmp`, which is the app-private temporary/cache location when provided by Android. Windows currently disables serialized-module caching rather than applying the POSIX owner/mode trust model to Windows ACLs. `WASMTIME_KMP_CACHE_DIR=/absolute/path` overrides the location on cache-enabled native hosts. Set `WASMTIME_KMP_CACHE_DIR=off` (or `0`) to disable it. Browser/WasmJS continues to use the browser WebAssembly engine and does not use these native serialized artifacts.

### Slim native Wasmtime

The official Wasmtime C-API archives enable many features that this runtime never uses. `lib/src/native/build-slim-wasmtime.sh` builds smaller Wasmtime 49.0.1 archives while keeping the runtime features used here: Cranelift, async host calls, Wasm GC with the DRC collector, parallel compilation, fuel, store limiters, and epoch interruption. Logging is compiled out. Wasmtime's WASI implementation is disabled; `wasi_lite.c` supplies only the Preview-1 calls used by the Kotlin plugin runtime. The Component Model, Component Model async support, and WebAssembly threads are explicitly disabled. Core WebAssembly exceptions remain enabled because the current Kotlin/Wasm runtime graph emits exception constructs. Winch, WAT parsing, profiling, coredumps, debug builtins, the Wasmtime cache feature, and standalone component-model APIs are not enabled. Pulley is enabled only for iOS: Cranelift remains present to translate Wasm into Pulley bytecode, while the engine is forced to `pulley64` so execution stays interpreter-only and Wasmtime marks the generated text as non-executable.

The slim build keeps the execution/runtime crates at release `-O3`, fat LTO, and one codegen unit while compiling the Cranelift/compiler implementation crates with `opt-level=s`. This changes compiler implementation size, not Cranelift's guest execution strategy. `WASMTIME_COMPILER_OPT_LEVEL=3` provides an O3 compiler baseline and `WASMTIME_COMPILER_OPT_LEVEL=z` enables the more aggressive size experiment. The build cache fingerprint includes this setting, so switching profiles rebuilds the affected archives deterministically.

On the current Linux x86_64 host, a 20-million-call resolved-function benchmark measured steady-state calls at about `11.55 ns/call` with the O3 compiler implementation and `11.60 ns/call` with selective `s` (about 0.5% difference). Compiling and instantiating the real 20-module shared runtime had a median cold-load time of about `2.65 s` with O3 versus `3.07 s` with selective `s` (about 15.6% slower). That benchmark predates GC type pruning and used the then-current 63,521-byte plugin. The more aggressive selective `z` build kept hot execution unchanged too, but its cold-load penalty was substantially larger, so `s` is the default compromise.

Generate the cache explicitly:

```bash
./gradlew :lib:buildSlimWasmtime
```

The generated archives live under the ignored `lib/src/native/.deps/slim/` directory. Compact archives are produced for Linux x86_64/ARM64, Windows x86_64, Android arm64/x86_64, macOS x86_64/ARM64, and iOS ARM64. Local developer builds can still cross-build Linux ARM64 and Windows x64 from Linux when the Kotlin/Native target toolchains are available. Release CI builds every architecture-specific Wasmtime/C/JNI payload on a runner matching its OS and CPU architecture. Kotlin/Native 2.4.x itself cannot execute on Linux ARM64, so the Linux ARM64 native payload is built on `ubuntu-24.04-arm` and only the final KLIB/cinterop packaging runs on the supported Linux x64 Kotlin/Native host using those prebuilt ARM64 archives. Apple archives require macOS/Xcode because they build against the macOS/iPhoneOS SDKs. Normal native builds generate/reuse these archives automatically and use the private pinned Rust toolchain under `lib/src/native/.deps/`; no global Rust installation is required. `WASMTIME_USE_FULL=1` can select the official full archive on platforms where Wasmtime publishes an ABI-compatible static archive; Windows `mingwX64` and iOS intentionally keep using the compact GNU/Pulley builds. Set `WASMTIME_KEEP_SYMBOLS=1` if native JVM symbols should not be stripped.

With the current release build, the size changes are approximately:

| Artifact | Full Wasmtime | Slim Wasmtime |
| --- | ---: | ---: |
| Android arm64 native bridge (release stripped) | 20.08 MiB | 5.53 MiB |
| Android x86_64 native bridge (release stripped) | 24.30 MiB | 7.17 MiB |
| Android universal release APK | 56.43 MiB | 24.75 MiB |
| JVM Linux x86_64 native bridge (stripped) | 24.38 MiB | 7.15 MiB |
| JVM API JAR (no native payload) | — | 93,743 B |
| JVM Linux x86_64 native classifier JAR | — | 3,103,621 B |
| JVM Linux ARM64 native classifier JAR | — | 2,700,876 B |
| Linux release executable (release-stripped, system libcurl) | 71.53 MiB | 30.55 MiB |

The current compact Linux ARM64 Wasmtime archive is **22,645,544 bytes** (about 21.60 MiB), versus **25,509,910 bytes** for Linux x86_64. The compact Windows x86_64 GNU archive is **14,713,990 bytes**. The stripped Linux ARM64 JNI bridge is **5,904,688 bytes**, while the x86_64 JNI bridge is **7,485,016 bytes**. JVM packaging keeps those bridges out of the API JAR: the code-only JVM artifact is **93,743 bytes**, the compressed x86_64 native classifier is **3,103,621 bytes**, and the ARM64 classifier is **2,700,876 bytes**. A packaged x86_64 application therefore carries about **3.20 MB** of Wasmtime JVM artifacts instead of the previous **5.90 MB** dual-architecture JAR, and an ARM64 package carries about **2.79 MB**.

The Linux number includes the release linker cleanup in addition to compact
Wasmtime: `--exclude-libs,ALL` enables more archive dead-code elimination,
release symbols are stripped, and Ktor Curl uses the system shared `libcurl`.
Keeping Ktor's static Curl/nghttp2/OpenSSL/crypto archives instead produces about
37.05 MiB after the same symbol-hiding and stripping. The Compose resources next
to the executable are about 4.8 MiB and are not included in the executable-size
row.

The demo grants each plugin only its own `/data` storage subtree plus the explicit HTTP capability. It does not grant arbitrary host filesystem access, environment variables, or direct sockets. Browser networking still goes through `WasmtimeHttpHandler`; browser files stay inside OPFS.

## Plugin resources

The app packages:

```text
demo/client/src/commonMain/composeResources/files/plugin1.wasm
demo/client/src/commonMain/composeResources/files/plugin2.wasm
demo/client/src/commonMain/composeResources/files/runtime/runtime.tsv
demo/client/src/commonMain/composeResources/files/runtime/*.wasm
```

`demo:client:syncDemoPluginResources` is now only a packaging task. It depends on `:demo:plugin1:exportWasmtimeExtension`, `:demo:plugin2:exportWasmtimeExtension`, and `:demo:client:buildWasmtimeRuntime`, then copies their already-stripped/pruned/validated outputs into the Compose resources. The optimization logic lives in the reusable Gradle plugins rather than the demo client.

Build the reusable artifacts directly with:

```bash
./gradlew \
  :demo:client:buildWasmtimeRuntime \
  :demo:plugin1:exportWasmtimeExtension \
  :demo:plugin2:exportWasmtimeExtension
```

To refresh the demo's packaged resources:

```bash
./gradlew :demo:client:syncDemoPluginResources
```

## Build

Use JDK 21. Linux additionally needs the normal Compose Native desktop libraries plus libcurl development headers/libraries for the target architecture. The Linux executable dynamically links `libcurl.so.4`, so libcurl must also be present at runtime. Linux ARM64 runtime/library cross-compilation works on x86_64 using Kotlin/Native's bundled AArch64 toolchain; cross-linking the Compose ARM64 demo shell also requires ARM64 SDL3/fontconfig/freetype/png/OpenGL/D-Bus/Wayland/X11 libraries in the linker sysroot. A native ARM64 Linux machine with those normal desktop libraries does not need that extra cross sysroot setup. The build resolves normal host Ktor artifacts and `kotlinx-io` from Maven Central. Wasm/WASI guests use `dev.brahmkshatriya.ktorwasi:ktor-client-wasi:3.6.0` from Maven Central.

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew \
  :demo:apps:android:assembleDebug \
  :demo:apps:linux:linkReleaseExecutableLinuxX64 \
  :demo:apps:jvm:createDistributable \
  :demo:apps:web:wasmJsBrowserDevelopmentExecutableDistribution
```

Native Kotlin consumers do not need to repeat Wasmtime linker paths. Each published native cinterop KLIB embeds the matching `libwasmtime_kmp_bridge.a` and `libwasmtime.a` plus target system-link metadata; a normal `implementation("dev.brahmkshatriya.wasmtime:lib:...")` is sufficient.

Windows x64 can be cross-linked from Linux with:

```bash
./gradlew :lib:linkDebugTestMingwX64
```

macOS x64/ARM64 and iOS ARM64 builds must run on macOS with Xcode installed; their cinterop tasks automatically build and embed the corresponding native archive. iOS uses Pulley rather than native guest JIT code. On a Mac, validate all three Apple native links with:

```bash
./gradlew :lib:verifyAppleNativeLink
```

Verify the Linux ARM64 Wasmtime/Kotlin-Native path on an x86_64 development machine using Kotlin/Native's bundled QEMU:

```bash
./gradlew :lib:testLinuxArm64UnderQemu
```

On an ARM64 Linux build machine (or an x86_64 cross environment with the ARM64 desktop libraries above), build the Compose shell with:

```bash
./gradlew :demo:apps:linux:linkReleaseExecutableLinuxArm64
```

Run Linux:

```bash
demo/apps/linux/build/bin/linuxX64/releaseExecutable/wasmtime-kmp-demo.kexe
```

For a published JVM consumer, depend on the architecture-neutral API plus exactly one runtime classifier:

```kotlin
implementation("dev.brahmkshatriya.wasmtime:lib-jvm:<version>")
runtimeOnly("dev.brahmkshatriya.wasmtime:lib-jvm:<version>:linux-x64")
// or on ARM64:
// runtimeOnly("dev.brahmkshatriya.wasmtime:lib-jvm:<version>:linux-arm64")
```

The demo JVM app resolves the classifier automatically from the build host's `os.arch`; the native payload remains runtime-only and does not enter compile classpaths.

Run JVM desktop:

```bash
./gradlew :demo:apps:jvm:run
```

Run the Wasm/JS browser development server:

```bash
./gradlew :demo:apps:web:wasmJsBrowserDevelopmentRun
```

Install Android:

```bash
adb install -r demo/apps/android/build/outputs/apk/debug/android-debug.apk
```

The UI starts with Plugin 1 and lets you switch between Plugin 1 and Plugin 2. Plugin 1 loads product 1; Plugin 2 loads product 2.

## Fast Wasmtime calls

For ordinary primitive exports, resolve the function once and reuse it:

```kotlin
val instance = Wasmtime.load(wasmBytes)
try {
    val function = instance.functionI32("someExport")
    val result = function(first, second)
} finally {
    instance.close()
}
```

`functionI32(name)` resolves and validates the export once. For ordinary synchronous modules, `invoke(...)` keeps the fast `wasmtime_func_call_unchecked` path. If an export can enter an asynchronous host capability, call `invokeAsync(...)`; it uses a Wasmtime call future and suspends while the native future is pending. `callI32(...)` and `callI32Async(...)` provide the corresponding convenience forms.

## Publishing Wasmtime KMP

Release publications use the group `dev.brahmkshatriya.wasmtime`. The complete Maven repository contains the KMP `lib` root and its Android, JVM, Wasm/JS, Linux x64/ARM64, Windows x64, macOS x64/ARM64, and iOS ARM64 targets; the `guest-runtime` KMP root and Wasm/WASI target; the Gradle plugin implementation; and the host/extension plugin markers.

A released host application normally depends on the KMP root:

```kotlin
implementation("dev.brahmkshatriya.wasmtime:lib:<version>")
```

Extension projects apply the published plugin; it adds the matching `guest-runtime` as a compile-only dependency automatically:

```kotlin
plugins {
    id("dev.brahmkshatriya.wasmtime.extension") version "<version>"
}
```

`Wasmtime KMP CI and Publish` builds release shards on platform/architecture-specific runners, then merges and verifies one complete Maven repository. The shards are: architecture-neutral KMP/JVM/Web/WASI/plugin metadata on Ubuntu x64; Linux x64 on `ubuntu-24.04`; Linux ARM64 native payloads on `ubuntu-24.04-arm` followed by KLIB packaging on the supported Linux x64 Kotlin/Native compiler host; Windows x64 on `windows-2025`; macOS x64 on `macos-15-intel`; macOS ARM64 on Apple Silicon `macos-15`; and iOS ARM64 on a separate Apple Silicon macOS job. Android ARM64 and Android x86_64 JNI payloads are also built in separate jobs, then combined into one multi-ABI AAR without rebuilding either payload. Android uses an x64 Linux host because the Android NDK's Linux host package is x86_64-only.

Pushes and pull requests use CI-only versions. Tags such as `v0.1.0` publish automatically; `workflow_dispatch` accepts an explicit version and a `publish` switch. Central publication consumes the exact merged artifact and does not rebuild it.

The workflow uses two GitHub secrets: `GRADLE_PROPERTIES_CONTENT` and `GPG_SECRET_KEY_RING_BASE64`. `GRADLE_PROPERTIES_CONTENT` supplies Maven Central and signing properties, while the base64 secret restores the signing key ring.

To reproduce individual shards locally, point them at separate repository directories. For example, on Linux x64:

```bash
export WASMTIME_MAVEN_REPOSITORY="$PWD/release/maven-common"
./gradlew --no-daemon -PVERSION=0.1.0 publishWasmtimeCommonToMavenRepository

export WASMTIME_MAVEN_REPOSITORY="$PWD/release/maven-linux-x64"
./gradlew --no-daemon -PVERSION=0.1.0 publishWasmtimeLinuxX64ToMavenRepository
```

The other native shard tasks are `publishWasmtimeLinuxArm64ToMavenRepository`, `publishWasmtimeWindowsX64ToMavenRepository`, `publishWasmtimeMacosX64ToMavenRepository`, `publishWasmtimeMacosArm64ToMavenRepository`, and `publishWasmtimeIosArm64ToMavenRepository`. Android CI builds `:lib:buildAndroidArm64NativeBridge` and `:lib:buildAndroidX64NativeBridge` separately, then packages them with `publishWasmtimeAndroidToMavenRepository -PwasmtimeAndroidPrebuilt=true`. Merge all repository shard directories with `scripts/merge-wasmtime-repositories.py`, then run `scripts/verify-wasmtime-repository.py` and `scripts/verify-wasmtime-consumer.sh` on the merged repository before signing or publishing.
