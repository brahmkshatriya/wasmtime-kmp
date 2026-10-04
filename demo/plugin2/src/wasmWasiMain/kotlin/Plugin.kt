package dev.brahmkshatriya.wasmtime.demo.plugin2

import dev.brahmkshatriya.wasmtime.demo.shared.ExtensionEntry
import dev.brahmkshatriya.wasmtime.demo.shared.Plugin
import dev.brahmkshatriya.wasmtime.demo.shared.Product
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private const val PRODUCT_URL = "https://dummyjson.com/products/2"
private val client = HttpClient()
private val json = Json { ignoreUnknownKeys = true }

@ExtensionEntry
object ProductPlugin : Plugin {
    override suspend fun getProductDetails(): Product {
        val counterPath = Path("/data/counter.txt")
        val previous = if (SystemFileSystem.exists(counterPath)) {
            SystemFileSystem.source(counterPath).buffered().use { source ->
                source.readByteArray().decodeToString().toInt()
            }
        } else 0
        val next = previous + 1
        SystemFileSystem.sink(counterPath).buffered().use { sink ->
            sink.write(next.toString().encodeToByteArray())
        }
        val stored = SystemFileSystem.source(counterPath).buffered().use { source ->
            source.readByteArray().decodeToString().toInt()
        }
        check(stored == next)

        val responseJson = client.get(PRODUCT_URL).bodyAsText()
        val product = json.decodeFromString<Product>(responseJson)
        return product.copy(title = "${product.title} [storage=$stored]")
    }
}
