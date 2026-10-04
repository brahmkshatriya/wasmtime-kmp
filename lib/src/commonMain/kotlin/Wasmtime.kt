package dev.brahmkshatriya.wasmtime

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

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

public data class WasmtimeHttpHeader(
    public val name: String,
    public val value: String,
)

public data class WasmtimeHttpRequest(
    public val method: String,
    public val url: String,
    public val headers: List<WasmtimeHttpHeader> = emptyList(),
    public val body: ByteArray = ByteArray(0),
)

public data class WasmtimeHttpResponse(
    public val statusCode: Int,
    public val headers: List<WasmtimeHttpHeader> = emptyList(),
    public val body: ByteArray = ByteArray(0),
) {
    init {
        require(statusCode in 100..599) { "statusCode must be between 100 and 599" }
    }
}


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

public data class WasmtimeRuntimeManifestEntry(
    public val moduleName: String,
    public val fileName: String,
)

/** Parses the runtime.tsv format emitted by the Wasmtime host Gradle plugin. */
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

public suspend fun loadWasmtimeRuntime(
    manifest: String,
    loadModule: suspend (fileName: String) -> ByteArray,
): WasmtimeRuntime = WasmtimeRuntime(
    parseWasmtimeRuntimeManifest(manifest).map { entry ->
        WasmtimeRuntimeModule(entry.moduleName, loadModule(entry.fileName))
    },
)

public fun interface WasmtimeHttpHandler {
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

public object Wasmtime {
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

    public fun load(
        wasm: ByteArray,
        limits: WasmtimeLimits = WasmtimeLimits(),
        httpHandler: WasmtimeHttpHandler? = null,
        storage: WasmtimeStorage? = null,
        runtime: WasmtimeRuntime? = null,
    ): WasmtimeInstance {
        require(wasm.isNotEmpty()) { "wasm must not be empty" }
        require(limits.maxModuleBytes == 0 || wasm.size <= limits.maxModuleBytes) {
            "wasm module is too large: ${wasm.size} > ${limits.maxModuleBytes} bytes"
        }
        validateRuntimeModuleSizes(limits, runtime)
        return WasmtimeInstance(
            createPlatformWasmtimeInstance(wasm, limits, httpHandler, storage, runtime),
        )
    }
}

@OptIn(ExperimentalAtomicApi::class)
public class WasmtimeModule internal constructor(
    private val platform: PlatformWasmtimeModule,
) {
    private val activeUses = AtomicInt(0)
    private val closing = AtomicInt(0)
    private val platformClosed = AtomicInt(0)

    public fun instantiate(
        limits: WasmtimeLimits = WasmtimeLimits(),
        httpHandler: WasmtimeHttpHandler? = null,
        storage: WasmtimeStorage? = null,
        runtime: WasmtimeRuntime? = null,
    ): WasmtimeInstance {
        validateRuntimeModuleSizes(limits, runtime)
        beginUse()
        try {
            return WasmtimeInstance(platform.instantiate(limits, httpHandler, storage, runtime))
        } finally {
            endUse()
        }
    }

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

@OptIn(ExperimentalAtomicApi::class)
public class WasmtimeInstance internal constructor(
    private val platform: PlatformWasmtimeInstance,
) {
    private val lifecycleLock = AtomicInt(0)
    private val activeUses = AtomicInt(0)
    private val closing = AtomicInt(0)
    private val platformClosed = AtomicInt(0)
    private val i32Functions = mutableMapOf<String, WasmtimeI32Function>()

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

    public fun callI32(exportName: String, first: Int, second: Int): Int =
        functionI32(exportName)(first, second)

    public suspend fun callI32Async(exportName: String, first: Int, second: Int): Int =
        functionI32(exportName).invokeAsync(first, second)

    public fun close() {
        withLifecycleLock {
            if (!closing.compareAndSet(0, 1)) return@withLifecycleLock
            i32Functions.values.forEach { it.closeInternal() }
            i32Functions.clear()
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

@OptIn(ExperimentalAtomicApi::class)
public class WasmtimeI32Function internal constructor(
    private val platform: PlatformWasmtimeI32Function,
    private val ownerBeginUse: () -> Unit,
    private val ownerEndUse: () -> Unit,
) {
    private val activeUses = AtomicInt(0)
    private val closing = AtomicInt(0)
    private val platformClosed = AtomicInt(0)

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
): PlatformWasmtimeInstance
