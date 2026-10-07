@file:OptIn(
    kotlin.wasm.ExperimentalWasmInterop::class,
    kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class,
)

package dev.brahmkshatriya.wasmtime.extension

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.withScopedMemoryAllocator

/** Optional lifecycle implemented by extension entry objects. */
public interface WasmtimeExtensionLifecycle {
    public suspend fun onLoad(): Unit = Unit
    public suspend fun onUnload(): Unit = Unit
}

/** Standard guest-visible storage namespace. Hosts may mount any or all of these directories. */
public object ExtensionStorage {
    public const val ROOT: String = "/extension"
    public const val PERSISTENT: String = "/extension/persistent"
    public const val CACHE: String = "/extension/cache"
    public const val TEMPORARY: String = "/extension/temporary"

    /** Creates the standard namespaced directories in the host-provided extension mount. */
    public fun prepare() {
        SystemFileSystem.createDirectories(Path(PERSISTENT), mustCreate = false)
        SystemFileSystem.createDirectories(Path(CACHE), mustCreate = false)
        SystemFileSystem.createDirectories(Path(TEMPORARY), mustCreate = false)
    }

    /** Resolves a relative persistent-data path and rejects attempts to escape the namespace. */
    public fun persistentPath(relative: String): Path = scopedPath(PERSISTENT, relative)

    /** Resolves a relative cache path and rejects attempts to escape the namespace. */
    public fun cachePath(relative: String): Path = scopedPath(CACHE, relative)

    /** Resolves a relative temporary-data path and rejects attempts to escape the namespace. */
    public fun temporaryPath(relative: String): Path = scopedPath(TEMPORARY, relative)

    private fun scopedPath(root: String, relative: String): Path {
        require(relative.isNotBlank()) { "storage path must not be blank" }
        require(!relative.startsWith('/')) { "storage path must be relative" }
        require(relative.split('/').none { it == ".." }) { "storage path must not escape its namespace" }
        return Path("$root/$relative")
    }
}

/** Guest logger routed to the host's [WasmtimeExtensionLogger] capability. */
public object ExtensionLog {
    public suspend fun debug(message: String): Unit = write("Debug", message)
    public suspend fun info(message: String): Unit = write("Info", message)
    public suspend fun warn(message: String): Unit = write("Warning", message)
    public suspend fun error(message: String): Unit = write("Error", message)

    private suspend fun write(level: String, message: String) {
        val response = ExtensionHttp.request(
            ExtensionHttpRequest(
                method = LOG_WRITE,
                url = LOG_SERVICE_URL,
                headers = listOf(ExtensionHttpHeader(LOG_LEVEL_HEADER, level)),
                body = message.encodeToByteArray(),
            )
        )
        check(response.statusCode == 204) {
            "Host log write failed with HTTP ${response.statusCode}"
        }
    }
}

public data class ExtensionHttpHeader(
    public val name: String,
    public val value: String,
)

public data class ExtensionHttpRequest(
    public val method: String,
    public val url: String,
    public val headers: List<ExtensionHttpHeader> = emptyList(),
    public val body: ByteArray = ByteArray(0),
) {
    /** Requests a host-owned credential by logical name without receiving the secret itself. */
    public fun withCredential(name: String): ExtensionHttpRequest = copy(
        headers = headers + ExtensionHttpHeader(CREDENTIAL_HEADER, name),
    )

    public companion object {
        public const val CREDENTIAL_HEADER: String = "X-Wasmtime-Credential"
    }
}

public data class ExtensionHttpResponse(
    public val statusCode: Int,
    public val headers: List<ExtensionHttpHeader>,
    public val body: ByteArray,
) {
    public fun bodyAsText(): String = body.decodeToString()
}

/** First-class HTTP client backed by the host's `ktor_wasi` capability imports. */
public object ExtensionHttp {
    public suspend fun get(
        url: String,
        headers: List<ExtensionHttpHeader> = emptyList(),
        credential: String? = null,
    ): ExtensionHttpResponse = request(
        ExtensionHttpRequest("GET", url, headers).let { request ->
            if (credential == null) request else request.withCredential(credential)
        }
    )

    public suspend fun request(request: ExtensionHttpRequest): ExtensionHttpResponse {
        require(request.method.isNotBlank()) { "HTTP method must not be blank" }
        require(request.url.isNotBlank()) { "HTTP URL must not be blank" }
        val metadata = encodeMetadata(
            buildList {
                add(request.method)
                add(request.url)
                request.headers.forEach { header ->
                    add(header.name)
                    add(header.value)
                }
            }
        )
        val handle = hostRequestCreate(metadata.size, request.body.size)
        check(handle >= 0) { "Host rejected HTTP request creation" }
        try {
            copyBytesToHost(metadata) { pointer, length ->
                hostRequestMetadataCopy(handle, pointer, length)
            }
            copyBytesToHost(request.body) { pointer, length ->
                hostRequestBodyCopy(handle, pointer, length)
            }
            check(hostRequestExecute(handle) == 0) { "Host HTTP request failed" }

            val metadataLength = hostResponseMetadataLength(handle)
            require(metadataLength >= 1) { "Host returned invalid HTTP response metadata length" }
            val responseMetadata = copyBytesFromHost(metadataLength) { pointer, length ->
                hostResponseMetadataCopy(handle, pointer, length)
            }
            val fields = decodeMetadata(responseMetadata)
            require(fields.isNotEmpty() && (fields.size - 1) % 2 == 0) {
                "Host returned malformed HTTP response metadata"
            }
            val status = fields.first().toInt()
            require(status in 100..599) { "Host returned invalid HTTP status $status" }
            val responseHeaders = fields.drop(1).chunked(2).map { (name, value) ->
                ExtensionHttpHeader(name, value)
            }

            val bodyLength = hostResponseBodyLength(handle)
            require(bodyLength >= 0) { "Host returned invalid HTTP response body length" }
            val body = copyBytesFromHost(bodyLength) { pointer, length ->
                hostResponseBodyCopy(handle, pointer, length)
            }
            return ExtensionHttpResponse(status, responseHeaders, body)
        } finally {
            hostRequestClose(handle)
        }
    }
}

/** Pull-based stream opened from a host-provided named resource. */
public class ExtensionResourceStream internal constructor(
    private val handle: Int,
) {
    private var closed: Boolean = false

    /** Returns the next chunk, or `null` at end-of-stream. */
    public suspend fun read(maxBytes: Int = 64 * 1024): ByteArray? {
        check(!closed) { "resource stream is closed" }
        require(maxBytes > 0) { "maxBytes must be positive" }
        val response = ExtensionHttp.request(
            ExtensionHttpRequest(
                method = RESOURCE_READ,
                url = RESOURCE_SERVICE_URL,
                headers = listOf(
                    ExtensionHttpHeader(RESOURCE_HANDLE_HEADER, handle.toString()),
                    ExtensionHttpHeader(RESOURCE_MAX_BYTES_HEADER, maxBytes.toString()),
                ),
            )
        )
        return when (response.statusCode) {
            204 -> null
            206 -> response.body
            else -> error("Host resource read failed with HTTP ${response.statusCode}")
        }
    }

    /** Releases the host resource handle. Safe to call more than once. */
    public suspend fun close() {
        if (closed) return
        closed = true
        val response = ExtensionHttp.request(
            ExtensionHttpRequest(
                method = RESOURCE_CLOSE,
                url = RESOURCE_SERVICE_URL,
                headers = listOf(ExtensionHttpHeader(RESOURCE_HANDLE_HEADER, handle.toString())),
            )
        )
        check(response.statusCode == 204 || response.statusCode == 404) {
            "Host resource close failed with HTTP ${response.statusCode}"
        }
    }
}

/** First-class access to host-provided named byte streams. */
public object ExtensionResources {
    public suspend fun open(name: String): ExtensionResourceStream {
        require(name.isNotBlank()) { "resource name must not be blank" }
        val response = ExtensionHttp.request(
            ExtensionHttpRequest(
                method = RESOURCE_OPEN,
                url = RESOURCE_SERVICE_URL,
                headers = listOf(ExtensionHttpHeader(RESOURCE_NAME_HEADER, name)),
            )
        )
        check(response.statusCode == 200) {
            "Host resource open failed with HTTP ${response.statusCode}"
        }
        val handle = response.bodyAsText().toIntOrNull()
            ?: error("Host returned an invalid resource handle")
        return ExtensionResourceStream(handle)
    }
}

private const val RESOURCE_SERVICE_URL: String = "wasmtime://resource"
private const val RESOURCE_OPEN: String = "RESOURCE_OPEN"
private const val RESOURCE_READ: String = "RESOURCE_READ"
private const val RESOURCE_CLOSE: String = "RESOURCE_CLOSE"
private const val RESOURCE_NAME_HEADER: String = "X-Wasmtime-Resource-Name"
private const val RESOURCE_HANDLE_HEADER: String = "X-Wasmtime-Resource-Handle"
private const val RESOURCE_MAX_BYTES_HEADER: String = "X-Wasmtime-Resource-Max-Bytes"
private const val LOG_SERVICE_URL: String = "wasmtime://log"
private const val LOG_WRITE: String = "LOG"
private const val LOG_LEVEL_HEADER: String = "X-Wasmtime-Log-Level"

private fun encodeMetadata(fields: List<String>): ByteArray {
    val encoded = fields.map { field ->
        require('\u0000' !in field) { "HTTP metadata must not contain NUL" }
        field.encodeToByteArray()
    }
    return ByteArray(encoded.sumOf { it.size + 1 }).also { output ->
        var offset = 0
        encoded.forEach { field ->
            field.copyInto(output, offset)
            offset += field.size
            output[offset++] = 0
        }
    }
}

private fun decodeMetadata(bytes: ByteArray): List<String> {
    val output = mutableListOf<String>()
    var start = 0
    bytes.forEachIndexed { index, byte ->
        if (byte.toInt() == 0) {
            output += bytes.copyOfRange(start, index).decodeToString()
            start = index + 1
        }
    }
    require(start == bytes.size) { "unterminated HTTP metadata" }
    return output
}

private inline fun copyBytesToHost(bytes: ByteArray, copy: (pointer: Int, length: Int) -> Int) {
    if (bytes.isEmpty()) {
        check(copy(0, 0) == 0) { "Host rejected empty byte buffer" }
        return
    }
    withScopedMemoryAllocator { allocator ->
        val pointer = allocator.allocate(bytes.size)
        bytes.forEachIndexed { index, value -> (pointer + index).storeByte(value) }
        check(copy(pointer.address.toInt(), bytes.size) == 0) { "Host rejected byte buffer" }
    }
}

private inline fun copyBytesFromHost(length: Int, copy: (pointer: Int, length: Int) -> Int): ByteArray {
    if (length == 0) return ByteArray(0)
    return withScopedMemoryAllocator { allocator ->
        val pointer = allocator.allocate(length)
        check(copy(pointer.address.toInt(), length) == 0) { "Host failed to copy byte buffer" }
        ByteArray(length).also { output -> pointer.copyInto(output, length) }
    }
}

private fun Pointer.copyInto(output: ByteArray, length: Int) {
    var offset = 0
    while (offset < length) {
        output[offset] = (this + offset).loadByte()
        offset++
    }
}

@WasmImport("ktor_wasi", "request_create")
private external fun hostRequestCreate(metadataLength: Int, bodyLength: Int): Int

@WasmImport("ktor_wasi", "request_metadata_copy")
private external fun hostRequestMetadataCopy(request: Int, pointer: Int, length: Int): Int

@WasmImport("ktor_wasi", "request_body_copy")
private external fun hostRequestBodyCopy(request: Int, pointer: Int, length: Int): Int

@WasmImport("ktor_wasi", "request_execute")
private external fun hostRequestExecute(request: Int): Int

@WasmImport("ktor_wasi", "response_metadata_length")
private external fun hostResponseMetadataLength(request: Int): Int

@WasmImport("ktor_wasi", "response_metadata_copy")
private external fun hostResponseMetadataCopy(request: Int, pointer: Int, length: Int): Int

@WasmImport("ktor_wasi", "response_body_length")
private external fun hostResponseBodyLength(request: Int): Int

@WasmImport("ktor_wasi", "response_body_copy")
private external fun hostResponseBodyCopy(request: Int, pointer: Int, length: Int): Int

@WasmImport("ktor_wasi", "request_close")
private external fun hostRequestClose(request: Int): Int
