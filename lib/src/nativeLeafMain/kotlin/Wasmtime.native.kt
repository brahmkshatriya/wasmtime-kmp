@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.wasmtime

import dev.brahmkshatriya.wasmtime.cinterop.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.ptr
import kotlinx.cinterop.pointed
import kotlinx.cinterop.pin
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import platform.posix.free
import platform.posix.malloc
import platform.posix.memcpy
import platform.posix.size_tVar

internal actual fun createPlatformWasmtimeModule(
    wasm: ByteArray,
): PlatformWasmtimeModule {
    val handle = wasm.usePinned { pinned ->
        memScoped {
            val error = alloc<CPointerVar<ByteVar>>()
            error.value = null
            val compiled = wasmtime_kmp_compile(
                pinned.addressOf(0).reinterpret(),
                wasm.size.convert(),
                error.ptr,
            )
            if (compiled == null) throwWasmtimeError(error.value)
            compiled
        }
    }
    return NativeWasmtimeModule(handle)
}

internal actual fun createPlatformWasmtimeInstance(
    wasm: ByteArray,
    limits: WasmtimeLimits,
    httpHandler: WasmtimeHttpHandler?,
    storage: WasmtimeStorage?,
    runtime: WasmtimeRuntime?,
    imports: WasmtimeImports,
): PlatformWasmtimeInstance {
    val httpBridge = httpHandler?.let { NativeHttpBridge(it, limits.maxHttpResponseBytes) }
    val handle = wasm.usePinned { pinned ->
        memScoped {
            val nativeLimits = alloc<wasmtime_kmp_limits_t>()
            nativeLimits.max_memory_bytes = limits.maxMemoryBytes
            nativeLimits.fuel = limits.fuel.toULong()
            nativeLimits.max_execution_millis = limits.maxExecutionMillis
            nativeLimits.max_table_elements = limits.maxTableElements
            nativeLimits.max_host_call_bytes = limits.maxHostCallBytes.toLong()
            nativeLimits.max_output_bytes = limits.maxOutputBytes
            nativeLimits.max_http_response_bytes = limits.maxHttpResponseBytes.toLong()
            nativeLimits.max_wasi_poll_millis = limits.maxWasiPollMillis
            val error = alloc<CPointerVar<ByteVar>>()
            error.value = null
            withNativeHttpHandler(httpBridge) { nativeHttpHandler ->
                withNativeStorage(storage) { nativeStorage ->
                    withNativeRuntime(runtime) { nativeRuntime, nativeRuntimeCount ->
                        withNativeImports(imports) { nativeFunctions, nativeFunctionCount, nativeImports ->
                            val loaded = wasmtime_kmp_load_with_runtime_and_imports(
                                pinned.addressOf(0).reinterpret(),
                                wasm.size.convert(),
                                nativeRuntime,
                                nativeRuntimeCount,
                                nativeFunctions,
                                nativeFunctionCount,
                                nativeImports,
                                nativeLimits.ptr,
                                nativeHttpHandler,
                                nativeStorage,
                                error.ptr,
                            )
                            if (loaded == null) throwWasmtimeError(error.value)
                            loaded
                        }
                    }
                }
            }
        }
    }
    return NativeWasmtimeInstance(handle, httpBridge)
}

@OptIn(ExperimentalAtomicApi::class)
private class NativeHandle<T : Any>(initial: T) {
    private val lock = AtomicInt(0)
    private var value: T? = initial

    fun <R> withOpen(block: (T) -> R): R {
        acquire()
        try {
            return block(checkNotNull(value) { "native handle is closed" })
        } finally {
            release()
        }
    }

    fun close(block: (T) -> Unit) {
        acquire()
        try {
            val current = value ?: return
            value = null
            block(current)
        } finally {
            release()
        }
    }

    private fun acquire() {
        while (!lock.compareAndSet(0, 1)) {
            // Close/start races are rare and the guarded sections are short.
        }
    }

    private fun release() = lock.store(0)
}

private class NativeWasmtimeModule(
    handle: CPointer<wasmtime_kmp_module_t>,
) : PlatformWasmtimeModule {
    private val handle = NativeHandle(handle)

    override fun instantiate(
        limits: WasmtimeLimits,
        httpHandler: WasmtimeHttpHandler?,
        storage: WasmtimeStorage?,
        runtime: WasmtimeRuntime?,
        imports: WasmtimeImports,
    ): PlatformWasmtimeInstance = handle.withOpen { current ->
        val httpBridge = httpHandler?.let { NativeHttpBridge(it, limits.maxHttpResponseBytes) }
        val instance = memScoped {
            val nativeLimits = alloc<wasmtime_kmp_limits_t>()
            nativeLimits.max_memory_bytes = limits.maxMemoryBytes
            nativeLimits.fuel = limits.fuel.toULong()
            nativeLimits.max_execution_millis = limits.maxExecutionMillis
            nativeLimits.max_table_elements = limits.maxTableElements
            nativeLimits.max_host_call_bytes = limits.maxHostCallBytes.toLong()
            nativeLimits.max_output_bytes = limits.maxOutputBytes
            nativeLimits.max_http_response_bytes = limits.maxHttpResponseBytes.toLong()
            nativeLimits.max_wasi_poll_millis = limits.maxWasiPollMillis
            val error = alloc<CPointerVar<ByteVar>>()
            error.value = null
            withNativeHttpHandler(httpBridge) { nativeHttpHandler ->
                withNativeStorage(storage) { nativeStorage ->
                    withNativeRuntime(runtime) { nativeRuntime, nativeRuntimeCount ->
                        withNativeImports(imports) { nativeFunctions, nativeFunctionCount, nativeImports ->
                            val instantiated = wasmtime_kmp_instantiate_with_runtime_and_imports(
                                current,
                                nativeRuntime,
                                nativeRuntimeCount,
                                nativeFunctions,
                                nativeFunctionCount,
                                nativeImports,
                                nativeLimits.ptr,
                                nativeHttpHandler,
                                nativeStorage,
                                error.ptr,
                            )
                            if (instantiated == null) throwWasmtimeError(error.value)
                            instantiated
                        }
                    }
                }
            }
        }
        NativeWasmtimeInstance(instance, httpBridge)
    }

    override fun close() = handle.close(::wasmtime_kmp_module_close)
}

private class NativeWasmtimeInstance(
    handle: CPointer<wasmtime_kmp_instance_t>,
    private val httpBridge: NativeHttpBridge?,
) : PlatformWasmtimeInstance {
    private val handle = NativeHandle(handle)

    override fun resolveI32(exportName: String): PlatformWasmtimeI32Function =
        handle.withOpen { current ->
            val function = memScoped {
                val error = alloc<CPointerVar<ByteVar>>()
                error.value = null
                val resolved = wasmtime_kmp_resolve_i32_2(
                    current,
                    exportName.cstr.getPointer(this),
                    error.ptr,
                )
                if (resolved == null) throwWasmtimeError(error.value)
                resolved
            }
            NativeWasmtimeI32Function(function, httpBridge)
        }

    override fun resolveFunction(
        exportName: String,
        type: WasmtimeFunctionType,
    ): PlatformWasmtimeFunction = handle.withOpen { current ->
        memScoped {
            val parameterKinds = nativeKinds(type.parameters)
            val resultKinds = nativeKinds(type.results)
            val error = alloc<CPointerVar<ByteVar>>()
            error.value = null
            val resolved = wasmtime_kmp_resolve_func(
                current,
                exportName.cstr.getPointer(this),
                parameterKinds,
                type.parameters.size.convert(),
                resultKinds,
                type.results.size.convert(),
                error.ptr,
            )
            if (resolved == null) throwWasmtimeError(error.value)
            NativeWasmtimeFunction(resolved, type)
        }
    }

    override fun resolveMemory(exportName: String): PlatformWasmtimeMemory {
        val memory = NativeWasmtimeMemory(handle, exportName)
        memory.size()
        return memory
    }

    override fun close() = handle.close(::wasmtime_kmp_close)
}

private class NativeWasmtimeFunction(
    handle: CPointer<wasmtime_kmp_func_t>,
    private val type: WasmtimeFunctionType,
) : PlatformWasmtimeFunction {
    private val handle = NativeHandle(handle)

    override fun call(arguments: List<WasmValue>): List<WasmValue> =
        handle.withOpen { current ->
            memScoped {
                val nativeArguments =
                    if (arguments.isEmpty()) null
                    else allocArray<wasmtime_kmp_value_t>(arguments.size).also { native ->
                        arguments.forEachIndexed { index, value ->
                            native[index].setWasmValue(value)
                        }
                    }
                val nativeResults =
                    if (type.results.isEmpty()) null
                    else allocArray<wasmtime_kmp_value_t>(type.results.size)
                val error = alloc<CPointerVar<ByteVar>>()
                error.value = null
                val ok = wasmtime_kmp_func_call(
                    current,
                    nativeArguments,
                    arguments.size.convert(),
                    nativeResults,
                    type.results.size.convert(),
                    error.ptr,
                )
                if (ok == 0) throwWasmtimeError(error.value)
                type.results.mapIndexed { index, resultType ->
                    checkNotNull(nativeResults)[index].toWasmValue(resultType)
                }
            }
        }

    override fun close() = handle.close(::wasmtime_kmp_func_close)
}

private class NativeWasmtimeMemory(
    private val instance: NativeHandle<CPointer<wasmtime_kmp_instance_t>>,
    private val exportName: String,
) : PlatformWasmtimeMemory {
    override fun size(): Int = instance.withOpen { current ->
        memScoped {
            val size = alloc<size_tVar>()
            val error = alloc<CPointerVar<ByteVar>>()
            error.value = null
            val ok = wasmtime_kmp_memory_size(
                current,
                exportName.cstr.getPointer(this),
                size.ptr,
                error.ptr,
            )
            if (ok == 0) throwWasmtimeError(error.value)
            require(size.value <= Int.MAX_VALUE.toULong()) { "Wasm memory is too large" }
            size.value.toInt()
        }
    }

    override fun read(offset: Int, length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        val bytes = ByteArray(length)
        instance.withOpen { current ->
            bytes.usePinned { pinned ->
                memScoped {
                    val error = alloc<CPointerVar<ByteVar>>()
                    error.value = null
                    val ok = wasmtime_kmp_memory_read(
                        current,
                        exportName.cstr.getPointer(this),
                        offset.convert(),
                        pinned.addressOf(0).reinterpret(),
                        length.convert(),
                        error.ptr,
                    )
                    if (ok == 0) throwWasmtimeError(error.value)
                }
            }
        }
        return bytes
    }

    override fun write(offset: Int, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        instance.withOpen { current ->
            bytes.usePinned { pinned ->
                memScoped {
                    val error = alloc<CPointerVar<ByteVar>>()
                    error.value = null
                    val ok = wasmtime_kmp_memory_write(
                        current,
                        exportName.cstr.getPointer(this),
                        offset.convert(),
                        pinned.addressOf(0).reinterpret(),
                        bytes.size.convert(),
                        error.ptr,
                    )
                    if (ok == 0) throwWasmtimeError(error.value)
                }
            }
        }
    }
}

private fun MemScope.nativeKinds(types: List<WasmValueType>): CPointer<IntVar>? {
    if (types.isEmpty()) return null
    return allocArray<IntVar>(types.size).also { native ->
        types.forEachIndexed { index, type -> native[index] = type.ordinal }
    }
}

private class NativeWasmtimeI32Function(
    handle: CPointer<wasmtime_kmp_func_i32_2_t>,
    private val httpBridge: NativeHttpBridge?,
) : PlatformWasmtimeI32Function {
    private val handle = NativeHandle(handle)

    override fun call(first: Int, second: Int): Int = handle.withOpen { current ->
        memScoped {
            val result = alloc<IntVar>()
            val error = alloc<CPointerVar<ByteVar>>()
            error.value = null
            val ok = wasmtime_kmp_func_i32_2_call_managed(
                current,
                first,
                second,
                result.ptr,
                error.ptr,
            )
            if (ok == 0) throwWasmtimeError(error.value)
            result.value
        }
    }

    override suspend fun callAsync(first: Int, second: Int): Int {
        val future = handle.withOpen { current ->
            memScoped {
                val error = alloc<CPointerVar<ByteVar>>()
                error.value = null
                val started = wasmtime_kmp_func_i32_2_call_async_start(
                    current, first, second, error.ptr,
                )
                if (started == null) throwWasmtimeError(error.value)
                started
            }
        }
        try {
            while (true) {
                val polled = memScoped {
                    val result = alloc<IntVar>()
                    val error = alloc<CPointerVar<ByteVar>>()
                    error.value = null
                    val status = wasmtime_kmp_func_i32_2_call_async_poll(
                        future, result.ptr, error.ptr,
                    )
                    when (status) {
                        1 -> Result.success(result.value)
                        0 -> null
                        else -> Result.failure(consumeWasmtimeError(error.value))
                    }
                }
                if (polled != null) return polled.getOrThrow()
                httpBridge?.awaitProgress() ?: yield()
            }
        } finally {
            wasmtime_kmp_func_i32_2_call_async_close(future)
        }
    }

    override fun close() = handle.close(::wasmtime_kmp_func_i32_2_close)
}

private fun consumeWasmtimeError(error: CPointer<ByteVar>?): IllegalStateException {
    val message = error?.toKString() ?: "Unknown Wasmtime error"
    if (error != null) wasmtime_kmp_string_free(error)
    return IllegalStateException(message)
}

private fun throwWasmtimeError(error: CPointer<ByteVar>?): Nothing =
    throw consumeWasmtimeError(error)


private inline fun <T> MemScope.withNativeRuntime(
    runtime: WasmtimeRuntime?,
    block: (CPointer<wasmtime_kmp_linked_module_t>?, ULong) -> T,
): T {
    val modules = runtime?.modules.orEmpty()
    if (modules.isEmpty()) return block(null, 0UL)

    val pinned = modules.map { module -> module.wasm.pin() }
    try {
        val native = allocArray<wasmtime_kmp_linked_module_t>(modules.size)
        modules.forEachIndexed { index, module ->
            native[index].name = module.name.cstr.getPointer(this)
            native[index].wasm = pinned[index].addressOf(0).reinterpret()
            native[index].wasm_len = module.wasm.size.convert()
        }
        return block(native, modules.size.convert())
    } finally {
        pinned.forEach { it.unpin() }
    }
}

private inline fun <T> MemScope.withNativeImports(
    imports: WasmtimeImports,
    block: (
        CPointer<wasmtime_kmp_host_function_t>?,
        ULong,
        CPointer<wasmtime_kmp_host_imports_t>?,
    ) -> T,
): T {
    val functions = imports.functions
    if (functions.isEmpty()) return block(null, 0UL, null)

    val nativeFunctions = allocArray<wasmtime_kmp_host_function_t>(functions.size)
    functions.forEachIndexed { index, function ->
        nativeFunctions[index].module = function.module.cstr.getPointer(this)
        nativeFunctions[index].name = function.name.cstr.getPointer(this)
        nativeFunctions[index].function_index = index.convert()

        val parameters = function.type.parameters
        nativeFunctions[index].parameter_count = parameters.size.convert()
        nativeFunctions[index].parameter_kinds =
            if (parameters.isEmpty()) {
                null
            } else {
                allocArray<IntVar>(parameters.size).also { native ->
                    parameters.forEachIndexed { valueIndex, type ->
                        native[valueIndex] = type.ordinal
                    }
                }
            }

        val results = function.type.results
        nativeFunctions[index].result_count = results.size.convert()
        nativeFunctions[index].result_kinds =
            if (results.isEmpty()) {
                null
            } else {
                allocArray<IntVar>(results.size).also { native ->
                    results.forEachIndexed { valueIndex, type ->
                        native[valueIndex] = type.ordinal
                    }
                }
            }
    }

    val stable = StableRef.create(NativeImportsBridge(imports))
    val nativeImports = alloc<wasmtime_kmp_host_imports_t>()
    nativeImports.user_data = stable.asCPointer()
    nativeImports.invoke = staticCFunction(::nativeHostImportInvoke)
    nativeImports.dispose = staticCFunction(::nativeHostImportsDispose)
    return block(
        nativeFunctions,
        functions.size.convert(),
        nativeImports.ptr,
    )
}

private class NativeImportsBridge(
    val imports: WasmtimeImports,
)

private fun nativeHostImportInvoke(
    userData: COpaquePointer?,
    functionIndex: ULong,
    caller: CPointer<wasmtime_kmp_caller_t>?,
    arguments: CPointer<wasmtime_kmp_value_t>?,
    argumentCount: ULong,
    results: CPointer<wasmtime_kmp_value_t>?,
    resultCount: ULong,
    errorOut: CPointer<CPointerVar<ByteVar>>?,
): Int = try {
    val bridge = checkNotNull(userData).asStableRef<NativeImportsBridge>().get()
    require(functionIndex <= Int.MAX_VALUE.toULong())
    val function = bridge.imports.functions[functionIndex.toInt()]
    require(argumentCount == function.type.parameters.size.toULong())
    require(resultCount == function.type.results.size.toULong())

    val values = function.type.parameters.mapIndexed { index, type ->
        checkNotNull(arguments)[index].toWasmValue(type)
    }
    val callerPointer = checkNotNull(caller)
    val memory = WasmtimeCallerMemory(
        readBlock = { exportName, offset, length ->
            nativeCallerMemoryRead(callerPointer, exportName, offset, length)
        },
        writeBlock = { exportName, offset, bytes ->
            nativeCallerMemoryWrite(callerPointer, exportName, offset, bytes)
        },
        sizeBlock = { exportName ->
            nativeCallerMemorySize(callerPointer, exportName)
        },
    )
    val returned = function.callback.invoke(WasmtimeHostCall(values, memory))
    validateWasmValues(returned, function.type.results, "host import result")
    returned.forEachIndexed { index, value ->
        checkNotNull(results)[index].setWasmValue(value)
    }
    1
} catch (throwable: Throwable) {
    writeNativeError(errorOut, throwable.message ?: throwable.toString())
    0
}

private fun nativeHostImportsDispose(userData: COpaquePointer?) {
    if (userData != null) userData.asStableRef<NativeImportsBridge>().dispose()
}

private fun writeNativeError(
    errorOut: CPointer<CPointerVar<ByteVar>>?,
    message: String,
) {
    if (errorOut == null) return
    val bytes = message.encodeToByteArray()
    val pointer = malloc((bytes.size + 1).convert())?.reinterpret<ByteVar>() ?: return
    bytes.forEachIndexed { index, byte -> pointer[index] = byte }
    pointer[bytes.size] = 0
    errorOut.pointed.value = pointer
}

private fun wasmtime_kmp_value_t.setWasmValue(value: WasmValue) {
    kind = value.type.ordinal
    bits = when (value) {
        is WasmValue.I32 -> value.value.toUInt().toULong()
        is WasmValue.I64 -> value.value.toULong()
        is WasmValue.F32 -> value.value.toBits().toUInt().toULong()
        is WasmValue.F64 -> value.value.toBits().toULong()
    }
}

private fun wasmtime_kmp_value_t.toWasmValue(type: WasmValueType): WasmValue =
    when (type) {
        WasmValueType.I32 -> WasmValue.I32(bits.toInt())
        WasmValueType.I64 -> WasmValue.I64(bits.toLong())
        WasmValueType.F32 -> WasmValue.F32(Float.fromBits(bits.toInt()))
        WasmValueType.F64 -> WasmValue.F64(Double.fromBits(bits.toLong()))
    }

private fun nativeCallerMemorySize(
    caller: CPointer<wasmtime_kmp_caller_t>,
    exportName: String,
): Int = memScoped {
    val size = alloc<size_tVar>()
    val error = alloc<CPointerVar<ByteVar>>()
    error.value = null
    val ok = wasmtime_kmp_caller_memory_size(
        caller,
        exportName.cstr.getPointer(this),
        size.ptr,
        error.ptr,
    )
    if (ok == 0) throwWasmtimeError(error.value)
    require(size.value <= Int.MAX_VALUE.toULong()) { "Wasm memory is too large" }
    size.value.toInt()
}

private fun nativeCallerMemoryRead(
    caller: CPointer<wasmtime_kmp_caller_t>,
    exportName: String,
    offset: Int,
    length: Int,
): ByteArray {
    if (length == 0) return ByteArray(0)
    val bytes = ByteArray(length)
    bytes.usePinned { pinned ->
        memScoped {
            val error = alloc<CPointerVar<ByteVar>>()
            error.value = null
            val ok = wasmtime_kmp_caller_memory_read(
                caller,
                exportName.cstr.getPointer(this),
                offset.convert(),
                pinned.addressOf(0).reinterpret(),
                length.convert(),
                error.ptr,
            )
            if (ok == 0) throwWasmtimeError(error.value)
        }
    }
    return bytes
}

private fun nativeCallerMemoryWrite(
    caller: CPointer<wasmtime_kmp_caller_t>,
    exportName: String,
    offset: Int,
    bytes: ByteArray,
) {
    if (bytes.isEmpty()) return
    bytes.usePinned { pinned ->
        memScoped {
            val error = alloc<CPointerVar<ByteVar>>()
            error.value = null
            val ok = wasmtime_kmp_caller_memory_write(
                caller,
                exportName.cstr.getPointer(this),
                offset.convert(),
                pinned.addressOf(0).reinterpret(),
                bytes.size.convert(),
                error.ptr,
            )
            if (ok == 0) throwWasmtimeError(error.value)
        }
    }
}

private inline fun <T> withNativeStorage(
    storage: WasmtimeStorage?,
    block: (CPointer<wasmtime_kmp_storage_t>?) -> T,
): T {
    if (storage == null) return block(null)
    return memScoped {
        val native = alloc<wasmtime_kmp_storage_t>()
        native.backing_path = storage.backingPath.cstr.getPointer(this)
        native.guest_path = storage.guestPath.cstr.getPointer(this)
        native.read_only = storage.readOnly
        native.max_bytes = storage.maxBytes
        native.max_entries = storage.maxEntries.toLong()
        native.max_file_bytes = storage.maxFileBytes
        block(native.ptr)
    }
}

private inline fun <T> withNativeHttpHandler(
    bridge: NativeHttpBridge?,
    block: (CPointer<wasmtime_kmp_http_handler_t>?) -> T,
): T {
    if (bridge == null) return block(null)
    val stable = StableRef.create(bridge)
    return memScoped {
        val native = alloc<wasmtime_kmp_http_handler_t>()
        native.user_data = stable.asCPointer()
        native.execute_start = staticCFunction(::nativeHttpExecuteStart)
        native.execute_poll = staticCFunction(::nativeHttpExecutePoll)
        native.execute_dispose = staticCFunction(::nativeHttpExecuteDispose)
        native.free_buffer = staticCFunction(::nativeHttpFreeBuffer)
        native.dispose = staticCFunction(::nativeHttpDispose)
        block(native.ptr)
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class NativeHttpBridge(
    private val handler: WasmtimeHttpHandler,
    private val maxHttpResponseBytes: Int,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val currentOperation = AtomicReference<NativeHttpOperation?>(null)

    fun start(request: WasmtimeHttpRequest): NativeHttpOperation =
        NativeHttpOperation(scope, handler, request, maxHttpResponseBytes).also(currentOperation::store)

    suspend fun awaitProgress() {
        currentOperation.load()?.awaitCompletion() ?: yield()
    }

    fun release(operation: NativeHttpOperation) {
        currentOperation.compareAndSet(operation, null)
    }

    fun close() {
        scope.cancel()
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class NativeHttpOperation(
    scope: CoroutineScope,
    handler: WasmtimeHttpHandler,
    request: WasmtimeHttpRequest,
    maxHttpResponseBytes: Int,
) {
    val state = AtomicInt(0)
    val response = AtomicReference<EncodedHttpResponse?>(null)
    private val completion = CompletableDeferred<Unit>()
    val job: Job = scope.launch {
        try {
            response.store(encodeBoundedHttpResponse(handler.execute(request), maxHttpResponseBytes))
            state.store(1)
        } catch (_: CancellationException) {
            state.store(-1)
        } catch (_: Throwable) {
            state.store(-1)
        } finally {
            completion.complete(Unit)
        }
    }

    suspend fun awaitCompletion() = completion.await()
}

@OptIn(ExperimentalAtomicApi::class)
private fun nativeHttpExecuteStart(
    userData: COpaquePointer?,
    requestMetadata: CPointer<UByteVar>?,
    requestMetadataLen: ULong,
    requestBody: CPointer<UByteVar>?,
    requestBodyLen: ULong,
): COpaquePointer? = try {
    val bridge = checkNotNull(userData)
        .asStableRef<NativeHttpBridge>()
        .get()
    val request = decodeHttpRequestMetadata(
        requestMetadata.toByteArray(requestMetadataLen),
        requestBody.toByteArray(requestBodyLen),
    )
    StableRef.create(bridge.start(request)).asCPointer()
} catch (_: Throwable) {
    null
}

@OptIn(ExperimentalAtomicApi::class)
private fun nativeHttpExecutePoll(
    userData: COpaquePointer?,
    operationPointer: COpaquePointer?,
    responseMetadataOut: CPointer<CPointerVar<UByteVar>>?,
    responseMetadataLenOut: CPointer<size_tVar>?,
    responseBodyOut: CPointer<CPointerVar<UByteVar>>?,
    responseBodyLenOut: CPointer<size_tVar>?,
): Int = try {
    @Suppress("UNUSED_VARIABLE") val ignored = userData
    val operation = checkNotNull(operationPointer)
        .asStableRef<NativeHttpOperation>()
        .get()
    when (operation.state.load()) {
        0 -> 0
        1 -> {
            val response = operation.response.load() ?: return -1
            val nativeMetadata = response.metadata.copyToNativeBuffer() ?: return -1
            val nativeBody = response.body.copyToNativeBuffer()
            if (response.body.isNotEmpty() && nativeBody == null) {
                free(nativeMetadata)
                return -1
            }
            checkNotNull(responseMetadataOut).pointed.value = nativeMetadata
            checkNotNull(responseMetadataLenOut).pointed.value = response.metadata.size.convert()
            checkNotNull(responseBodyOut).pointed.value = nativeBody
            checkNotNull(responseBodyLenOut).pointed.value = response.body.size.convert()
            1
        }
        else -> -1
    }
} catch (_: Throwable) {
    -1
}

private fun nativeHttpExecuteDispose(
    userData: COpaquePointer?,
    operationPointer: COpaquePointer?,
) {
    if (operationPointer == null) return
    val stable = operationPointer.asStableRef<NativeHttpOperation>()
    val operation = stable.get()
    operation.job.cancel()
    if (userData != null) {
        userData.asStableRef<NativeHttpBridge>().get().release(operation)
    }
    stable.dispose()
}

private fun nativeHttpFreeBuffer(
    userData: COpaquePointer?,
    buffer: CPointer<UByteVar>?,
    bufferLen: ULong,
) {
    @Suppress("UNUSED_VARIABLE") val ignoredData = userData
    @Suppress("UNUSED_VARIABLE") val ignoredLength = bufferLen
    free(buffer)
}

private fun CPointer<UByteVar>?.toByteArray(size: ULong): ByteArray {
    if (size == 0UL) return ByteArray(0)
    val pointer = checkNotNull(this)
    require(size <= Int.MAX_VALUE.toULong())
    return ByteArray(size.toInt()) { index -> pointer[index].toByte() }
}

private fun ByteArray.copyToNativeBuffer(): CPointer<UByteVar>? {
    if (isEmpty()) return null
    val pointer = malloc(size.convert())?.reinterpret<UByteVar>() ?: return null
    usePinned { pinned ->
        memcpy(pointer, pinned.addressOf(0), size.convert())
    }
    return pointer
}

private fun nativeHttpDispose(userData: COpaquePointer?) {
    if (userData != null) {
        val stable = userData.asStableRef<NativeHttpBridge>()
        stable.get().close()
        stable.dispose()
    }
}
