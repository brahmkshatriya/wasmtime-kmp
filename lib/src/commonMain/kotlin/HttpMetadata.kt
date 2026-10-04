package dev.brahmkshatriya.wasmtime

internal const val MAX_HTTP_METADATA_BYTES: Int = 64 * 1024

internal data class EncodedHttpResponse(
    val metadata: ByteArray,
    val body: ByteArray,
)

internal fun encodeBoundedHttpResponse(
    response: WasmtimeHttpResponse,
    maxBodyBytes: Int,
): EncodedHttpResponse {
    require(maxBodyBytes == 0 || response.body.size <= maxBodyBytes) {
        "HTTP response is too large: ${response.body.size} > $maxBodyBytes bytes"
    }
    val metadata = encodeHttpResponseMetadata(response)
    require(metadata.size <= MAX_HTTP_METADATA_BYTES) {
        "HTTP response metadata is too large: ${metadata.size} > $MAX_HTTP_METADATA_BYTES bytes"
    }
    return EncodedHttpResponse(metadata, response.body)
}

internal fun encodeHttpRequestMetadata(request: WasmtimeHttpRequest): ByteArray =
    encodeHttpFields(
        buildList {
            add(request.method)
            add(request.url)
            request.headers.forEach { header ->
                add(header.name)
                add(header.value)
            }
        },
    )

internal fun decodeHttpRequestMetadata(
    metadata: ByteArray,
    body: ByteArray,
): WasmtimeHttpRequest {
    val fields = decodeHttpFields(metadata)
    require(fields.size >= 2 && (fields.size - 2) % 2 == 0) {
        "invalid HTTP request metadata"
    }
    return WasmtimeHttpRequest(
        method = fields[0],
        url = fields[1],
        headers = fields.drop(2).chunked(2).map { (name, value) ->
            WasmtimeHttpHeader(name, value)
        },
        body = body,
    )
}

internal fun encodeHttpResponseMetadata(response: WasmtimeHttpResponse): ByteArray =
    encodeHttpFields(
        buildList {
            add(response.statusCode.toString())
            response.headers.forEach { header ->
                add(header.name)
                add(header.value)
            }
        },
    )

internal fun decodeHttpResponseMetadata(
    metadata: ByteArray,
    body: ByteArray,
): WasmtimeHttpResponse {
    val fields = decodeHttpFields(metadata)
    require(fields.isNotEmpty() && (fields.size - 1) % 2 == 0) {
        "invalid HTTP response metadata"
    }
    return WasmtimeHttpResponse(
        statusCode = fields[0].toInt(),
        headers = fields.drop(1).chunked(2).map { (name, value) ->
            WasmtimeHttpHeader(name, value)
        },
        body = body,
    )
}

private fun encodeHttpFields(fields: List<String>): ByteArray {
    val encoded = fields.map { field ->
        require('\u0000' !in field) { "HTTP metadata must not contain NUL" }
        field.encodeToByteArray()
    }
    val size = encoded.sumOf { it.size + 1 }
    return ByteArray(size).also { output ->
        var offset = 0
        encoded.forEach { field ->
            field.copyInto(output, offset)
            offset += field.size
            output[offset++] = 0
        }
    }
}

private fun decodeHttpFields(metadata: ByteArray): List<String> {
    val result = mutableListOf<String>()
    var start = 0
    metadata.forEachIndexed { index, value ->
        if (value.toInt() == 0) {
            result += metadata.copyOfRange(start, index).decodeToString()
            start = index + 1
        }
    }
    require(start == metadata.size) { "unterminated HTTP metadata" }
    return result
}
