package dev.brahmkshatriya.wasmtime

import dev.brahmkshatriya.wasmtime.internal.JniHostImportsBridge
import dev.brahmkshatriya.wasmtime.internal.JniHttpHandlerBridge
import dev.brahmkshatriya.wasmtime.internal.NativeWasmtime
import dev.brahmkshatriya.wasmtime.internal.toBits
import dev.brahmkshatriya.wasmtime.internal.toWasmValue
import kotlinx.coroutines.yield

internal actual fun createPlatformWasmtimeModule(
    wasm: ByteArray,
): PlatformWasmtimeModule = JniWasmtimeModule(NativeWasmtime.nativeCompile(wasm))

internal actual fun createPlatformWasmtimeInstance(
    wasm: ByteArray,
    limits: WasmtimeLimits,
    httpHandler: WasmtimeHttpHandler?,
    storage: WasmtimeStorage?,
    runtime: WasmtimeRuntime?,
    imports: WasmtimeImports,
): PlatformWasmtimeInstance {
    val httpBridge = httpHandler?.let { JniHttpHandlerBridge(it, limits.maxHttpResponseBytes) }
    val runtimeModules = runtime?.modules.orEmpty()
    val functions = imports.functions
    val handle = NativeWasmtime.nativeLoadWithRuntimeAndImports(
        wasm,
        runtimeModules.map { it.name }.toTypedArray(),
        runtimeModules.map { it.wasm }.toTypedArray(),
        functions.map { it.module }.toTypedArray(),
        functions.map { it.name }.toTypedArray(),
        functions.map { it.type.parameters.toKindArray() }.toTypedArray(),
        functions.map { it.type.results.toKindArray() }.toTypedArray(),
        imports.takeIf { functions.isNotEmpty() }?.let(::JniHostImportsBridge),
        limits.maxMemoryBytes,
        limits.fuel,
        limits.maxExecutionMillis,
        limits.maxTableElements,
        limits.maxHostCallBytes,
        limits.maxOutputBytes,
        limits.maxHttpResponseBytes,
        limits.maxWasiPollMillis,
        httpBridge,
        storage?.backingPath,
        storage?.guestPath,
        storage?.readOnly ?: false,
        storage?.maxBytes ?: 0L,
        storage?.maxEntries ?: 0,
        storage?.maxFileBytes ?: 0L,
    )
    return JniWasmtimeInstance(handle, httpBridge)
}

private fun List<WasmValueType>.toKindArray(): IntArray =
    IntArray(size) { index -> this[index].ordinal }

private class JniHandle(initial: Long) {
    private var value: Long = initial

    fun <T> withOpen(block: (Long) -> T): T = synchronized(this) {
        check(value != 0L) { "native handle is closed" }
        block(value)
    }

    fun close(block: (Long) -> Unit): Unit = synchronized(this) {
        if (value == 0L) return@synchronized
        val current = value
        value = 0L
        block(current)
    }
}

private class JniWasmtimeModule(
    handle: Long,
) : PlatformWasmtimeModule {
    private val handle = JniHandle(handle)

    override fun instantiate(
        limits: WasmtimeLimits,
        httpHandler: WasmtimeHttpHandler?,
        storage: WasmtimeStorage?,
        runtime: WasmtimeRuntime?,
        imports: WasmtimeImports,
    ): PlatformWasmtimeInstance = handle.withOpen { current ->
        val httpBridge = httpHandler?.let { JniHttpHandlerBridge(it, limits.maxHttpResponseBytes) }
        val runtimeModules = runtime?.modules.orEmpty()
        val functions = imports.functions
        val instance = NativeWasmtime.nativeInstantiateWithRuntimeAndImports(
            current,
            runtimeModules.map { it.name }.toTypedArray(),
            runtimeModules.map { it.wasm }.toTypedArray(),
            functions.map { it.module }.toTypedArray(),
            functions.map { it.name }.toTypedArray(),
            functions.map { it.type.parameters.toKindArray() }.toTypedArray(),
            functions.map { it.type.results.toKindArray() }.toTypedArray(),
            imports.takeIf { functions.isNotEmpty() }?.let(::JniHostImportsBridge),
            limits.maxMemoryBytes,
            limits.fuel,
            limits.maxExecutionMillis,
            limits.maxTableElements,
            limits.maxHostCallBytes,
            limits.maxOutputBytes,
            limits.maxHttpResponseBytes,
            limits.maxWasiPollMillis,
            httpBridge,
            storage?.backingPath,
            storage?.guestPath,
            storage?.readOnly ?: false,
            storage?.maxBytes ?: 0L,
            storage?.maxEntries ?: 0,
            storage?.maxFileBytes ?: 0L,
        )
        JniWasmtimeInstance(instance, httpBridge)
    }

    override fun close() = handle.close(NativeWasmtime::nativeModuleClose)
}

private class JniWasmtimeInstance(
    handle: Long,
    private val httpBridge: JniHttpHandlerBridge?,
) : PlatformWasmtimeInstance {
    private val handle = JniHandle(handle)

    override fun resolveI32(exportName: String): PlatformWasmtimeI32Function =
        handle.withOpen { current ->
            JniWasmtimeI32Function(
                NativeWasmtime.nativeResolveI32(current, exportName),
                httpBridge,
            )
        }

    override fun resolveFunction(
        exportName: String,
        type: WasmtimeFunctionType,
    ): PlatformWasmtimeFunction = handle.withOpen { current ->
        JniWasmtimeFunction(
            handle = NativeWasmtime.nativeResolveFunction(
                current,
                exportName,
                type.parameters.toKindArray(),
                type.results.toKindArray(),
            ),
            type = type,
        )
    }

    override fun resolveMemory(exportName: String): PlatformWasmtimeMemory =
        JniWasmtimeMemory(handle, exportName).also { it.size() }

    override fun close() = handle.close(NativeWasmtime::nativeClose)
}

private class JniWasmtimeFunction(
    handle: Long,
    private val type: WasmtimeFunctionType,
) : PlatformWasmtimeFunction {
    private val handle = JniHandle(handle)

    override fun call(arguments: List<WasmValue>): List<WasmValue> =
        handle.withOpen { current ->
            val resultBits = NativeWasmtime.nativeCallResolvedFunction(
                current,
                arguments.map(WasmValue::toBits).toLongArray(),
            )
            require(resultBits.size == type.results.size) { "Wasm result count mismatch" }
            type.results.mapIndexed { index, resultType ->
                resultBits[index].toWasmValue(resultType)
            }
        }

    override fun close() = handle.close(NativeWasmtime::nativeGenericFunctionClose)
}

private class JniWasmtimeMemory(
    private val instance: JniHandle,
    private val exportName: String,
) : PlatformWasmtimeMemory {
    override fun size(): Int = instance.withOpen { current ->
        NativeWasmtime.nativeMemorySize(current, exportName)
    }

    override fun read(offset: Int, length: Int): ByteArray = instance.withOpen { current ->
        NativeWasmtime.nativeMemoryRead(current, exportName, offset, length)
    }

    override fun write(offset: Int, bytes: ByteArray) {
        instance.withOpen { current ->
            NativeWasmtime.nativeMemoryWrite(current, exportName, offset, bytes)
        }
    }
}

private class JniWasmtimeI32Function(
    handle: Long,
    private val httpBridge: JniHttpHandlerBridge?,
) : PlatformWasmtimeI32Function {
    private val handle = JniHandle(handle)

    override fun call(first: Int, second: Int): Int = handle.withOpen { current ->
        NativeWasmtime.nativeCallResolvedI32(current, first, second)
    }

    override suspend fun callAsync(first: Int, second: Int): Int {
        val future = handle.withOpen { current ->
            NativeWasmtime.nativeCallResolvedI32AsyncStart(current, first, second)
        }
        check(future != 0L) { "failed to create Wasmtime async call" }
        try {
            while (true) {
                val value = NativeWasmtime.nativeCallResolvedI32AsyncPoll(future)
                if (value != NativeWasmtime.ASYNC_PENDING) return value.toInt()
                httpBridge?.awaitProgress() ?: yield()
            }
        } finally {
            NativeWasmtime.nativeCallResolvedI32AsyncClose(future)
        }
    }

    override fun close() = handle.close(NativeWasmtime::nativeFunctionClose)
}
