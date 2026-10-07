package dev.brahmkshatriya.wasmtime

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Resource limits applied to an untrusted Wasm instance.
 *
 * A value of `0` disables the corresponding limit where noted. Defaults are intentionally
 * conservative for plugin-style workloads; applications can create a different policy per load.
 *
 * @property maxModuleBytes maximum size of each accepted Wasm module, in bytes. `0` disables the cap.
 * @property maxMemoryBytes maximum linear-memory / Wasm-GC memory budget, in bytes. `0` disables the cap.
 * @property fuel Wasmtime fuel available to native/JVM execution. `0` disables the finite-fuel bound.
 *   Browser WebAssembly cannot enforce Wasmtime fuel and relies on the extension transport timeout.
 * @property maxExecutionMillis wall-clock execution deadline in milliseconds. `0` disables the deadline.
 * @property maxTableElements maximum number of elements permitted in a Wasm table. `0` disables the cap.
 * @property maxHostCallBytes maximum byte count accepted by a single bounded guest-to-host operation.
 *   `0` disables the cap.
 * @property maxOutputBytes maximum cumulative guest stdout/stderr-style output accepted by the host.
 *   `0` disables the cap.
 * @property maxHttpResponseBytes maximum encoded HTTP response size returned to the guest. `0` disables the cap.
 * @property maxWasiPollMillis maximum delay a guest may request in one WASI `poll_oneoff` operation.
 *   `0` disables the cap.
 */
public data class WasmtimeLimits(
    /** Maximum size of an untrusted root Wasm module accepted by [Wasmtime.load]. Zero disables the cap. */
    public val maxModuleBytes: Int = 16 * 1024 * 1024,
    public val maxMemoryBytes: Long = 64L * 1024L * 1024L,
    public val fuel: Long = 100_000_000L,
    public val maxExecutionMillis: Long = 30_000L,
    public val maxTableElements: Long = 1_000_000L,
    public val maxHostCallBytes: Int = 1 * 1024 * 1024,
    public val maxOutputBytes: Long = 1L * 1024L * 1024L,
    public val maxHttpResponseBytes: Int = 16 * 1024 * 1024,
    public val maxWasiPollMillis: Long = 1_000L,
) {
    init {
        require(maxModuleBytes >= 0) { "maxModuleBytes must be >= 0" }
        require(maxMemoryBytes >= 0) { "maxMemoryBytes must be >= 0" }
        require(fuel >= 0) { "fuel must be >= 0" }
        require(maxExecutionMillis >= 0) { "maxExecutionMillis must be >= 0" }
        require(maxTableElements >= 0) { "maxTableElements must be >= 0" }
        require(maxHostCallBytes >= 0) { "maxHostCallBytes must be >= 0" }
        require(maxOutputBytes >= 0) { "maxOutputBytes must be >= 0" }
        require(maxHttpResponseBytes >= 0) { "maxHttpResponseBytes must be >= 0" }
        require(maxWasiPollMillis >= 0) { "maxWasiPollMillis must be >= 0" }
    }
}

/**
 * A single HTTP header crossing the explicit Wasmtime HTTP capability boundary.
 *
 * @property name header name as seen by the guest/host bridge.
 * @property value header value without transport-specific encoding.
 */
public data class WasmtimeHttpHeader(
    public val name: String,
    public val value: String,
)

/**
 * HTTP request issued by guest code through the optional host HTTP capability.
 *
 * The host is responsible for enforcing its own URL, method, redirect, authentication, and network policy.
 * Supplying a [WasmtimeHttpHandler] grants only the requests that the handler chooses to execute.
 *
 * @property method HTTP method supplied by the guest.
 * @property url absolute URL supplied by the guest.
 * @property headers request headers supplied by the guest.
 * @property body raw request body bytes.
 */
public data class WasmtimeHttpRequest(
    public val method: String,
    public val url: String,
    public val headers: List<WasmtimeHttpHeader> = emptyList(),
    public val body: ByteArray = ByteArray(0),
)

/**
 * HTTP response returned by [WasmtimeHttpHandler] to the guest.
 *
 * @property statusCode HTTP status in the inclusive range `100..599`.
 * @property headers response headers exposed to the guest.
 * @property body raw response body bytes.
 */
public data class WasmtimeHttpResponse(
    public val statusCode: Int,
    public val headers: List<WasmtimeHttpHeader> = emptyList(),
    public val body: ByteArray = ByteArray(0),
) {
    init {
        require(statusCode in 100..599) { "statusCode must be between 100 and 599" }
    }
}


/**
 * Explicit persistent-storage capability mounted into a guest.
 *
 * The guest only sees [guestPath]; it does not receive general host-filesystem access. Native/JVM hosts use
 * [backingPath] as a host directory. The browser backend interprets it as an OPFS-relative directory.
 *
 * @property backingPath host directory, or OPFS-relative browser directory, used for persistent data.
 * @property guestPath absolute path exposed inside the guest. The guest filesystem root cannot be mounted.
 * @property readOnly whether guest writes are rejected.
 * @property maxBytes maximum total bytes below this mount; `0` disables this quota.
 * @property maxEntries maximum files, directories, and symlinks below this mount; `0` disables this quota.
 * @property maxFileBytes maximum logical size of one regular file; `0` disables this quota.
 */
public data class WasmtimeStorage(
    /** Native/JVM host directory, or an OPFS-relative directory on WasmJS. */
    public val backingPath: String,
    /** Absolute path visible to the guest. */
    public val guestPath: String = "/data",
    public val readOnly: Boolean = false,
    /** Total bytes the guest may keep below this mount. Zero disables the quota. */
    public val maxBytes: Long = 64L * 1024L * 1024L,
    /** Maximum files, directories, and symlinks below this mount. Zero disables the quota. */
    public val maxEntries: Int = 4_096,
    /** Maximum logical size of one regular file. Zero disables the quota. */
    public val maxFileBytes: Long = 16L * 1024L * 1024L,
) {
    init {
        require(backingPath.isNotBlank()) { "backingPath must not be blank" }
        require('\u0000' !in backingPath) { "backingPath must not contain NUL" }
        require(guestPath.startsWith('/')) { "guestPath must be absolute" }
        require(guestPath != "/") { "guestPath must not expose the guest filesystem root" }
        require('\u0000' !in guestPath) { "guestPath must not contain NUL" }
        require(maxBytes >= 0) { "maxBytes must be >= 0" }
        require(maxEntries >= 0) { "maxEntries must be >= 0" }
        require(maxFileBytes >= 0) { "maxFileBytes must be >= 0" }
    }
}

/**
 * One open-world Wasm module supplied by the host runtime bundle.
 *
 * [name] must match the import-module name expected by dependent Wasm modules (for example `<kotlin>`).
 *
 * @property name import-module name used when linking the open-world graph.
 * @property wasm validated WebAssembly module bytes.
 */
public data class WasmtimeRuntimeModule(
    public val name: String,
    public val wasm: ByteArray,
) {
    init {
        require(name.isNotBlank()) { "runtime module name must not be blank" }
        require('\u0000' !in name) { "runtime module name must not contain NUL" }
        require(wasm.isNotEmpty()) { "runtime module wasm must not be empty" }
    }
}

/**
 * Ordered shared runtime modules made available while a root extension is instantiated.
 *
 * Prefer [loadWasmtimeRuntime] with the `runtime.tsv` produced by the host Gradle plugin instead of
 * manually constructing the module list.
 *
 * @property modules modules in the load order chosen by the runtime builder.
 */
public data class WasmtimeRuntime(
    public val modules: List<WasmtimeRuntimeModule>,
) {
    init {
        require(modules.isNotEmpty()) { "runtime must contain at least one module" }
        require(modules.map { it.name }.distinct().size == modules.size) {
            "runtime module names must be unique"
        }
    }
}

/**
 * A module-name/file-name pair parsed from a generated Wasmtime runtime manifest.
 *
 * @property moduleName import-module name recorded in `runtime.tsv`.
 * @property fileName manifest-relative Wasm file name.
 */
public data class WasmtimeRuntimeManifestEntry(
    public val moduleName: String,
    public val fileName: String,
)

/**
 * Parses the `runtime.tsv` format emitted by the `dev.brahmkshatriya.wasmtime.host` Gradle plugin.
 *
 * Each non-empty line is `<module-name>\tfile-name`. The returned order is the load order selected by
 * the runtime builder.
 *
 * @throws IllegalArgumentException if a non-empty line is malformed.
 */
public fun parseWasmtimeRuntimeManifest(manifest: String): List<WasmtimeRuntimeManifestEntry> =
    manifest.lineSequence()
        .filter(String::isNotBlank)
        .map { line ->
            val separator = line.indexOf('\t')
            require(separator > 0 && separator < line.lastIndex) {
                "invalid Wasmtime runtime manifest entry: $line"
            }
            WasmtimeRuntimeManifestEntry(
                moduleName = line.substring(0, separator),
                fileName = line.substring(separator + 1),
            )
        }
        .toList()

/**
 * Loads every module referenced by a generated runtime manifest.
 *
 * This is convenient for Compose resources, classpath resources, or any storage system where the caller
 * knows how to turn a manifest file name into bytes.
 *
 * ```kotlin
 * val runtime = loadWasmtimeRuntime(runtimeManifestText) { fileName ->
 *     loadResourceBytes("wasmtime/runtime/$fileName")
 * }
 * ```
 *
 * @param manifest text from `build/wasmtime/runtime/runtime.tsv`.
 * @param loadModule callback that loads one manifest-relative Wasm file.
 */
public suspend fun loadWasmtimeRuntime(
    manifest: String,
    loadModule: suspend (fileName: String) -> ByteArray,
): WasmtimeRuntime = WasmtimeRuntime(
    parseWasmtimeRuntimeManifest(manifest).map { entry ->
        WasmtimeRuntimeModule(entry.moduleName, loadModule(entry.fileName))
    },
)

/**
 * Host capability used when guest code performs an HTTP request.
 *
 * No network capability is granted when this handler is `null`. Treat [execute] as a security boundary:
 * validate the final destination and redirect policy before forwarding an untrusted guest request.
 */
public fun interface WasmtimeHttpHandler {
    /** Executes an allowed [request] and returns the response that should be exposed to guest code. */
    public suspend fun execute(request: WasmtimeHttpRequest): WasmtimeHttpResponse
}

internal fun validateRuntimeModuleSizes(
    limits: WasmtimeLimits,
    runtime: WasmtimeRuntime?,
) {
    if (limits.maxModuleBytes == 0 || runtime == null) return
    runtime.modules.forEach { module ->
        require(module.wasm.size <= limits.maxModuleBytes) {
            "runtime module ${module.name} is too large: ${module.wasm.size} > ${limits.maxModuleBytes} bytes"
        }
    }
}

/**
 * Entry point for compiling and instantiating Wasm modules.
 *
 * For one-off execution, use [load]. For repeated instantiation of the same trusted module bytes, use
 * [compile] once and call [WasmtimeModule.instantiate] multiple times.
 *
 * ```kotlin
 * val instance = Wasmtime.load(
 *     wasm = pluginBytes,
 *     limits = WasmtimeLimits(maxExecutionMillis = 5_000),
 *     storage = WasmtimeStorage("/app/plugin-data"),
 * )
 * try {
 *     val result = instance.callI32("add", 20, 22)
 * } finally {
 *     instance.close()
 * }
 * ```
 *
 * For generated suspend-contract extensions, prefer [createWasmtimeExtensionTransport] instead of calling
 * low-level exports directly.
 */
public object Wasmtime {
    /**
     * Compiles [wasm] into a reusable module without instantiating it.
     *
     * Compilation does not grant HTTP, storage, or shared-runtime capabilities; those are supplied for each
     * [WasmtimeModule.instantiate] call.
     *
     * @param maxModuleBytes maximum accepted input size, or `0` for no size cap.
     * @throws IllegalArgumentException when [wasm] is empty or exceeds [maxModuleBytes].
     */
    public fun compile(
        wasm: ByteArray,
        maxModuleBytes: Int = WasmtimeLimits().maxModuleBytes,
    ): WasmtimeModule {
        require(wasm.isNotEmpty()) { "wasm must not be empty" }
        require(maxModuleBytes >= 0) { "maxModuleBytes must be >= 0" }
        require(maxModuleBytes == 0 || wasm.size <= maxModuleBytes) {
            "wasm module is too large: ${wasm.size} > $maxModuleBytes bytes"
        }
        return WasmtimeModule(createPlatformWasmtimeModule(wasm))
    }

    /**
     * Compiles and immediately instantiates a Wasm module.
     *
     * Every optional host capability is deny-by-default: omit [httpHandler] for no HTTP access and omit
     * [storage] for no persistent filesystem mount. [runtime] supplies open-world shared modules generated by
     * the host Gradle plugin.
     *
     * @throws IllegalArgumentException when module bytes or runtime modules violate [limits].
     */
    public fun load(
        wasm: ByteArray,
        limits: WasmtimeLimits = WasmtimeLimits(),
        httpHandler: WasmtimeHttpHandler? = null,
        storage: WasmtimeStorage? = null,
        runtime: WasmtimeRuntime? = null,
        imports: WasmtimeImports = WasmtimeImports.Empty,
    ): WasmtimeInstance {
        require(wasm.isNotEmpty()) { "wasm must not be empty" }
        require(limits.maxModuleBytes == 0 || wasm.size <= limits.maxModuleBytes) {
            "wasm module is too large: ${wasm.size} > ${limits.maxModuleBytes} bytes"
        }
        validateRuntimeModuleSizes(limits, runtime)
        return WasmtimeInstance(
            createPlatformWasmtimeInstance(wasm, limits, httpHandler, storage, runtime, imports),
        )
    }
}

/**
 * Reusable compiled Wasm module returned by [Wasmtime.compile].
 *
 * Instances created from this module are independent and may use different limits/capabilities. Call [close]
 * when no more instances will be created. Closing is idempotent.
 */
@OptIn(ExperimentalAtomicApi::class)
public class WasmtimeModule internal constructor(
    private val platform: PlatformWasmtimeModule,
) {
    private val activeUses = AtomicInt(0)
    private val closing = AtomicInt(0)
    private val platformClosed = AtomicInt(0)

    /**
     * Creates an instance with the supplied resource policy and explicit host capabilities.
     *
     * @throws IllegalStateException if this compiled module has already been closed.
     */
    public fun instantiate(
        limits: WasmtimeLimits = WasmtimeLimits(),
        httpHandler: WasmtimeHttpHandler? = null,
        storage: WasmtimeStorage? = null,
        runtime: WasmtimeRuntime? = null,
        imports: WasmtimeImports = WasmtimeImports.Empty,
    ): WasmtimeInstance {
        validateRuntimeModuleSizes(limits, runtime)
        beginUse()
        try {
            return WasmtimeInstance(platform.instantiate(limits, httpHandler, storage, runtime, imports))
        } finally {
            endUse()
        }
    }

    /** Releases the compiled module after any in-flight instantiation finishes. Safe to call more than once. */
    public fun close() {
        if (!closing.compareAndSet(0, 1)) return
        if (activeUses.load() == 0) closePlatformOnce()
    }

    private fun beginUse() {
        check(closing.load() == 0) { "Wasmtime module is closed" }
        activeUses.addAndFetch(1)
        if (closing.load() != 0) {
            finishUse()
            error("Wasmtime module is closed")
        }
    }

    private fun endUse() {
        finishUse()
    }

    private fun finishUse() {
        val remaining = activeUses.addAndFetch(-1)
        check(remaining >= 0) { "Wasmtime module lifecycle underflow" }
        if (remaining == 0 && closing.load() != 0) closePlatformOnce()
    }

    private fun closePlatformOnce() {
        if (platformClosed.compareAndSet(0, 1)) platform.close()
    }
}

/**
 * Live Wasm instance with resolved host capabilities.
 *
 * Resolve frequently-called integer exports once with [functionI32]. Use [callI32] for simple synchronous
 * calls, or [callI32Async] when an export may suspend in an asynchronous host capability. Call [close] when
 * finished; previously resolved function handles become unusable when the instance closes.
 */
@OptIn(ExperimentalAtomicApi::class)
public class WasmtimeInstance internal constructor(
    private val platform: PlatformWasmtimeInstance,
) {
    private val lifecycleLock = AtomicInt(0)
    private val activeUses = AtomicInt(0)
    private val closing = AtomicInt(0)
    private val platformClosed = AtomicInt(0)
    private val i32Functions = mutableMapOf<String, WasmtimeI32Function>()
    private val functions = mutableMapOf<Pair<String, WasmtimeFunctionType>, WasmtimeFunction>()
    private val memories = mutableMapOf<String, WasmtimeMemory>()

    /**
     * Resolves and caches an exported function with signature `(i32, i32) -> i32`.
     *
     * @throws IllegalStateException if the instance is closed or the export cannot be used with this ABI.
     */
    public fun functionI32(exportName: String): WasmtimeI32Function {
        beginUse()
        try {
            return withLifecycleLock {
                check(closing.load() == 0) { "Wasmtime instance is closed" }
                i32Functions.getOrPut(exportName) {
                    WasmtimeI32Function(
                        platform = platform.resolveI32(exportName),
                        ownerBeginUse = ::beginUse,
                        ownerEndUse = ::endUse,
                    )
                }
            }
        } finally {
            endUse()
        }
    }

    /** Calls an `(i32, i32) -> i32` export synchronously. */
    public fun callI32(exportName: String, first: Int, second: Int): Int =
        functionI32(exportName)(first, second)

    /**
     * Calls an `(i32, i32) -> i32` export through the async execution path.
     *
     * Use this when the guest may enter a suspending host capability such as [WasmtimeHttpHandler].
     */
    public suspend fun callI32Async(exportName: String, first: Int, second: Int): Int =
        functionI32(exportName).invokeAsync(first, second)

    /** Resolves and caches an exported scalar function with the exact [type] supplied by the caller. */
    public fun function(exportName: String, type: WasmtimeFunctionType): WasmtimeFunction {
        beginUse()
        try {
            return withLifecycleLock {
                check(closing.load() == 0) { "Wasmtime instance is closed" }
                functions.getOrPut(exportName to type) {
                    WasmtimeFunction(
                        type = type,
                        platform = platform.resolveFunction(exportName, type),
                        ownerBeginUse = ::beginUse,
                        ownerEndUse = ::endUse,
                    )
                }
            }
        } finally {
            endUse()
        }
    }

    /** Calls an exported scalar function once. */
    public fun call(
        exportName: String,
        type: WasmtimeFunctionType,
        arguments: List<WasmValue> = emptyList(),
    ): List<WasmValue> = function(exportName, type).call(arguments)

    /** Resolves an exported linear memory, usually named `memory`. */
    public fun memory(exportName: String = "memory"): WasmtimeMemory {
        beginUse()
        try {
            return withLifecycleLock {
                check(closing.load() == 0) { "Wasmtime instance is closed" }
                memories.getOrPut(exportName) {
                    WasmtimeMemory(
                        platform = platform.resolveMemory(exportName),
                        ownerBeginUse = ::beginUse,
                        ownerEndUse = ::endUse,
                    )
                }
            }
        } finally {
            endUse()
        }
    }

    /** Releases the instance after in-flight calls finish. Safe to call more than once. */
    public fun close() {
        withLifecycleLock {
            if (!closing.compareAndSet(0, 1)) return@withLifecycleLock
            i32Functions.values.forEach { it.closeInternal() }
            i32Functions.clear()
            functions.values.forEach { it.closeInternal() }
            functions.clear()
            memories.clear()
        }
        if (activeUses.load() == 0) closePlatformOnce()
    }

    private fun beginUse() {
        check(closing.load() == 0) { "Wasmtime instance is closed" }
        activeUses.addAndFetch(1)
        if (closing.load() != 0) {
            finishUse()
            error("Wasmtime instance is closed")
        }
    }

    private fun endUse() {
        finishUse()
    }

    private fun finishUse() {
        val remaining = activeUses.addAndFetch(-1)
        check(remaining >= 0) { "Wasmtime instance lifecycle underflow" }
        if (remaining == 0 && closing.load() != 0) closePlatformOnce()
    }

    private fun closePlatformOnce() {
        if (platformClosed.compareAndSet(0, 1)) platform.close()
    }

    private fun <T> withLifecycleLock(block: () -> T): T {
        while (!lifecycleLock.compareAndSet(0, 1)) {
            // Resolution/close sections are intentionally tiny; calls themselves never hold this lock.
        }
        try {
            return block()
        } finally {
            lifecycleLock.store(0)
        }
    }
}

/**
 * Cached `(i32, i32) -> i32` Wasm export resolved by [WasmtimeInstance.functionI32].
 *
 * The handle is owned by its [WasmtimeInstance] and is closed automatically with the instance.
 */
@OptIn(ExperimentalAtomicApi::class)
public class WasmtimeI32Function internal constructor(
    private val platform: PlatformWasmtimeI32Function,
    private val ownerBeginUse: () -> Unit,
    private val ownerEndUse: () -> Unit,
) {
    private val activeUses = AtomicInt(0)
    private val closing = AtomicInt(0)
    private val platformClosed = AtomicInt(0)

    /** Invokes the export synchronously. */
    public operator fun invoke(first: Int, second: Int): Int {
        beginUse()
        try {
            return platform.call(first, second)
        } finally {
            endUse()
        }
    }

    /** Calls the export without blocking the caller while an async host import is pending. */
    public suspend fun invokeAsync(first: Int, second: Int): Int {
        beginUse()
        try {
            return platform.callAsync(first, second)
        } finally {
            endUse()
        }
    }

    internal fun closeInternal() {
        if (!closing.compareAndSet(0, 1)) return
        if (activeUses.load() == 0) closePlatformOnce()
    }

    private fun beginUse() {
        check(closing.load() == 0) { "Wasmtime function is closed" }
        activeUses.addAndFetch(1)
        if (closing.load() != 0) {
            finishFunctionUse()
            error("Wasmtime function is closed")
        }
        try {
            ownerBeginUse()
        } catch (throwable: Throwable) {
            finishFunctionUse()
            throw throwable
        }
    }

    private fun endUse() {
        finishFunctionUse()
        ownerEndUse()
    }

    private fun finishFunctionUse() {
        val remaining = activeUses.addAndFetch(-1)
        check(remaining >= 0) { "Wasmtime function lifecycle underflow" }
        if (remaining == 0 && closing.load() != 0) closePlatformOnce()
    }

    private fun closePlatformOnce() {
        if (platformClosed.compareAndSet(0, 1)) platform.close()
    }
}

internal interface PlatformWasmtimeInstance {
    fun resolveI32(exportName: String): PlatformWasmtimeI32Function
    fun resolveFunction(exportName: String, type: WasmtimeFunctionType): PlatformWasmtimeFunction
    fun resolveMemory(exportName: String): PlatformWasmtimeMemory
    fun close()
}

internal interface PlatformWasmtimeI32Function {
    fun call(first: Int, second: Int): Int
    suspend fun callAsync(first: Int, second: Int): Int
    fun close()
}

internal interface PlatformWasmtimeModule {
    fun instantiate(
        limits: WasmtimeLimits,
        httpHandler: WasmtimeHttpHandler?,
        storage: WasmtimeStorage?,
        runtime: WasmtimeRuntime?,
        imports: WasmtimeImports,
    ): PlatformWasmtimeInstance
    fun close()
}

internal expect fun createPlatformWasmtimeModule(
    wasm: ByteArray,
): PlatformWasmtimeModule

internal expect fun createPlatformWasmtimeInstance(
    wasm: ByteArray,
    limits: WasmtimeLimits,
    httpHandler: WasmtimeHttpHandler?,
    storage: WasmtimeStorage?,
    runtime: WasmtimeRuntime?,
    imports: WasmtimeImports,
): PlatformWasmtimeInstance
