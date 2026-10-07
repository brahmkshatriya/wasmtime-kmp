package dev.brahmkshatriya.wasmtime.extension

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Guest façade over one host-owned resource registered in `WasmtimeHostResourceRegistry`. */
public class ExtensionHostResource(
    public val handle: Long,
) {
    private var released: Boolean = false

    public suspend fun invoke(operation: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        check(!released) { "host resource has been released" }
        val response = ExtensionHttp.request(
            ExtensionHttpRequest(
                method = HOST_RESOURCE_INVOKE,
                url = HOST_RESOURCE_SERVICE_URL,
                headers = listOf(
                    ExtensionHttpHeader(HOST_RESOURCE_HANDLE_HEADER, handle.toString()),
                    ExtensionHttpHeader(HOST_RESOURCE_OPERATION_HEADER, operation.toString()),
                ),
                body = payload,
            )
        )
        check(response.statusCode == 200) {
            "Host resource invocation failed with HTTP ${response.statusCode}"
        }
        return response.body
    }

    /** Releases the host-side entry. Safe to call repeatedly. */
    public suspend fun release() {
        if (released) return
        val response = ExtensionHttp.request(
            ExtensionHttpRequest(
                method = HOST_RESOURCE_INVOKE,
                url = HOST_RESOURCE_SERVICE_URL,
                headers = listOf(
                    ExtensionHttpHeader(HOST_RESOURCE_HANDLE_HEADER, handle.toString()),
                    ExtensionHttpHeader(
                        HOST_RESOURCE_OPERATION_HEADER,
                        WasmtimeRemoteResourceOperation.RELEASE.toString(),
                    ),
                ),
            )
        )
        check(response.statusCode == 204 || response.statusCode == 404) {
            "Host resource release failed with HTTP ${response.statusCode}"
        }
        released = true
    }
}

/** Guest façade for a host callback registered with `WasmtimeHostResourceRegistry.registerCallback`. */
public class ExtensionHostCallback(handle: Long) {
    private val resource = ExtensionHostResource(handle)

    public suspend fun invoke(payload: ByteArray = ByteArray(0)): ByteArray =
        resource.invoke(WasmtimeRemoteResourceOperation.CALL, payload)

    public suspend fun release(): Unit = resource.release()
}

/** Pull-stream façade for a host flow/resource registered with the standard NEXT operation. */
public class ExtensionHostStream(handle: Long) {
    private val resource = ExtensionHostResource(handle)

    public suspend fun next(): ByteArray? {
        val response = resource.invoke(WasmtimeRemoteResourceOperation.NEXT)
        require(response.isNotEmpty()) { "invalid host stream response" }
        return when (response[0].toInt()) {
            0 -> {
                require(response.size == 1) { "trailing bytes after host stream end marker" }
                null
            }
            1 -> response.copyOfRange(1, response.size)
            else -> error("invalid host stream state: ${response[0].toInt()}")
        }
    }

    /** Collection cancellation releases the host resource and cancels its producer. */
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

private const val HOST_RESOURCE_SERVICE_URL: String = "wasmtime://host-resource"
private const val HOST_RESOURCE_INVOKE: String = "RESOURCE_INVOKE"
private const val HOST_RESOURCE_HANDLE_HEADER: String = "X-Wasmtime-Host-Resource-Handle"
private const val HOST_RESOURCE_OPERATION_HEADER: String = "X-Wasmtime-Host-Resource-Operation"
