package dev.brahmkshatriya.wasmtime.demo.plugin2

import dev.brahmkshatriya.wasmtime.demo.shared.Plugin
import dev.brahmkshatriya.wasmtime.demo.shared.Product
import dev.brahmkshatriya.wasmtime.extension.ExtensionHttp
import dev.brahmkshatriya.wasmtime.extension.ExtensionLog
import dev.brahmkshatriya.wasmtime.extension.ExtensionResources
import dev.brahmkshatriya.wasmtime.extension.ExtensionStorage
import dev.brahmkshatriya.wasmtime.extension.WasmtimeExtensionLifecycle
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

object ProductPlugin : Plugin, WasmtimeExtensionLifecycle {
    override suspend fun onLoad() {
        ExtensionStorage.prepare()
        ExtensionLog.info("Product plugin loaded")
    }

    override suspend fun onUnload() {
        ExtensionLog.info("Product plugin unloaded")
    }

    override suspend fun getProductDetails(productId: Int): Product {
        val counterPath = ExtensionStorage.persistentPath("counter.txt")
        val previous = if (SystemFileSystem.exists(counterPath)) {
            SystemFileSystem.source(counterPath).buffered().use { source ->
                source.readByteArray().decodeToString().toInt()
            }
        } else 0
        val next = previous + 1
        SystemFileSystem.sink(counterPath).buffered().use { sink ->
            sink.write(next.toString().encodeToByteArray())
        }

        val response = ExtensionHttp.get("https://dummyjson.com/products/$productId")
        check(response.statusCode in 200..299) { "HTTP ${response.statusCode}" }
        val product = json.decodeFromString<Product>(response.bodyAsText())
        return product.copy(title = "${product.title} [storage=$next]")
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
}
