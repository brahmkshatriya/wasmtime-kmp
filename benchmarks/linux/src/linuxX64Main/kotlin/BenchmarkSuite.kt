package dev.brahmkshatriya.wasmtime.benchmark

internal class BenchmarkSuite(
    private val environment: BenchmarkEnvironment,
) {
    private val guest = environment.guest

    suspend fun run() {
        compute()
        coroutines()
        files()
        verifyKtorFastPathSemantics()
        mockHttpLayers()
        realNetwork()
        BenchmarkHarness.printBlackhole()
    }

    private suspend fun compute() {
        val iterations = 20_000_000
        val seed = 0x12345678
        check(guest.compute(iterations, seed) == NativeBenchmarks.compute(iterations, seed))

        BenchmarkHarness.compare(
            name = "compute_20m_int_mix",
            warmups = 2,
            runs = 9,
            native = { NativeBenchmarks.compute(iterations, seed) },
            wasm = { guest.compute(iterations, seed) },
        )
    }

    private suspend fun coroutines() {
        BenchmarkHarness.compare(
            name = "coroutine_50k_yield",
            warmups = 2,
            runs = 7,
            native = { NativeBenchmarks.yield(50_000) },
            wasm = { guest.run(GuestOperation.YIELD, 50_000) },
        )
        BenchmarkHarness.compare(
            name = "coroutine_50x_delay_1ms",
            warmups = 1,
            runs = 5,
            native = { NativeBenchmarks.delayOneMillis(50) },
            wasm = { guest.run(GuestOperation.DELAY, 50) },
        )
    }

    private suspend fun files() {
        check(guest.file(1, 4096) == NativeBenchmarks.file(environment.nativeFile, 1, 4096))

        BenchmarkHarness.compare(
            name = "file_500x_4k_write_read",
            warmups = 1,
            runs = 7,
            native = { NativeBenchmarks.file(environment.nativeFile, 500, 4096) },
            wasm = { guest.file(500, 4096) },
        )
        BenchmarkHarness.compare(
            name = "file_32x_256k_write_read",
            warmups = 1,
            runs = 5,
            native = { NativeBenchmarks.file(environment.nativeFile, 32, 256 * 1024) },
            wasm = { guest.file(32, 256 * 1024) },
        )
    }

    private suspend fun verifyKtorFastPathSemantics() {
        check(guest.run(GuestOperation.REPEATED_BODY_READ) == 2048) { "fast-path repeated body read failed" }
        check(guest.run(GuestOperation.POST_FALLBACK) == 1024) { "POST fallback failed" }
        check(guest.run(GuestOperation.CUSTOM_CLIENT_FALLBACK) == 1024) { "custom-client fallback failed" }
        check(guest.run(GuestOperation.REDIRECT) == 1024) { "redirect fast path failed" }
        check(guest.run(GuestOperation.PIPELINE_INTERCEPTOR_FALLBACK) == 11024) {
            "direct pipeline interceptor fallback failed"
        }
        println("CHECK|network_fast_path_semantics=ok")
    }

    private suspend fun mockHttpLayers() {
        check(guest.run(GuestOperation.STATUS_SMALL) == 200)
        compareMock("ktor_status_20x_1k", GuestOperation.STATUS_SMALL, 20, 20 * 200)

        check(guest.run(GuestOperation.STATUS_LARGE) == 200)
        compareMock("ktor_status_10x_64k", GuestOperation.STATUS_LARGE, 10, 10 * 200)

        check(guest.run(GuestOperation.RAW_SMALL) == 1024)
        compareMock("bridge_raw_20x_1k", GuestOperation.RAW_SMALL, 20, 20 * 1024) {
            repeat(20) { ByteArray(1024) { 7 } }
        }

        check(guest.run(GuestOperation.RAW_LARGE) == 65536)
        compareMock("bridge_raw_10x_64k", GuestOperation.RAW_LARGE, 10, 10 * 64 * 1024) {
            repeat(10) { ByteArray(64 * 1024) { 7 } }
        }

        check(guest.run(GuestOperation.MOCK_SMALL) == 1024)
        compareMock("bridge_mock_20x_1k", GuestOperation.MOCK_SMALL, 20, 20 * 1024) {
            repeat(20) { ByteArray(1024) { 7 } }
        }

        check(guest.run(GuestOperation.MOCK_LARGE) == 65536)
        compareMock("bridge_mock_10x_64k", GuestOperation.MOCK_LARGE, 10, 10 * 64 * 1024) {
            repeat(10) { ByteArray(64 * 1024) { 7 } }
        }

        compareMock("ktor_generic_mock_20x_1k", GuestOperation.GENERIC_SMALL, 20, 20 * 1024)
        compareMock("ktor_generic_mock_10x_64k", GuestOperation.GENERIC_LARGE, 10, 10 * 64 * 1024)
    }

    private suspend fun realNetwork() {
        check(guest.run(GuestOperation.NETWORK_SMALL) == 1024)
        BenchmarkHarness.compare(
            name = "network_10x_local_1k",
            warmups = 1,
            runs = 5,
            native = { NativeBenchmarks.network(environment.hostHttpClient, NETWORK_SMALL_URL, 10) },
            wasm = { guest.run(GuestOperation.NETWORK_SMALL, 10) },
        )

        check(guest.run(GuestOperation.NETWORK_LARGE) == 65536)
        BenchmarkHarness.compare(
            name = "network_5x_local_64k",
            warmups = 1,
            runs = 5,
            native = { NativeBenchmarks.network(environment.hostHttpClient, NETWORK_LARGE_URL, 5) },
            wasm = { guest.run(GuestOperation.NETWORK_LARGE, 5) },
        )
    }

    private suspend fun compareMock(
        name: String,
        operation: GuestOperation,
        count: Int,
        nativeResult: Int,
        nativeSetup: () -> Unit = {},
    ) {
        BenchmarkHarness.compare(
            name = name,
            warmups = 1,
            runs = 5,
            native = {
                nativeSetup()
                nativeResult
            },
            wasm = { guest.run(operation, count) },
        )
    }
}
