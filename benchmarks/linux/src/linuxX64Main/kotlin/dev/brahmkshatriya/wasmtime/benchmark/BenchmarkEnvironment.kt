package dev.brahmkshatriya.wasmtime.benchmark

import dev.brahmkshatriya.wasmtime.Wasmtime
import dev.brahmkshatriya.wasmtime.WasmtimeHttpHandler
import dev.brahmkshatriya.wasmtime.WasmtimeHttpHeader
import dev.brahmkshatriya.wasmtime.WasmtimeHttpResponse
import dev.brahmkshatriya.wasmtime.WasmtimeLimits
import dev.brahmkshatriya.wasmtime.WasmtimeStorage
import dev.brahmkshatriya.wasmtime.loadWasmtimeRuntime
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.time.TimeSource

internal const val NETWORK_SMALL_URL = "http://127.0.0.1:18080/bench?size=1024"
internal const val NETWORK_LARGE_URL = "http://127.0.0.1:18080/bench?size=65536"

internal class BenchmarkEnvironment private constructor(
    val guest: WasmBenchmarkGuest,
    val hostHttpClient: HttpClient,
    val nativeFile: Path,
    private val closeGuest: () -> Unit,
) {
    fun close() {
        closeGuest()
        hostHttpClient.close()
    }

    companion object {
        suspend fun load(): BenchmarkEnvironment {
            val runtimeDir = "./demo/client/build/wasmtime/runtime"
            val guestPath = "./benchmarks/guest/build/wasmtime/benchmark-guest.wasm"
            val storageRoot = "/tmp/wasmtime-kmp-benchmark"
            val guestStorage = "$storageRoot/guest"

            SystemFileSystem.createDirectories(Path(storageRoot), mustCreate = false)
            SystemFileSystem.createDirectories(Path(guestStorage), mustCreate = false)

            val hostHttpClient = HttpClient(Curl) { expectSuccess = false }
            try {
                check(hostHttpClient.get(NETWORK_SMALL_URL).bodyAsBytes().size == 1024)

                val runtimeReadMark = TimeSource.Monotonic.markNow()
                val runtime = loadWasmtimeRuntime(readText("$runtimeDir/runtime.tsv")) { fileName ->
                    readBytes("$runtimeDir/$fileName")
                }
                val runtimeReadNs = runtimeReadMark.elapsedNow().inWholeNanoseconds

                val wasm = readBytes(guestPath)
                val loadMark = TimeSource.Monotonic.markNow()
                val instance = Wasmtime.load(
                    wasm = wasm,
                    limits = WasmtimeLimits(
                        maxMemoryBytes = 128L * 1024L * 1024L,
                        fuel = 100_000_000_000L,
                    ),
                    httpHandler = benchmarkHttpHandler(hostHttpClient),
                    storage = WasmtimeStorage(guestStorage),
                    runtime = runtime,
                )
                val wasmLoadNs = loadMark.elapsedNow().inWholeNanoseconds

                println("ENV|guest_bytes=${wasm.size}|runtime_modules=${runtime.modules.size}")
                println("COLD|runtime_file_read_ns=$runtimeReadNs|wasm_load_ns=$wasmLoadNs")
                println(
                    "  runtime file read ${formatTime(runtimeReadNs)}, " +
                        "Wasmtime compile+instantiate ${formatTime(wasmLoadNs)}",
                )

                return BenchmarkEnvironment(
                    guest = WasmBenchmarkGuest(instance),
                    hostHttpClient = hostHttpClient,
                    nativeFile = Path("$storageRoot/native.bin"),
                    closeGuest = instance::close,
                )
            } catch (error: Throwable) {
                hostHttpClient.close()
                throw error
            }
        }
    }
}

private fun benchmarkHttpHandler(client: HttpClient): WasmtimeHttpHandler = WasmtimeHttpHandler { request ->
    when {
        request.url == "http://wasmtime-kmp.local/redirect-start" ->
            WasmtimeHttpResponse(
                statusCode = 302,
                headers = listOf(WasmtimeHttpHeader(HttpHeaders.Location, "/mock?size=1024")),
            )

        request.url.startsWith("http://wasmtime-kmp.local/mock?size=") -> {
            val size = request.url.substringAfter("size=").toInt()
            WasmtimeHttpResponse(statusCode = 200, body = ByteArray(size) { 7 })
        }

        else -> {
            val response = client.request(request.url) {
                method = HttpMethod(request.method)
                headers {
                    request.headers.forEach { header ->
                        if (!header.name.equals(HttpHeaders.ContentLength, true) &&
                            !header.name.equals(HttpHeaders.TransferEncoding, true) &&
                            !header.name.equals(HttpHeaders.Host, true)
                        ) {
                            append(header.name, header.value)
                        }
                    }
                }
                if (request.body.isNotEmpty()) setBody(request.body)
            }
            WasmtimeHttpResponse(
                statusCode = response.status.value,
                headers = response.headers.entries().flatMap { (name, values) ->
                    values.map { value -> WasmtimeHttpHeader(name, value) }
                },
                body = response.bodyAsBytes(),
            )
        }
    }
}

private fun readBytes(path: String): ByteArray =
    SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }

private fun readText(path: String): String = readBytes(path).decodeToString()

internal suspend fun BenchmarkEnvironment.use(block: suspend (BenchmarkEnvironment) -> Unit) {
    try {
        block(this)
    } finally {
        close()
    }
}
