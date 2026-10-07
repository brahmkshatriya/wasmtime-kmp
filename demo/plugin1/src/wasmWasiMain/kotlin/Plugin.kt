@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.brahmkshatriya.wasmtime.demo.plugin1

import dev.brahmkshatriya.wasmtime.demo.shared.ExtensionMessage
import dev.brahmkshatriya.wasmtime.demo.shared.ExtensionMetadata
import dev.brahmkshatriya.wasmtime.demo.shared.Feed
import dev.brahmkshatriya.wasmtime.demo.shared.FeedPageRequest
import dev.brahmkshatriya.wasmtime.demo.shared.MusicExtensionClient
import dev.brahmkshatriya.wasmtime.demo.shared.Page
import dev.brahmkshatriya.wasmtime.demo.shared.Product
import dev.brahmkshatriya.wasmtime.demo.shared.SettingItem
import dev.brahmkshatriya.wasmtime.demo.shared.SettingsOperation
import dev.brahmkshatriya.wasmtime.demo.shared.SettingsRequest
import dev.brahmkshatriya.wasmtime.demo.shared.SettingsResponse
import dev.brahmkshatriya.wasmtime.demo.shared.Shelf
import dev.brahmkshatriya.wasmtime.demo.shared.Tab
import dev.brahmkshatriya.wasmtime.demo.shared.Track
import dev.brahmkshatriya.wasmtime.extension.ExtensionHostCallback
import dev.brahmkshatriya.wasmtime.extension.ExtensionHostStream
import dev.brahmkshatriya.wasmtime.extension.ExtensionHttp
import dev.brahmkshatriya.wasmtime.extension.ExtensionLog
import dev.brahmkshatriya.wasmtime.extension.ExtensionRemoteResources
import dev.brahmkshatriya.wasmtime.extension.ExtensionResources
import dev.brahmkshatriya.wasmtime.extension.ExtensionStorage
import dev.brahmkshatriya.wasmtime.extension.WasmtimeExtensionLifecycle
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }
private val cbor = Cbor { ignoreUnknownKeys = true }

object MusicPlugin : MusicExtensionClient, WasmtimeExtensionLifecycle {
    private var settings: ExtensionHostCallback? = null
    private var messageFlow: ExtensionHostCallback? = null

    override suspend fun onLoad() {
        ExtensionLog.info("Echo-style music extension runtime loaded")
    }

    override suspend fun onUnload() {
        ExtensionLog.info("Echo-style music extension runtime unloaded")
    }

    override suspend fun metadata(): ExtensionMetadata = ExtensionMetadata(
        id = "plugin1",
        name = "Echo-style Music 1",
        version = "unspecified",
        description = "Capability-based music extension with feed paging and host injection.",
    )

    override suspend fun getSettingItems(): List<SettingItem> = listOf(
        SettingItem(
            key = "greeting",
            title = "Greeting",
            description = "Injected host setting read by the extension during initialization.",
            defaultValue = "Hello from Echo-style Music 1",
        )
    )

    override suspend fun setSettings(settingsHandle: Long) {
        settings = ExtensionHostCallback(settingsHandle)
    }

    override suspend fun setMessageFlow(messageHandle: Long) {
        messageFlow = ExtensionHostCallback(messageHandle)
    }

    override suspend fun onInitialize() {
        val greeting = getStringSetting("greeting", "Hello from extension")
        emitMessage("plugin1 initialized: $greeting")
        ExtensionLog.info("Extension API initialized after host injections")
    }

    override suspend fun onExtensionSelected() {
        emitMessage("Selected Echo-style Music 1")
    }

    override suspend fun loadHomeFeed(): Feed {
        val handle = ExtensionRemoteResources.registerCallback { payload ->
            val request = cbor.decodeFromByteArray<FeedPageRequest>(payload)
            cbor.encodeToByteArray<Page<Shelf>>(buildHomePage(request))
        }
        return Feed(
            tabs = listOf(
                Tab("recommended", "Recommended"),
                Tab("new", "New releases"),
            ),
            pagerHandle = handle,
        )
    }

    override suspend fun loadTrack(trackId: Int): Track {
        ExtensionStorage.prepare()
        val counterPath = ExtensionStorage.persistentPath("track-load-count.txt")
        val previous = if (SystemFileSystem.exists(counterPath)) {
            SystemFileSystem.source(counterPath).buffered().use { source ->
                source.readByteArray().decodeToString().toInt()
            }
        } else 0
        val next = previous + 1
        SystemFileSystem.sink(counterPath).buffered().use { sink ->
            sink.write(next.toString().encodeToByteArray())
        }

        val response = ExtensionHttp.get("https://dummyjson.com/products/$trackId")
        check(response.statusCode in 200..299) { "HTTP ${response.statusCode}" }
        val product = json.decodeFromString<Product>(response.bodyAsText())
        return Track(
            id = product.id,
            title = "${product.title} [load=$next]",
            artist = product.brand ?: product.category,
            artworkUrl = product.thumbnail,
        )
    }

    override suspend fun echoProducts(products: List<Product>): List<Product> = products

    override suspend fun invokeHostCallback(handle: Long, value: String): String {
        val callback = ExtensionHostCallback(handle)
        return try {
            callback.invoke(value.encodeToByteArray()).decodeToString()
        } finally {
            callback.release()
        }
    }

    override suspend fun sumHostStream(handle: Long): Int {
        val stream = ExtensionHostStream(handle)
        return try {
            var total = 0
            while (true) {
                val item = stream.next() ?: break
                item.forEach { byte -> total += byte.toInt() and 0xff }
            }
            total
        } finally {
            stream.release()
        }
    }

    override suspend fun readResourceSize(name: String): Int {
        val stream = ExtensionResources.open(name)
        return try {
            var total = 0
            while (true) {
                val chunk = stream.read(4096) ?: break
                total += chunk.size
            }
            total
        } finally {
            stream.close()
        }
    }

    override suspend fun credentialAvailable(): Boolean {
        val response = ExtensionHttp.get(
            url = "https://credential.local/check",
            credential = "demo",
        )
        return response.statusCode == 204
    }

    override suspend fun failForTest() {
        throw IllegalArgumentException("demo structured failure")
    }

    private fun buildHomePage(request: FeedPageRequest): Page<Shelf> {
        val prefix = if (request.tabId == "new") "New" else "Recommended"
        return if (request.continuation == null) {
            Page(
                data = listOf(
                    Shelf(
                        title = "$prefix picks",
                        subtitle = "Page one is loaded through a guest-owned pager resource.",
                        tracks = listOf(
                            demoTrack(1, "$prefix Alpha"),
                            demoTrack(2, "$prefix Beta"),
                        ),
                    )
                ),
                continuation = "page-2",
            )
        } else {
            Page(
                data = listOf(
                    Shelf(
                        title = "$prefix more",
                        subtitle = "The same pager handle survives into this second call.",
                        tracks = listOf(demoTrack(3, "$prefix Gamma")),
                    )
                ),
                continuation = null,
            )
        }
    }

    private fun demoTrack(id: Int, title: String): Track = Track(
        id = id,
        title = "$title · plugin1",
        artist = "Demo Artist 1",
        artworkUrl = null,
        durationMs = 180_000L + id * 1_000L,
    )

    private suspend fun getStringSetting(key: String, defaultValue: String): String {
        val callback = settings ?: return defaultValue
        val request = SettingsRequest(
            operation = SettingsOperation.GetString,
            key = key,
            defaultValue = defaultValue,
        )
        return cbor.decodeFromByteArray<SettingsResponse>(
            callback.invoke(cbor.encodeToByteArray(request))
        ).value ?: defaultValue
    }

    private suspend fun emitMessage(text: String) {
        messageFlow?.invoke(cbor.encodeToByteArray(ExtensionMessage(text)))
    }
}
