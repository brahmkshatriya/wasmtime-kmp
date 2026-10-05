package dev.brahmkshatriya.wasmtime.benchmark

import dev.brahmkshatriya.wasmtime.WasmtimeInstance
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlin.time.TimeSource

internal enum class GuestOperation(val code: Int) {
    YIELD(1),
    DELAY(2),
    NETWORK_SMALL(3),
    NETWORK_LARGE(4),
    MOCK_SMALL(5),
    MOCK_LARGE(6),
    RAW_SMALL(7),
    RAW_LARGE(8),
    STATUS_SMALL(9),
    STATUS_LARGE(10),
    REPEATED_BODY_READ(11),
    POST_FALLBACK(12),
    CUSTOM_CLIENT_FALLBACK(13),
    REDIRECT(14),
    PIPELINE_INTERCEPTOR_FALLBACK(15),
    GENERIC_SMALL(16),
    GENERIC_LARGE(17),
}

internal class WasmBenchmarkGuest(private val instance: WasmtimeInstance) {
    private val computeFunction = instance.functionI32("bench_compute")
    private val fileFunction = instance.functionI32("bench_file")
    private val asyncStart = instance.functionI32("bench_async_start")
    private val asyncPoll = instance.functionI32("bench_async_poll")
    private val asyncNextWake = instance.functionI32("bench_async_next_wake")
    private val asyncCancel = instance.functionI32("bench_async_cancel")
    private val asyncResult = instance.functionI32("bench_async_result")

    fun compute(iterations: Int, seed: Int): Int = computeFunction(iterations, seed)

    fun file(iterations: Int, byteCount: Int): Int = fileFunction(iterations, byteCount)

    suspend fun run(operation: GuestOperation, count: Int = 1): Int {
        var state = asyncStart.invokeAsync(operation.code, count)
        var lastPoll = TimeSource.Monotonic.markNow()
        try {
            while (state == PENDING) {
                when (val wake = asyncNextWake(0, 0)) {
                    0 -> yield()
                    in 1..Int.MAX_VALUE -> delay(wake.toLong())
                    else -> yield()
                }

                val elapsedMillis = lastPoll.elapsedNow().inWholeMilliseconds
                    .coerceIn(0L, Int.MAX_VALUE.toLong())
                    .toInt()
                state = asyncPoll.invokeAsync(elapsedMillis, 0)
                lastPoll = TimeSource.Monotonic.markNow()
            }

            check(state == SUCCESS) { "guest async benchmark failed with state $state" }
            return asyncResult(0, 0)
        } finally {
            if (state == PENDING) asyncCancel(0, 0)
        }
    }

    companion object {
        private const val PENDING = 0
        private const val SUCCESS = 1
    }
}
