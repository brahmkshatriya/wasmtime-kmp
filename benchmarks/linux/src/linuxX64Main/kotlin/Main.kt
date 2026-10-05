package dev.brahmkshatriya.wasmtime.benchmark

import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    BenchmarkEnvironment.load().use { environment ->
        BenchmarkSuite(environment).run()
    }
}
