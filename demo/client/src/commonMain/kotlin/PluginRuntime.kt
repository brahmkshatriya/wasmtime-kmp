package dev.brahmkshatriya.wasmtime.demo

import dev.brahmkshatriya.wasmtime.WasmtimeHttpHandler
import dev.brahmkshatriya.wasmtime.WasmtimeHttpHeader
import dev.brahmkshatriya.wasmtime.WasmtimeHttpResponse
import dev.brahmkshatriya.wasmtime.WasmtimeLimits
import dev.brahmkshatriya.wasmtime.WasmtimeRuntime
import dev.brahmkshatriya.wasmtime.WasmtimeStorage
import dev.brahmkshatriya.wasmtime.createWasmtimeExtensionTransport
import dev.brahmkshatriya.wasmtime.demo.generated.resources.Res
import dev.brahmkshatriya.wasmtime.demo.shared.Plugin
import dev.brahmkshatriya.wasmtime.demo.shared.Product
import dev.brahmkshatriya.wasmtime.generated.PluginWasmtimeProxy
import dev.brahmkshatriya.wasmtime.loadWasmtimeRuntime
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

data class DemoPlugin(
    val id: String,
    val name: String,
    val resourcePath: String,
)

data class LoadedProduct(
    val plugin: DemoPlugin,
    val product: Product,
)

val demoPlugins: List<DemoPlugin> = listOf(
    DemoPlugin("plugin1", "Plugin 1", "files/plugin1.wasm"),
    DemoPlugin("plugin2", "Plugin 2", "files/plugin2.wasm"),
)

private val hostHttpClient = HttpClient {
    expectSuccess = false
    followRedirects = false
}
private val limits = WasmtimeLimits(
    maxMemoryBytes = 64L * 1024L * 1024L,
    fuel = 200_000_000L,
)
private var cachedPluginRuntime: WasmtimeRuntime? = null

private fun demoHttpHandler(): WasmtimeHttpHandler = WasmtimeHttpHandler { request ->
    require(request.method == "GET") { "network method denied: ${request.method}" }
    require(request.body.isEmpty()) { "network request body denied" }
    require(request.url.matches(Regex("""https://dummyjson\.com/products/[0-9]+"""))) {
        "network request denied: ${request.url}"
    }

    val response = hostHttpClient.request(request.url) {
        method = HttpMethod(request.method)
        headers {
            request.headers.forEach { header ->
                if (!header.name.equals(HttpHeaders.ContentLength, ignoreCase = true) &&
                    !header.name.equals(HttpHeaders.TransferEncoding, ignoreCase = true) &&
                    !header.name.equals(HttpHeaders.Host, ignoreCase = true)
                ) {
                    append(header.name, header.value)
                }
            }
        }
    }
    require(response.status.value !in 300..399) {
        "network redirect denied: ${response.status.value} ${response.headers[HttpHeaders.Location].orEmpty()}"
    }
    response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { contentLength ->
        require(limits.maxHttpResponseBytes == 0 || contentLength <= limits.maxHttpResponseBytes) {
            "HTTP response is too large: $contentLength > ${limits.maxHttpResponseBytes} bytes"
        }
    }
    val readLimit = if (limits.maxHttpResponseBytes == 0) Long.MAX_VALUE
        else limits.maxHttpResponseBytes.toLong() + 1L
    val responseBody = response.bodyAsChannel().readBuffer(readLimit).readByteArray()
    require(limits.maxHttpResponseBytes == 0 || responseBody.size <= limits.maxHttpResponseBytes) {
        "HTTP response is too large: ${responseBody.size} > ${limits.maxHttpResponseBytes} bytes"
    }

    WasmtimeHttpResponse(
        statusCode = response.status.value,
        headers = response.headers.entries().flatMap { (name, values) ->
            values.map { value -> WasmtimeHttpHeader(name, value) }
        },
        body = responseBody,
    )
}

suspend fun loadPlugin(plugin: DemoPlugin, storageRoot: String): Plugin {
    val wasm = Res.readBytes(plugin.resourcePath)
    val runtime = loadPluginRuntime()
    val httpHandler = demoHttpHandler()
    return PluginWasmtimeProxy(
        createWasmtimeExtensionTransport(
            wasm = wasm,
            limits = limits,
            httpHandler = httpHandler,
            storage = WasmtimeStorage(
                backingPath = "$storageRoot/${plugin.id}",
                guestPath = "/data",
            ),
            runtime = runtime,
            maxResultBytes = 1_000_000,
        )
    )
}

private suspend fun loadPluginRuntime(): WasmtimeRuntime {
    cachedPluginRuntime?.let { return it }

    val resources = mutableMapOf<String, ByteArray>()
    val runtime = loadWasmtimeRuntime(
        manifest = Res.readBytes("files/runtime/runtime.tsv").decodeToString(),
    ) { fileName ->
        resources[fileName] ?: Res.readBytes("files/runtime/$fileName").also {
            resources[fileName] = it
        }
    }
    return runtime.also { cachedPluginRuntime = it }
}
