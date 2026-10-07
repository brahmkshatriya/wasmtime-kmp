package dev.brahmkshatriya.wasmtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Reserved extension method used to address guest-owned resources. */
internal const val EXTENSION_RESOURCE_METHOD_ID: Int = Int.MIN_VALUE + 2

/** Standard operations used by the generic callback and pull-stream helpers. */
public object WasmtimeRemoteResourceOperation {
    public const val RELEASE: Int = -1
    public const val CALL: Int = 1
    public const val NEXT: Int = 2
}

/**
 * Opaque reference to behavior/state owned by a persistent Wasm extension instance.
 *
 * The payload is deliberately untyped so API adapters can put CBOR values on top without coupling the core
 * runtime to application models such as feeds or pages.
 */
public class WasmtimeRemoteResource(
    private val transport: WasmtimeExtensionTransport,
    public val handle: Long,
) {
    init {
        require(transport.supportsPersistentResources) {
            "this Wasmtime transport does not preserve guest resources across invocations"
        }
    }

    private var released: Boolean = false

    public suspend fun invoke(operation: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        check(!released) { "remote resource has been released" }
        return transport.invoke(EXTENSION_RESOURCE_METHOD_ID, encodeRequest(operation, payload))
    }

    public fun invokeSync(operation: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        check(!released) { "remote resource has been released" }
        return transport.invokeSync(EXTENSION_RESOURCE_METHOD_ID, encodeRequest(operation, payload))
    }

    /** Releases the guest-side entry. Safe to call repeatedly. */
    public suspend fun release() {
        if (released) return
        transport.invoke(
            EXTENSION_RESOURCE_METHOD_ID,
            encodeRequest(WasmtimeRemoteResourceOperation.RELEASE, ByteArray(0)),
        )
        released = true
    }

    private fun encodeRequest(operation: Int, payload: ByteArray): ByteArray = ByteArray(12 + payload.size).also { out ->
        repeat(8) { index -> out[index] = (handle ushr (index * 8)).toByte() }
        out[8] = operation.toByte()
        out[9] = (operation ushr 8).toByte()
        out[10] = (operation ushr 16).toByte()
        out[11] = (operation ushr 24).toByte()
        payload.copyInto(out, 12)
    }
}

/** Host façade for a guest callback registered with `ExtensionRemoteResources.registerCallback`. */
public class WasmtimeRemoteCallback(
    transport: WasmtimeExtensionTransport,
    handle: Long,
) {
    private val resource = WasmtimeRemoteResource(transport, handle)

    public suspend fun invoke(payload: ByteArray = ByteArray(0)): ByteArray =
        resource.invoke(WasmtimeRemoteResourceOperation.CALL, payload)

    public suspend fun release(): Unit = resource.release()
}

/** Host façade for a guest pull stream registered with `ExtensionRemoteResources.registerStream`. */
public class WasmtimeRemoteStream(
    transport: WasmtimeExtensionTransport,
    handle: Long,
) {
    private val resource = WasmtimeRemoteResource(transport, handle)

    /** Returns the next item, or `null` when the guest reports end-of-stream. */
    public suspend fun next(): ByteArray? {
        val response = resource.invoke(WasmtimeRemoteResourceOperation.NEXT)
        require(response.isNotEmpty()) { "invalid remote stream response" }
        return when (response[0].toInt()) {
            0 -> {
                require(response.size == 1) { "trailing bytes after remote stream end marker" }
                null
            }
            1 -> response.copyOfRange(1, response.size)
            else -> error("invalid remote stream state: ${response[0].toInt()}")
        }
    }

    /**
     * Exposes this pull stream as a cold [Flow]. Cancelling collection releases the guest resource.
     *
     * A stream resource is single-owner: collecting the returned flow consumes and then releases this handle.
     */
    public fun asFlow(): Flow<ByteArray> = flow {
        try {
            while (true) {
                val item = next() ?: break
                emit(item)
            }
        } finally {
            release()
        }
    }

    public suspend fun release(): Unit = resource.release()
}
