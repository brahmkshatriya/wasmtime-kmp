package dev.brahmkshatriya.wasmtime.extension

/** Handler stored in the guest and addressed by an opaque host-visible handle. */
public fun interface WasmtimeGuestResourceHandler {
    public suspend fun invoke(operation: Int, payload: ByteArray): ByteArray
}

private data class GuestResourceEntry(
    val handler: WasmtimeGuestResourceHandler,
    val close: suspend () -> Unit,
)

/**
 * Registry for behavior that cannot be copied across the Wasm boundary.
 *
 * Feed loaders, pagers, callbacks, iterators, and raw byte sources can register a handler and return the
 * resulting [Long] as part of an ordinary CBOR value. Handles live for the lifetime of the Wasm instance or
 * until explicitly released.
 */
public object ExtensionRemoteResources {
    private const val MAX_RESOURCES: Int = 4096
    private val resources = mutableMapOf<Long, GuestResourceEntry>()
    private var nextHandle: Long = 1L

    public fun register(
        handler: WasmtimeGuestResourceHandler,
        close: suspend () -> Unit = {},
    ): Long {
        require(resources.size < MAX_RESOURCES) {
            "guest resource limit exceeded: $MAX_RESOURCES"
        }
        check(nextHandle > 0L) { "guest resource handle space exhausted" }
        val handle = nextHandle++
        resources[handle] = GuestResourceEntry(handler, close)
        return handle
    }

    /** Registers a callback using the standard callback operation. */
    public fun registerCallback(callback: suspend (ByteArray) -> ByteArray): Long =
        register(WasmtimeGuestResourceHandler { operation, payload ->
            require(operation == WasmtimeRemoteResourceOperation.CALL) {
                "unsupported callback resource operation: $operation"
            }
            callback(payload)
        })

    /** Registers a pull stream. A `null` item is encoded as end-of-stream. */
    public fun registerStream(
        next: suspend () -> ByteArray?,
        close: suspend () -> Unit = {},
    ): Long = register(
        handler = WasmtimeGuestResourceHandler { operation, _ ->
            require(operation == WasmtimeRemoteResourceOperation.NEXT) {
                "unsupported stream resource operation: $operation"
            }
            val item = next()
            if (item == null) byteArrayOf(0) else byteArrayOf(1) + item
        },
        close = close,
    )

    /** Runtime entry point used by the generated adapter. Not intended for extension application code. */
    public suspend fun dispatch(bytes: ByteArray): ByteArray {
        require(bytes.size >= 12) { "truncated guest resource request" }
        val handle = bytes.readLongLe(0)
        val operation = bytes.readIntLe(8)
        if (operation == WasmtimeRemoteResourceOperation.RELEASE) {
            resources.remove(handle)?.close?.invoke()
            return ByteArray(0)
        }
        val entry = resources[handle] ?: error("unknown guest resource handle: $handle")
        return entry.handler.invoke(operation, bytes.copyOfRange(12, bytes.size))
    }

    /** Runtime entry point used during generated adapter shutdown. */
    public suspend fun clear() {
        val entries = resources.values.toList()
        resources.clear()
        entries.forEach { it.close() }
    }
}

/** Standard operations understood by the generic guest resource helpers. */
public object WasmtimeRemoteResourceOperation {
    public const val RELEASE: Int = -1
    public const val CALL: Int = 1
    public const val NEXT: Int = 2
}

private fun ByteArray.readIntLe(offset: Int): Int =
    (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8) or
        ((this[offset + 2].toInt() and 0xff) shl 16) or
        ((this[offset + 3].toInt() and 0xff) shl 24)

private fun ByteArray.readLongLe(offset: Int): Long {
    var result = 0L
    repeat(8) { index -> result = result or ((this[offset + index].toLong() and 0xffL) shl (index * 8)) }
    return result
}
