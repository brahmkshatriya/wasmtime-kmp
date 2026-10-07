package dev.brahmkshatriya.wasmtime.demo.shared

/** Contract implemented by every demo plugin, regardless of where it runs. */
interface Plugin {
    suspend fun getProductDetails(productId: Int): Product
    suspend fun readResourceSize(name: String): Int
    suspend fun credentialAvailable(): Boolean
    suspend fun failForTest()
}
