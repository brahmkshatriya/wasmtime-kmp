package dev.brahmkshatriya.wasmtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/**
 * Transport used by generated host-side contract proxies.
 *
 * Most applications create one with [createWasmtimeExtensionTransport] and pass it to the generated
 * `<Contract>WasmtimeProxy`. The generated proxy owns method IDs and serialization; application code only
 * supplies Wasm bytes, runtime modules, limits, and explicit host capabilities.
 *
 * [invoke] is suspend because a guest contract method may suspend on guest timers or asynchronous host calls.
 */
public interface WasmtimeExtensionTransport {
    /** Invokes one generated contract method with its serialized argument payload. */
    public suspend fun invoke(methodId: Int, arguments: ByteArray = ByteArray(0)): ByteArray

    /** Releases any reusable Wasm instance owned by this transport. */
    public fun close() {}
}

/** Structured exception reconstructed from a failure thrown inside the guest extension. */
public class WasmtimeExtensionException(
    public val remoteType: String,
    message: String,
    public val guestStackTrace: String? = null,
) : RuntimeException(message)

/**
 * Creates a transport for a generated Wasmtime contract proxy.
 *
 * This is the recommended high-level host API for third-party extensions. The SDK owns the fixed wire ABI,
 * method IDs, instance lifecycle, cancellation, and browser worker isolation. Callers provide only the root
 * extension bytes, generated shared [runtime], [limits], and explicitly granted capabilities.
 *
 * ```kotlin
 * val transport = createWasmtimeExtensionTransport(
 *     wasm = extensionBytes,
 *     runtime = runtime,
 *     httpHandler = httpCapability,
 *     storage = WasmtimeStorage(pluginDataDir),
 * )
 * val plugin = PluginWasmtimeProxy(transport)
 * val value = plugin.load()
 * ```
 *
 * On browser/WasmJS this path executes the untrusted extension in a dedicated worker so
 * [WasmtimeLimits.maxExecutionMillis] can terminate runaway synchronous Wasm.
 *
 * @param maxResultBytes maximum serialized contract result/error size, or `0` to accept only empty results.
 * @throws IllegalArgumentException when input modules or [maxResultBytes] violate the configured limits.
 */
public fun createWasmtimeExtensionTransport(
    wasm: ByteArray,
    limits: WasmtimeLimits = WasmtimeLimits(),
    services: WasmtimeHostServices = WasmtimeHostServices(),
    runtime: WasmtimeRuntime? = null,
    manifest: WasmtimeExtensionManifest? = null,
    maxArgumentBytes: Int = limits.maxHostCallBytes,
    maxResultBytes: Int = 16 * 1024 * 1024,
): WasmtimeExtensionTransport {
    require(wasm.isNotEmpty()) { "wasm must not be empty" }
    require(limits.maxModuleBytes == 0 || wasm.size <= limits.maxModuleBytes) {
        "wasm module is too large: ${wasm.size} > ${limits.maxModuleBytes} bytes"
    }
    validateRuntimeModuleSizes(limits, runtime)
    require(maxArgumentBytes >= 0) { "maxArgumentBytes must be >= 0" }
    require(maxResultBytes >= 0) { "maxResultBytes must be >= 0" }
    createPlatformWasmtimeExtensionTransport(
        wasm = wasm,
        limits = limits,
        httpHandler = services.effectiveHttp(manifest?.id),
        storage = services.effectiveStorage(),
        runtime = runtime,
        maxArgumentBytes = maxArgumentBytes,
        maxResultBytes = maxResultBytes,
    )?.let { return it }

    val instance = Wasmtime.load(
        wasm = wasm,
        limits = limits,
        httpHandler = services.effectiveHttp(manifest?.id),
        storage = services.effectiveStorage(),
        runtime = runtime,
    )
    val lock = kotlinx.coroutines.sync.Mutex()
    return object : WasmtimeExtensionTransport {
        override suspend fun invoke(methodId: Int, arguments: ByteArray): ByteArray =
            lock.withLock {
                services.logger?.log(
                    WasmtimeExtensionLogEvent(
                        manifest?.id,
                        WasmtimeExtensionLogEvent.Level.Debug,
                        "Calling extension method $methodId (${arguments.size} argument bytes)",
                    )
                )
                try {
                    withContext(Dispatchers.Default) {
                        if (limits.maxExecutionMillis > 0) {
                            withTimeout(limits.maxExecutionMillis) {
                                instance.callExtension(methodId, arguments, maxArgumentBytes, maxResultBytes)
                            }
                        } else {
                            instance.callExtension(methodId, arguments, maxArgumentBytes, maxResultBytes)
                        }
                    }.also { result ->
                        services.logger?.log(
                            WasmtimeExtensionLogEvent(
                                manifest?.id,
                                WasmtimeExtensionLogEvent.Level.Debug,
                                "Extension method $methodId completed (${result.size} result bytes)",
                            )
                        )
                    }
                } catch (failure: Throwable) {
                    services.logger?.log(
                        WasmtimeExtensionLogEvent(
                            manifest?.id,
                            WasmtimeExtensionLogEvent.Level.Error,
                            "Extension method $methodId failed",
                            failure,
                        )
                    )
                    throw failure
                }
            }

        override fun close() {
            instance.close()
        }
    }
}

internal expect fun createPlatformWasmtimeExtensionTransport(
    wasm: ByteArray,
    limits: WasmtimeLimits,
    httpHandler: WasmtimeHttpHandler?,
    storage: WasmtimeStorage?,
    runtime: WasmtimeRuntime?,
    maxArgumentBytes: Int,
    maxResultBytes: Int,
): WasmtimeExtensionTransport?

/**
 * Invokes one generated extension method through wasmtime-kmp's fixed async ABI.
 *
 * Generated proxies call this indirectly through [WasmtimeExtensionTransport]; normal application code should
 * not need method IDs. Cancellation of the calling coroutine is propagated to the guest operation.
 *
 * @param methodId deterministic method ID generated from the configured contract.
 * @param maxResultBytes maximum returned result or error payload size.
 * @throws IllegalArgumentException if the guest reports an invalid/oversized payload.
 * @throws CancellationException if the guest operation is cancelled.
 */
public suspend fun WasmtimeInstance.callExtension(
    methodId: Int,
    arguments: ByteArray = ByteArray(0),
    maxArgumentBytes: Int = WasmtimeLimits().maxHostCallBytes,
    maxResultBytes: Int = 16 * 1024 * 1024,
): ByteArray {
    require(maxArgumentBytes >= 0) { "maxArgumentBytes must be >= 0" }
    require(maxArgumentBytes == 0 || arguments.size <= maxArgumentBytes) {
        "extension argument payload is too large: ${arguments.size} > $maxArgumentBytes bytes"
    }
    require(maxResultBytes >= 0) { "maxResultBytes must be >= 0" }

    val prepareArguments = functionI32(ExtensionProtocol.ARGUMENT_PREPARE)
    val setArgumentByte = functionI32(ExtensionProtocol.ARGUMENT_BYTE)
    val start = functionI32(ExtensionProtocol.START)
    val poll = functionI32(ExtensionProtocol.POLL)
    val nextWakeMillis = functionI32(ExtensionProtocol.NEXT_WAKE_MILLIS)
    val cancel = functionI32(ExtensionProtocol.CANCEL)
    val resultLength = functionI32(ExtensionProtocol.RESULT_LENGTH)
    val resultByte = functionI32(ExtensionProtocol.RESULT_BYTE)
    val errorLength = functionI32(ExtensionProtocol.ERROR_LENGTH)
    val errorByte = functionI32(ExtensionProtocol.ERROR_BYTE)

    check(prepareArguments(arguments.size, 0) == 0) { "extension rejected argument payload" }
    arguments.forEachIndexed { index, byte ->
        check(setArgumentByte(index, byte.toInt() and 0xff) == 0) {
            "extension rejected argument byte $index"
        }
    }

    var state = start.invokeAsync(methodId, 0)
    var elapsedFromLastPoll = TimeSource.Monotonic.markNow()

    try {
        while (state == ExtensionProtocol.PENDING) {
            when (val wakeMillis = nextWakeMillis(0, 0)) {
                0 -> yield()
                in 1..Int.MAX_VALUE -> delay(wakeMillis.toLong())
                else -> delay(1L)
            }

            val elapsedMillis = elapsedFromLastPoll.elapsedNow().inWholeMilliseconds
                .coerceIn(0L, Int.MAX_VALUE.toLong())
                .toInt()
            state = poll.invokeAsync(elapsedMillis, 0)
            elapsedFromLastPoll = TimeSource.Monotonic.markNow()
        }

        return when (state) {
            ExtensionProtocol.SUCCESS -> {
                val length = resultLength(0, 0)
                require(length in 0..maxResultBytes) {
                    "invalid extension result length: $length"
                }
                ByteArray(length) { index -> resultByte(index, 0).toByte() }
            }

            ExtensionProtocol.FAILURE -> {
                val bytes = readExtensionBytes(errorLength, errorByte, maxResultBytes)
                throw decodeExtensionFailure(bytes)
            }

            ExtensionProtocol.CANCELLED -> {
                val bytes = readExtensionBytes(errorLength, errorByte, maxResultBytes)
                val failure = decodeExtensionFailure(bytes)
                throw CancellationException("extension call cancelled: ${failure.message}")
            }

            else -> error("invalid extension call state: $state")
        }
    } finally {
        if (state == ExtensionProtocol.PENDING) cancel(0, 0)
    }
}

private fun readExtensionBytes(
    lengthFunction: WasmtimeI32Function,
    byteFunction: WasmtimeI32Function,
    maxBytes: Int,
): ByteArray {
    val length = lengthFunction(0, 0)
    if (length <= 0) return ByteArray(0)
    require(length <= maxBytes) { "invalid extension error length: $length" }
    return ByteArray(length) { index -> byteFunction(index, 0).toByte() }
}

private fun decodeExtensionFailure(bytes: ByteArray): WasmtimeExtensionException {
    if (bytes.size < 4 || bytes[0] != 'W'.code.toByte() || bytes[1] != 'E'.code.toByte() || bytes[2] != 'X'.code.toByte()) {
        return WasmtimeExtensionException("Throwable", bytes.decodeToString().ifBlank { "unknown extension error" })
    }
    var offset = 4
    fun field(): String {
        if (offset + 4 > bytes.size) return ""
        val length = (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
        offset += 4
        if (length < 0 || offset + length > bytes.size) return ""
        return bytes.copyOfRange(offset, offset + length).decodeToString().also { offset += length }
    }
    val type = field().ifBlank { "Throwable" }
    val message = field().ifBlank { "extension call failed" }
    val stack = field().takeIf(String::isNotBlank)
    return WasmtimeExtensionException(type, message, stack)
}

private object ExtensionProtocol {
    const val ARGUMENT_PREPARE = "__wasmtime_extension_argument_prepare"
    const val ARGUMENT_BYTE = "__wasmtime_extension_argument_byte"
    const val START = "__wasmtime_extension_start"
    const val POLL = "__wasmtime_extension_poll"
    const val NEXT_WAKE_MILLIS = "__wasmtime_extension_next_wake_millis"
    const val CANCEL = "__wasmtime_extension_cancel"
    const val RESULT_LENGTH = "__wasmtime_extension_result_length"
    const val RESULT_BYTE = "__wasmtime_extension_result_byte"
    const val ERROR_LENGTH = "__wasmtime_extension_error_length"
    const val ERROR_BYTE = "__wasmtime_extension_error_byte"

    const val PENDING = 0
    const val SUCCESS = 1
    const val FAILURE = 2
    const val CANCELLED = 3
}
