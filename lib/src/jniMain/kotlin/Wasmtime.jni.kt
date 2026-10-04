package dev.brahmkshatriya.wasmtime

import dev.brahmkshatriya.wasmtime.internal.JniHttpHandlerBridge
import dev.brahmkshatriya.wasmtime.internal.NativeWasmtime
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
): PlatformWasmtimeInstance {
    val bridge = httpHandler?.let { JniHttpHandlerBridge(it, limits.maxHttpResponseBytes) }
    val handle = if (runtime == null) {
        NativeWasmtime.nativeLoad(
            wasm,
            limits.maxMemoryBytes,
            limits.fuel,
            limits.maxExecutionMillis,
            limits.maxTableElements,
            limits.maxHostCallBytes,
            limits.maxOutputBytes,
            limits.maxHttpResponseBytes,
            limits.maxWasiPollMillis,
            bridge,
            storage?.backingPath,
            storage?.guestPath,
            storage?.readOnly ?: false,
            storage?.maxBytes ?: 0L,
            storage?.maxEntries ?: 0,
            storage?.maxFileBytes ?: 0L,
        )
    } else {
        NativeWasmtime.nativeLoadWithRuntime(
            wasm,
            runtime.modules.map { it.name }.toTypedArray(),
            runtime.modules.map { it.wasm }.toTypedArray(),
            limits.maxMemoryBytes,
            limits.fuel,
            limits.maxExecutionMillis,
            limits.maxTableElements,
            limits.maxHostCallBytes,
            limits.maxOutputBytes,
            limits.maxHttpResponseBytes,
            limits.maxWasiPollMillis,
            bridge,
            storage?.backingPath,
            storage?.guestPath,
            storage?.readOnly ?: false,
            storage?.maxBytes ?: 0L,
            storage?.maxEntries ?: 0,
            storage?.maxFileBytes ?: 0L,
        )
    }
    return JniWasmtimeInstance(handle, bridge)
}

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
    ): PlatformWasmtimeInstance = handle.withOpen { current ->
        val bridge = httpHandler?.let { JniHttpHandlerBridge(it, limits.maxHttpResponseBytes) }
        val instance = if (runtime == null) {
            NativeWasmtime.nativeInstantiate(
                current,
                limits.maxMemoryBytes,
                limits.fuel,
                limits.maxExecutionMillis,
                limits.maxTableElements,
                limits.maxHostCallBytes,
                limits.maxOutputBytes,
                limits.maxHttpResponseBytes,
                limits.maxWasiPollMillis,
                bridge,
                storage?.backingPath,
                storage?.guestPath,
                storage?.readOnly ?: false,
                storage?.maxBytes ?: 0L,
                storage?.maxEntries ?: 0,
                storage?.maxFileBytes ?: 0L,
            )
        } else {
            NativeWasmtime.nativeInstantiateWithRuntime(
                current,
                runtime.modules.map { it.name }.toTypedArray(),
                runtime.modules.map { it.wasm }.toTypedArray(),
                limits.maxMemoryBytes,
                limits.fuel,
                limits.maxExecutionMillis,
                limits.maxTableElements,
                limits.maxHostCallBytes,
                limits.maxOutputBytes,
                limits.maxHttpResponseBytes,
                limits.maxWasiPollMillis,
                bridge,
                storage?.backingPath,
                storage?.guestPath,
                storage?.readOnly ?: false,
                storage?.maxBytes ?: 0L,
                storage?.maxEntries ?: 0,
                storage?.maxFileBytes ?: 0L,
            )
        }
        JniWasmtimeInstance(instance, bridge)
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

    override fun close() = handle.close(NativeWasmtime::nativeClose)
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

