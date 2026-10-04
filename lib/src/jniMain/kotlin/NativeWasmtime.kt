package dev.brahmkshatriya.wasmtime.internal

import dev.brahmkshatriya.wasmtime.WasmtimeHttpHandler
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
    external fun nativeCallI32(handle: Long, exportName: String, first: Int, second: Int): Int
    external fun nativeResolveI32(handle: Long, exportName: String): Long
    external fun nativeCallResolvedI32(functionHandle: Long, first: Int, second: Int): Int
    external fun nativeCallResolvedI32AsyncStart(functionHandle: Long, first: Int, second: Int): Long
    external fun nativeCallResolvedI32AsyncPoll(futureHandle: Long): Long
    external fun nativeCallResolvedI32AsyncClose(futureHandle: Long)
    external fun nativeFunctionClose(functionHandle: Long)
    external fun nativeClose(handle: Long)
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
