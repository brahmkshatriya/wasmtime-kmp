package dev.brahmkshatriya.wasmtime.benchmark

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

internal object NativeBenchmarks {
    fun compute(iterations: Int, seed: Int): Int {
        var value = seed
        repeat(iterations) {
            value = value * 1664525 + 1013904223
            value = value xor (value ushr 13)
            value *= -2048144789
            value = value xor (value ushr 16)
        }
        return value
    }

    fun file(path: Path, iterations: Int, byteCount: Int): Int {
        val payload = ByteArray(byteCount) { index -> (index * 31 + 7).toByte() }
        var checksum = 0
        repeat(iterations) {
            SystemFileSystem.sink(path).buffered().use { it.write(payload) }
            val read = SystemFileSystem.source(path).buffered().use { it.readByteArray() }
            checksum = checksum xor read.size
            if (read.isNotEmpty()) checksum = checksum xor (read.last().toInt() and 0xff)
        }
        return checksum
    }

    suspend fun yield(count: Int): Int {
        repeat(count) { yield() }
        return count
    }

    suspend fun delayOneMillis(count: Int): Int {
        repeat(count) { delay(1) }
        return count
    }

    suspend fun network(client: HttpClient, url: String, count: Int): Int {
        var total = 0
        repeat(count) { total += client.get(url).bodyAsBytes().size }
        return total
    }
}
