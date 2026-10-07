package dev.brahmkshatriya.wasmtime.internal

import dev.brahmkshatriya.wasmtime.WasmtimeHttpHandler
import dev.brahmkshatriya.wasmtime.WasmValue
import dev.brahmkshatriya.wasmtime.WasmValueType
import dev.brahmkshatriya.wasmtime.WasmtimeCallerMemory
import dev.brahmkshatriya.wasmtime.WasmtimeHostCall
import dev.brahmkshatriya.wasmtime.WasmtimeImports
import dev.brahmkshatriya.wasmtime.validateWasmValues
import dev.brahmkshatriya.wasmtime.decodeHttpRequestMetadata
import dev.brahmkshatriya.wasmtime.encodeBoundedHttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

internal object NativeWasmtime {
    const val ASYNC_PENDING: Long = Long.MIN_VALUE

    init {
        PlatformNativeLoader.load()
        nativeSetDefaultCacheDirectory(PlatformNativeLoader.cacheDirectory())
    }

    external fun nativeSetDefaultCacheDirectory(directory: String?)
    external fun nativeCompile(wasm: ByteArray): Long
    external fun nativeInstantiate(
        moduleHandle: Long,
        maxMemoryBytes: Long,
        fuel: Long,
        maxExecutionMillis: Long,
        maxTableElements: Long,
        maxHostCallBytes: Int,
        maxOutputBytes: Long,
        maxHttpResponseBytes: Int,
        maxWasiPollMillis: Long,
        httpHandler: JniHttpHandlerBridge?,
        storageBackingPath: String?,
        storageGuestPath: String?,
        storageReadOnly: Boolean,
        storageMaxBytes: Long,
        storageMaxEntries: Int,
        storageMaxFileBytes: Long,
    ): Long
    external fun nativeInstantiateWithRuntime(
        moduleHandle: Long,
        runtimeModuleNames: Array<String>,
        runtimeModules: Array<ByteArray>,
        maxMemoryBytes: Long,
        fuel: Long,
        maxExecutionMillis: Long,
        maxTableElements: Long,
        maxHostCallBytes: Int,
        maxOutputBytes: Long,
        maxHttpResponseBytes: Int,
        maxWasiPollMillis: Long,
        httpHandler: JniHttpHandlerBridge?,
        storageBackingPath: String?,
        storageGuestPath: String?,
        storageReadOnly: Boolean,
        storageMaxBytes: Long,
        storageMaxEntries: Int,
        storageMaxFileBytes: Long,
    ): Long
    external fun nativeModuleClose(moduleHandle: Long)
    external fun nativeLoad(
        wasm: ByteArray,
        maxMemoryBytes: Long,
        fuel: Long,
        maxExecutionMillis: Long,
        maxTableElements: Long,
        maxHostCallBytes: Int,
        maxOutputBytes: Long,
        maxHttpResponseBytes: Int,
        maxWasiPollMillis: Long,
        httpHandler: JniHttpHandlerBridge?,
        storageBackingPath: String?,
        storageGuestPath: String?,
        storageReadOnly: Boolean,
        storageMaxBytes: Long,
        storageMaxEntries: Int,
        storageMaxFileBytes: Long,
    ): Long
    external fun nativeLoadWithRuntime(
        wasm: ByteArray,
        runtimeModuleNames: Array<String>,
        runtimeModules: Array<ByteArray>,
        maxMemoryBytes: Long,
        fuel: Long,
        maxExecutionMillis: Long,
        maxTableElements: Long,
        maxHostCallBytes: Int,
        maxOutputBytes: Long,
        maxHttpResponseBytes: Int,
        maxWasiPollMillis: Long,
        httpHandler: JniHttpHandlerBridge?,
        storageBackingPath: String?,
        storageGuestPath: String?,
        storageReadOnly: Boolean,
        storageMaxBytes: Long,
        storageMaxEntries: Int,
        storageMaxFileBytes: Long,
    ): Long
    external fun nativeInstantiateWithRuntimeAndImports(
        moduleHandle: Long,
        runtimeModuleNames: Array<String>,
        runtimeModules: Array<ByteArray>,
        importModules: Array<String>,
        importNames: Array<String>,
        importParameterKinds: Array<IntArray>,
        importResultKinds: Array<IntArray>,
        importsBridge: JniHostImportsBridge?,
        maxMemoryBytes: Long,
        fuel: Long,
        maxExecutionMillis: Long,
        maxTableElements: Long,
        maxHostCallBytes: Int,
        maxOutputBytes: Long,
        maxHttpResponseBytes: Int,
        maxWasiPollMillis: Long,
        httpHandler: JniHttpHandlerBridge?,
        storageBackingPath: String?,
        storageGuestPath: String?,
        storageReadOnly: Boolean,
        storageMaxBytes: Long,
        storageMaxEntries: Int,
        storageMaxFileBytes: Long,
    ): Long
    external fun nativeLoadWithRuntimeAndImports(
        wasm: ByteArray,
        runtimeModuleNames: Array<String>,
        runtimeModules: Array<ByteArray>,
        importModules: Array<String>,
        importNames: Array<String>,
        importParameterKinds: Array<IntArray>,
        importResultKinds: Array<IntArray>,
        importsBridge: JniHostImportsBridge?,
        maxMemoryBytes: Long,
        fuel: Long,
        maxExecutionMillis: Long,
        maxTableElements: Long,
        maxHostCallBytes: Int,
        maxOutputBytes: Long,
        maxHttpResponseBytes: Int,
        maxWasiPollMillis: Long,
        httpHandler: JniHttpHandlerBridge?,
        storageBackingPath: String?,
        storageGuestPath: String?,
        storageReadOnly: Boolean,
        storageMaxBytes: Long,
        storageMaxEntries: Int,
        storageMaxFileBytes: Long,
    ): Long
    external fun nativeCallI32(handle: Long, exportName: String, first: Int, second: Int): Int
    external fun nativeResolveI32(handle: Long, exportName: String): Long
    external fun nativeCallResolvedI32(functionHandle: Long, first: Int, second: Int): Int
    external fun nativeCallResolvedI32AsyncStart(functionHandle: Long, first: Int, second: Int): Long
    external fun nativeCallResolvedI32AsyncPoll(futureHandle: Long): Long
    external fun nativeCallResolvedI32AsyncClose(futureHandle: Long)
    external fun nativeFunctionClose(functionHandle: Long)
    external fun nativeResolveFunction(
        handle: Long,
        exportName: String,
        parameterKinds: IntArray,
        resultKinds: IntArray,
    ): Long
    external fun nativeCallResolvedFunction(functionHandle: Long, argumentBits: LongArray): LongArray
    external fun nativeGenericFunctionClose(functionHandle: Long)
    external fun nativeMemorySize(handle: Long, exportName: String): Int
    external fun nativeMemoryRead(handle: Long, exportName: String, offset: Int, length: Int): ByteArray
    external fun nativeMemoryWrite(handle: Long, exportName: String, offset: Int, bytes: ByteArray)
    external fun nativeCallerMemorySize(callerHandle: Long, exportName: String): Int
    external fun nativeCallerMemoryRead(callerHandle: Long, exportName: String, offset: Int, length: Int): ByteArray
    external fun nativeCallerMemoryWrite(callerHandle: Long, exportName: String, offset: Int, bytes: ByteArray)
    external fun nativeClose(handle: Long)
}

internal class JniHostImportsBridge(
    private val imports: WasmtimeImports,
) {
    @Volatile
    private var error: String? = null

    @Suppress("unused") // Called from JNI.
    fun invoke(functionIndex: Int, callerHandle: Long, argumentBits: LongArray): LongArray? = try {
        val function = imports.functions[functionIndex]
        require(argumentBits.size == function.type.parameters.size)
        val arguments = function.type.parameters.mapIndexed { index, type ->
            argumentBits[index].toWasmValue(type)
        }
        val memory = WasmtimeCallerMemory(
            readBlock = { exportName, offset, length ->
                NativeWasmtime.nativeCallerMemoryRead(callerHandle, exportName, offset, length)
            },
            writeBlock = { exportName, offset, bytes ->
                NativeWasmtime.nativeCallerMemoryWrite(callerHandle, exportName, offset, bytes)
            },
            sizeBlock = { exportName ->
                NativeWasmtime.nativeCallerMemorySize(callerHandle, exportName)
            },
        )
        val results = function.callback.invoke(WasmtimeHostCall(arguments, memory))
        validateWasmValues(results, function.type.results, "host import result")
        results.map(WasmValue::toBits).toLongArray()
    } catch (throwable: Throwable) {
        error = throwable.message ?: throwable.toString()
        null
    }

    @Suppress("unused") // Called from JNI.
    fun consumeError(): String? = error.also { error = null }
}

internal fun WasmValue.toBits(): Long = when (this) {
    is WasmValue.I32 -> value.toUInt().toLong()
    is WasmValue.I64 -> value
    is WasmValue.F32 -> value.toBits().toUInt().toLong()
    is WasmValue.F64 -> value.toBits()
}

internal fun Long.toWasmValue(type: WasmValueType): WasmValue = when (type) {
    WasmValueType.I32 -> WasmValue.I32(toInt())
    WasmValueType.I64 -> WasmValue.I64(this)
    WasmValueType.F32 -> WasmValue.F32(Float.fromBits(toInt()))
    WasmValueType.F64 -> WasmValue.F64(Double.fromBits(this))
}

internal expect object PlatformNativeLoader {
    fun load()
    fun cacheDirectory(): String?
}

internal class JniHttpHandlerBridge(
    private val handler: WasmtimeHttpHandler,
    private val maxHttpResponseBytes: Int,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentOperation: JniHttpOperation? = null

    @Suppress("unused") // Called from JNI.
    fun start(metadata: ByteArray, body: ByteArray): JniHttpOperation =
        JniHttpOperation(
            scope = scope,
            handler = handler,
            metadata = metadata,
            body = body,
            maxHttpResponseBytes = maxHttpResponseBytes,
        ).also { currentOperation = it }

    suspend fun awaitProgress() {
        val operation = currentOperation
        if (operation == null) {
            yield()
            return
        }
        operation.awaitCompletion()
        if (currentOperation === operation) currentOperation = null
    }

    @Suppress("unused") // Called from JNI.
    fun close() {
        currentOperation?.cancel()
        currentOperation = null
        scope.cancel()
    }
}

internal class JniHttpOperation(
    scope: CoroutineScope,
    handler: WasmtimeHttpHandler,
    metadata: ByteArray,
    body: ByteArray,
    maxHttpResponseBytes: Int,
) {
    @Volatile
    private var completed: Boolean = false
    private val completion = CompletableDeferred<Unit>()

    @get:Suppress("unused") // Called from JNI.
    @Volatile
    var response: JniHttpResponse? = null
        private set

    private val job: Job = scope.launch {
        try {
            val value = encodeBoundedHttpResponse(
                handler.execute(decodeHttpRequestMetadata(metadata, body)),
                maxHttpResponseBytes,
            )
            response = JniHttpResponse(
                metadata = value.metadata,
                body = value.body,
            )
        } catch (_: CancellationException) {
            // A cancelled Wasmtime call cancels this operation through JNI.
        } catch (_: Throwable) {
            // Null response is the failure signal read by the native continuation.
        } finally {
            completed = true
            completion.complete(Unit)
        }
    }

    suspend fun awaitCompletion() = completion.await()

    @Suppress("unused") // Called from JNI.
    fun isCompleted(): Boolean = completed

    @Suppress("unused") // Called from JNI.
    fun cancel() {
        job.cancel()
    }
}

internal class JniHttpResponse(
    @get:Suppress("unused")
    val metadata: ByteArray,
    @get:Suppress("unused")
    val body: ByteArray,
)
