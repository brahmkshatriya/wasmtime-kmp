package dev.brahmkshatriya.wasmtime.demo

import dev.brahmkshatriya.wasmtime.WasmtimeBundledExtension
import dev.brahmkshatriya.wasmtime.WasmtimeCredentialProvider
import dev.brahmkshatriya.wasmtime.WasmtimeExtensionLogger
import dev.brahmkshatriya.wasmtime.WasmtimeExtensionException
import dev.brahmkshatriya.wasmtime.WasmtimeExtensionStorage
import dev.brahmkshatriya.wasmtime.WasmtimeHostServices
import dev.brahmkshatriya.wasmtime.WasmtimeHttpHandler
import dev.brahmkshatriya.wasmtime.WasmtimeHttpHeader
import dev.brahmkshatriya.wasmtime.WasmtimeHttpResponse
import dev.brahmkshatriya.wasmtime.WasmtimeLimits
import dev.brahmkshatriya.wasmtime.WasmtimeLoadedExtension
import dev.brahmkshatriya.wasmtime.WasmtimeResourceProvider
import dev.brahmkshatriya.wasmtime.WasmtimeRuntime
import dev.brahmkshatriya.wasmtime.asWasmtimeByteStream
import dev.brahmkshatriya.wasmtime.demo.generated.resources.Res
import dev.brahmkshatriya.wasmtime.demo.shared.Plugin
import dev.brahmkshatriya.wasmtime.demo.shared.Product
import dev.brahmkshatriya.wasmtime.generated.PluginWasmtimeProxy
import dev.brahmkshatriya.wasmtime.generated.WasmtimeBundledExtensions
import dev.brahmkshatriya.wasmtime.load
import dev.brahmkshatriya.wasmtime.loadWasmtimeBundledRuntime
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

data class DemoPlugin(
    val artifact: WasmtimeBundledExtension,
) {
    val id: String get() = artifact.id
    val name: String get() = artifact.name
}

data class LoadedProduct(
    val plugin: DemoPlugin,
    val product: Product,
)

val demoPlugins: List<DemoPlugin> = WasmtimeBundledExtensions.all.map(::DemoPlugin)
const val DEMO_RESOURCE_SIZE: Int = 150_000

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
    if (request.url == "https://credential.local/check") {
        val authorized = request.headers.any {
            it.name.equals("Authorization", ignoreCase = true) && it.value == "Demo host-secret"
        }
        return@WasmtimeHttpHandler WasmtimeHttpResponse(if (authorized) 204 else 401)
    }
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

private val demoCredentials = WasmtimeCredentialProvider { name, request ->
    require(name == "demo") { "unknown credential: $name" }
    require(request.url == "https://credential.local/check") {
        "credential '$name' is not valid for ${request.url}"
    }
    listOf(WasmtimeHttpHeader("Authorization", "Demo host-secret"))
}

private val demoResources = WasmtimeResourceProvider { name ->
    if (name != "demo-resource") null
    else ByteArray(DEMO_RESOURCE_SIZE) { index -> (index * 31).toByte() }.asWasmtimeByteStream()
}

private val demoLogger = WasmtimeExtensionLogger { event ->
    val prefix = event.extensionId?.let { "[$it] " }.orEmpty()
    println("wasmtime ${event.level}: $prefix${event.message}")
}

suspend fun loadPlugin(plugin: DemoPlugin, storageRoot: String): WasmtimeLoadedExtension<Plugin> =
    plugin.artifact.load(
        readResource = Res::readBytes,
        runtime = loadPluginRuntime(),
        services = WasmtimeHostServices(
            http = demoHttpHandler(),
            storage = WasmtimeExtensionStorage("$storageRoot/${plugin.id}"),
            credentials = demoCredentials,
            resources = demoResources,
            logger = demoLogger,
        ),
        limits = limits,
        expectedContract = PluginWasmtimeProxy.CONTRACT,
        proxyFactory = ::PluginWasmtimeProxy,
    )

private suspend fun loadPluginRuntime(): WasmtimeRuntime {
    cachedPluginRuntime?.let { return it }
    return loadWasmtimeBundledRuntime(
        manifestResourcePath = WasmtimeBundledExtensions.runtimeManifestResourcePath,
        readResource = Res::readBytes,
    ).also { cachedPluginRuntime = it }
}

suspend fun runDemoExtensionSmoke(storageRoot: String) {
    for (plugin in demoPlugins) {
        val loaded = loadPlugin(plugin, storageRoot)
        try {
            val productId = if (plugin.id == "plugin1") 1 else 2
            val product = loaded.api.getProductDetails(productId)
            check(product.id == productId) { "unexpected product id ${product.id}" }

            val streamedSize = loaded.api.readResourceSize("demo-resource")
            check(streamedSize == DEMO_RESOURCE_SIZE) {
                "streamed resource size mismatch: $streamedSize != $DEMO_RESOURCE_SIZE"
            }
            check(loaded.api.credentialAvailable()) { "host-owned credential was not injected" }

            val failure = runCatching { loaded.api.failForTest() }.exceptionOrNull()
            check(failure is WasmtimeExtensionException) {
                "expected structured WasmtimeExtensionException, got ${failure?.let { it::class.simpleName }}"
            }
            check(failure.remoteType.contains("IllegalArgumentException")) {
                "unexpected remote failure type: ${failure.remoteType}"
            }
            println("extension_smoke=${plugin.id}:ok product=${product.id} streamed=$streamedSize")
        } finally {
            loaded.shutdown()
        }
    }
}
