@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class, kotlin.wasm.ExperimentalWasmInterop::class)

package dev.brahmkshatriya.wasmtime.benchmark

import dev.brahmkshatriya.wasmtime.extension.WasmtimeExtensionCall
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.wasm.ExperimentalWasmInterop
import kotlin.wasm.WasmExport
import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.withScopedMemoryAllocator

private const val OP_YIELD = 1
private const val OP_DELAY = 2
private const val OP_NETWORK_SMALL = 3
private const val OP_NETWORK_LARGE = 4
private const val OP_MOCK_SMALL = 5
private const val OP_MOCK_LARGE = 6
private const val OP_RAW_SMALL = 7
private const val OP_RAW_LARGE = 8
private const val OP_STATUS_SMALL = 9
private const val OP_STATUS_LARGE = 10
private const val OP_REPEATED_BODY_READ = 11
private const val OP_POST_FALLBACK = 12
private const val OP_CUSTOM_CLIENT_FALLBACK = 13
private const val OP_REDIRECT = 14
private const val OP_PIPELINE_INTERCEPTOR_FALLBACK = 15
private const val OP_GENERIC_SMALL = 16
private const val OP_GENERIC_LARGE = 17

private const val NETWORK_SMALL_URL = "http://127.0.0.1:18080/bench?size=1024"
private const val NETWORK_LARGE_URL = "http://127.0.0.1:18080/bench?size=65536"
private const val MOCK_SMALL_URL = "http://wasmtime-kmp.local/mock?size=1024"
private const val MOCK_LARGE_URL = "http://wasmtime-kmp.local/mock?size=65536"
private const val MOCK_REDIRECT_URL = "http://wasmtime-kmp.local/redirect-start"

private val httpClient = HttpClient()
private val customHttpClient = HttpClient { install("benchmark-custom") { } }
private var pipelineInterceptorHits = 0
private val pipelineHttpClient = HttpClient().apply {
    requestPipeline.intercept(HttpRequestPipeline.Before) {
        pipelineInterceptorHits++
        proceed()
    }
}

private var asyncKind = 0
private var asyncCount = 0
private var asyncResult = 0

private val asyncCall = WasmtimeExtensionCall { _: Int ->
    asyncResult = when (asyncKind) {
        OP_YIELD -> {
            repeat(asyncCount) { yield() }
            asyncCount
        }
        OP_DELAY -> {
            repeat(asyncCount) { delay(1) }
            asyncCount
        }
        OP_NETWORK_SMALL, OP_NETWORK_LARGE, OP_MOCK_SMALL, OP_MOCK_LARGE -> {
            val url = when (asyncKind) {
                OP_NETWORK_SMALL -> NETWORK_SMALL_URL
                OP_NETWORK_LARGE -> NETWORK_LARGE_URL
                OP_MOCK_SMALL -> MOCK_SMALL_URL
                else -> MOCK_LARGE_URL
            }
            var total = 0
            repeat(asyncCount) {
                total += httpClient.get(url).bodyAsBytes().size
            }
            total
        }
        OP_RAW_SMALL -> repeatRawMock(asyncCount, 1024)
        OP_RAW_LARGE -> repeatRawMock(asyncCount, 64 * 1024)
        OP_STATUS_SMALL, OP_STATUS_LARGE -> {
            val url = if (asyncKind == OP_STATUS_SMALL) MOCK_SMALL_URL else MOCK_LARGE_URL
            var total = 0
            repeat(asyncCount) { total += httpClient.get(url).status.value }
            total
        }
        OP_REPEATED_BODY_READ -> {
            val response = httpClient.get(MOCK_SMALL_URL)
            response.bodyAsBytes().size + response.bodyAsBytes().size
        }
        OP_POST_FALLBACK -> httpClient.post(MOCK_SMALL_URL) {
            setBody(byteArrayOf(1, 2, 3, 4))
        }.bodyAsBytes().size
        OP_CUSTOM_CLIENT_FALLBACK -> customHttpClient.get(MOCK_SMALL_URL).bodyAsBytes().size
        OP_REDIRECT -> httpClient.get(MOCK_REDIRECT_URL).bodyAsBytes().size
        OP_PIPELINE_INTERCEPTOR_FALLBACK -> {
            pipelineInterceptorHits = 0
            val size = pipelineHttpClient.get(MOCK_SMALL_URL).bodyAsBytes().size
            size + pipelineInterceptorHits * 10_000
        }
        OP_GENERIC_SMALL, OP_GENERIC_LARGE -> {
            val url = if (asyncKind == OP_GENERIC_SMALL) MOCK_SMALL_URL else MOCK_LARGE_URL
            var total = 0
            repeat(asyncCount) { total += customHttpClient.get(url).bodyAsBytes().size }
            total
        }
        else -> error("unknown benchmark operation: $asyncKind")
    }
    byteArrayOf(1)
}

@WasmExport("bench_compute")
fun benchCompute(iterations: Int, seed: Int): Int {
    var x = seed
    repeat(iterations) {
        x = x * 1664525 + 1013904223
        x = x xor (x ushr 13)
        x *= -2048144789
        x = x xor (x ushr 16)
    }
    return x
}

@WasmExport("bench_file")
fun benchFile(iterations: Int, byteCount: Int): Int {
    val path = Path("/data/bench.bin")
    val payload = ByteArray(byteCount) { index -> (index * 31 + 7).toByte() }
    var checksum = 0
    repeat(iterations) {
        SystemFileSystem.sink(path).buffered().use { sink -> sink.write(payload) }
        val read = SystemFileSystem.source(path).buffered().use { source -> source.readByteArray() }
        checksum = checksum xor read.size
        if (read.isNotEmpty()) checksum = checksum xor (read[read.lastIndex].toInt() and 0xff)
    }
    return checksum
}

@WasmExport("bench_async_start")
fun benchAsyncStart(kind: Int, count: Int): Int {
    asyncKind = kind
    asyncCount = count
    asyncResult = 0
    return asyncCall.start()
}

@WasmExport("bench_async_poll")
fun benchAsyncPoll(elapsedMillis: Int, unused: Int): Int = asyncCall.poll(elapsedMillis)

@WasmExport("bench_async_next_wake")
fun benchAsyncNextWake(unused1: Int, unused2: Int): Int = asyncCall.nextWakeMillis()

@WasmExport("bench_async_cancel")
fun benchAsyncCancel(unused1: Int, unused2: Int): Int = asyncCall.cancel()

@WasmExport("bench_async_result")
fun benchAsyncResult(unused1: Int, unused2: Int): Int = asyncResult

private fun repeatRawMock(count: Int, size: Int): Int {
    val metadata = "GET\u0000http://wasmtime-kmp.local/mock?size=$size\u0000".encodeToByteArray()
    var total = 0
    repeat(count) {
        val request = rawRequestCreate(metadata.size, 0)
        check(request >= 0)
        try {
            withScopedMemoryAllocator { allocator ->
                val pointer = allocator.allocate(metadata.size)
                metadata.forEachIndexed { index, value -> (pointer + index).storeByte(value) }
                check(rawRequestMetadataCopy(request, pointer.address.toInt(), metadata.size) == 0)
            }
            check(rawRequestBodyCopy(request, 0, 0) == 0)
            check(rawRequestExecute(request) == 0)
            val metadataLength = rawResponseMetadataLength(request)
            check(metadataLength > 0)
            withScopedMemoryAllocator { allocator ->
                val pointer = allocator.allocate(metadataLength)
                check(rawResponseMetadataCopy(request, pointer.address.toInt(), metadataLength) == 0)
            }
            val bodyLength = rawResponseBodyLength(request)
            check(bodyLength == size)
            withScopedMemoryAllocator { allocator ->
                val pointer = allocator.allocate(bodyLength)
                check(rawResponseBodyCopy(request, pointer.address.toInt(), bodyLength) == 0)
            }
            total += bodyLength
        } finally {
            rawRequestClose(request)
        }
    }
    return total
}

@WasmImport("ktor_wasi", "request_create")
private external fun rawRequestCreate(metadataLength: Int, bodyLength: Int): Int

@WasmImport("ktor_wasi", "request_metadata_copy")
private external fun rawRequestMetadataCopy(request: Int, pointer: Int, length: Int): Int

@WasmImport("ktor_wasi", "request_body_copy")
private external fun rawRequestBodyCopy(request: Int, pointer: Int, length: Int): Int

@WasmImport("ktor_wasi", "request_execute")
private external fun rawRequestExecute(request: Int): Int

@WasmImport("ktor_wasi", "response_metadata_length")
private external fun rawResponseMetadataLength(request: Int): Int

@WasmImport("ktor_wasi", "response_metadata_copy")
private external fun rawResponseMetadataCopy(request: Int, pointer: Int, length: Int): Int

@WasmImport("ktor_wasi", "response_body_length")
private external fun rawResponseBodyLength(request: Int): Int

@WasmImport("ktor_wasi", "response_body_copy")
private external fun rawResponseBodyCopy(request: Int, pointer: Int, length: Int): Int

@WasmImport("ktor_wasi", "request_close")
private external fun rawRequestClose(request: Int): Int

fun main() = Unit
