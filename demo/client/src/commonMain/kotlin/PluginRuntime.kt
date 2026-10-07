@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.brahmkshatriya.wasmtime.demo

import dev.brahmkshatriya.wasmtime.WasmtimeBundledExtension
import dev.brahmkshatriya.wasmtime.WasmtimeCredentialProvider
import dev.brahmkshatriya.wasmtime.WasmtimeExtensionException
import dev.brahmkshatriya.wasmtime.WasmtimeExtensionLogger
import dev.brahmkshatriya.wasmtime.WasmtimeExtensionStorage
import dev.brahmkshatriya.wasmtime.WasmtimeHostResourceRegistry
import dev.brahmkshatriya.wasmtime.WasmtimeHostServices
import dev.brahmkshatriya.wasmtime.WasmtimeHttpHandler
import dev.brahmkshatriya.wasmtime.WasmtimeHttpHeader
import dev.brahmkshatriya.wasmtime.WasmtimeHttpResponse
import dev.brahmkshatriya.wasmtime.WasmtimeLimits
import dev.brahmkshatriya.wasmtime.WasmtimeLoadedExtension
import dev.brahmkshatriya.wasmtime.WasmtimeRemoteCallback
import dev.brahmkshatriya.wasmtime.WasmtimeResourceProvider
import dev.brahmkshatriya.wasmtime.WasmtimeRuntime
import dev.brahmkshatriya.wasmtime.asWasmtimeByteStream
import dev.brahmkshatriya.wasmtime.demo.generated.resources.Res
import dev.brahmkshatriya.wasmtime.demo.shared.ExtensionMessage
import dev.brahmkshatriya.wasmtime.demo.shared.Feed
import dev.brahmkshatriya.wasmtime.demo.shared.FeedPageRequest
import dev.brahmkshatriya.wasmtime.demo.shared.MusicExtensionClient
import dev.brahmkshatriya.wasmtime.demo.shared.Page
import dev.brahmkshatriya.wasmtime.demo.shared.SettingsOperation
import dev.brahmkshatriya.wasmtime.demo.shared.SettingsRequest
import dev.brahmkshatriya.wasmtime.demo.shared.SettingsResponse
import dev.brahmkshatriya.wasmtime.demo.shared.Shelf
import dev.brahmkshatriya.wasmtime.demo.shared.Tab
import dev.brahmkshatriya.wasmtime.demo.shared.Track
import dev.brahmkshatriya.wasmtime.generated.MusicExtensionClientWasmtimeProxy
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.io.readByteArray
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

private val cbor = Cbor { ignoreUnknownKeys = true }

data class DemoPlugin(
    val artifact: WasmtimeBundledExtension,
) {
    val id: String get() = artifact.id
    val name: String get() = artifact.name
}

data class LoadedHome(
    val plugin: DemoPlugin,
    val metadataName: String,
    val description: String,
    val shelves: List<Shelf>,
    val messages: List<ExtensionMessage>,
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
private val demoRemoteResources = WasmtimeHostResourceRegistry()

private val demoLogger = WasmtimeExtensionLogger { event ->
    val prefix = event.extensionId?.let { "[$it] " }.orEmpty()
    println("wasmtime ${event.level}: $prefix${event.message}")
}

class DemoExtensionSession internal constructor(
    val plugin: DemoPlugin,
    private val loaded: WasmtimeLoadedExtension<MusicExtensionClient>,
    private val settingsHandle: Long,
    private val messageHandle: Long,
    val messages: MutableList<ExtensionMessage>,
) {
    val api: MusicExtensionClient get() = loaded.api
    val supportsPersistentResources: Boolean get() = loaded.supportsPersistentResources

    suspend fun loadHomeFeed(): RemoteFeed {
        val descriptor = api.loadHomeFeed()
        return RemoteFeed(descriptor, loaded.remoteCallback(descriptor.pagerHandle))
    }

    suspend fun shutdown() {
        try {
            loaded.shutdown()
        } finally {
            demoRemoteResources.release(settingsHandle)
            demoRemoteResources.release(messageHandle)
        }
    }
}

class RemoteFeed internal constructor(
    val descriptor: Feed,
    private val pager: WasmtimeRemoteCallback,
) {
    val tabs: List<Tab> get() = descriptor.tabs

    suspend fun loadPage(tab: Tab?, continuation: String? = null): Page<Shelf> {
        val request = FeedPageRequest(tabId = tab?.id, continuation = continuation)
        return cbor.decodeFromByteArray(
            pager.invoke(cbor.encodeToByteArray(request))
        )
    }

    suspend fun close() {
        pager.release()
    }
}

suspend fun loadPlugin(plugin: DemoPlugin, storageRoot: String): DemoExtensionSession {
    val settings = mutableMapOf(
        "greeting" to "Host setting for ${plugin.name}",
    )
    val messages = mutableListOf<ExtensionMessage>()
    val settingsHandle = demoRemoteResources.registerCallback { payload ->
        val request = cbor.decodeFromByteArray<SettingsRequest>(payload)
        val response = when (request.operation) {
            SettingsOperation.GetString -> SettingsResponse(
                settings[request.key] ?: request.defaultValue
            )
            SettingsOperation.PutString -> {
                settings[request.key] = requireNotNull(request.value) { "setting value is required" }
                SettingsResponse(request.value)
            }
        }
        cbor.encodeToByteArray(response)
    }
    val messageHandle = demoRemoteResources.registerCallback { payload ->
        val message = cbor.decodeFromByteArray<ExtensionMessage>(payload)
        messages += message
        println("extension message [${plugin.id}]: ${message.text}")
        ByteArray(0)
    }

    var loaded: WasmtimeLoadedExtension<MusicExtensionClient>? = null
    try {
        loaded = plugin.artifact.load(
            readResource = Res::readBytes,
            runtime = loadPluginRuntime(),
            services = WasmtimeHostServices(
                http = demoHttpHandler(),
                storage = WasmtimeExtensionStorage("$storageRoot/${plugin.id}"),
                credentials = demoCredentials,
                resources = demoResources,
                remoteResources = demoRemoteResources,
                logger = demoLogger,
            ),
            limits = limits,
            expectedContract = MusicExtensionClientWasmtimeProxy.CONTRACT,
            proxyFactory = ::MusicExtensionClientWasmtimeProxy,
        )
        loaded.api.setSettings(settingsHandle)
        loaded.api.setMessageFlow(messageHandle)
        loaded.api.onInitialize()
        return DemoExtensionSession(plugin, loaded, settingsHandle, messageHandle, messages)
    } catch (failure: Throwable) {
        loaded?.shutdown()
        demoRemoteResources.release(settingsHandle)
        demoRemoteResources.release(messageHandle)
        throw failure
    }
}

private suspend fun loadPluginRuntime(): WasmtimeRuntime {
    cachedPluginRuntime?.let { return it }
    return loadWasmtimeBundledRuntime(
        manifestResourcePath = WasmtimeBundledExtensions.runtimeManifestResourcePath,
        readResource = Res::readBytes,
    ).also { cachedPluginRuntime = it }
}

suspend fun loadHomePreview(plugin: DemoPlugin, storageRoot: String): LoadedHome {
    val session = loadPlugin(plugin, storageRoot)
    try {
        session.api.onExtensionSelected()
        val metadata = session.api.metadata()
        val feed = session.loadHomeFeed()
        try {
            val tab = feed.tabs.firstOrNull()
            val first = feed.loadPage(tab)
            val second = first.continuation?.let { feed.loadPage(tab, it) }
            return LoadedHome(
                plugin = plugin,
                metadataName = metadata.name,
                description = metadata.description,
                shelves = first.data + second?.data.orEmpty(),
                messages = session.messages.toList(),
            )
        } finally {
            feed.close()
        }
    } finally {
        session.shutdown()
    }
}

suspend fun runDemoExtensionSmoke(storageRoot: String) {
    for (plugin in demoPlugins) {
        val session = loadPlugin(plugin, storageRoot)
        try {
            val metadata = session.api.metadata()
            check(metadata.id == plugin.id) { "unexpected extension id ${metadata.id}" }
            check(session.api.getSettingItems().isNotEmpty()) { "settings provider returned no items" }
            session.api.onExtensionSelected()
            check(session.messages.size >= 2) { "message flow injection did not receive lifecycle messages" }

            check(session.supportsPersistentResources) { "demo backend must preserve guest resources" }
            val feed = session.loadHomeFeed()
            try {
                val first = feed.loadPage(feed.tabs.first())
                check(first.data.isNotEmpty()) { "home feed first page is empty" }
                val continuation = checkNotNull(first.continuation) { "home feed did not expose a second page" }
                val second = feed.loadPage(feed.tabs.first(), continuation)
                check(second.data.isNotEmpty() && second.continuation == null) {
                    "home feed second page is invalid"
                }
            } finally {
                feed.close()
            }

            val trackId = if (plugin.id == "plugin1") 1 else 2
            val track: Track = session.api.loadTrack(trackId)
            check(track.id == trackId) { "unexpected track id ${track.id}" }

            val hostCallback = demoRemoteResources.registerCallback { payload ->
                "host:${payload.decodeToString()}".encodeToByteArray()
            }
            check(session.api.invokeHostCallback(hostCallback, "callback") == "host:callback") {
                "host callback resource failed"
            }

            coroutineScope {
                val hostStream = demoRemoteResources.registerFlow(
                    scope = this,
                    flow = flowOf(byteArrayOf(1, 2), byteArrayOf(3, 4)),
                )
                check(session.api.sumHostStream(hostStream) == 10) {
                    "host Flow resource failed"
                }
            }

            val streamedSize = session.api.readResourceSize("demo-resource")
            check(streamedSize == DEMO_RESOURCE_SIZE) {
                "streamed resource size mismatch: $streamedSize != $DEMO_RESOURCE_SIZE"
            }
            check(session.api.credentialAvailable()) { "host-owned credential was not injected" }

            val failure = runCatching { session.api.failForTest() }.exceptionOrNull()
            check(failure is WasmtimeExtensionException) {
                "expected structured WasmtimeExtensionException, got ${failure?.let { it::class.simpleName }}"
            }
            check(failure.remoteType.contains("IllegalArgumentException")) {
                "unexpected remote failure type: ${failure.remoteType}"
            }
            println(
                "extension_smoke=${plugin.id}:ok track=${track.id} " +
                    "messages=${session.messages.size} streamed=$streamedSize"
            )
        } finally {
            session.shutdown()
        }
    }
}
