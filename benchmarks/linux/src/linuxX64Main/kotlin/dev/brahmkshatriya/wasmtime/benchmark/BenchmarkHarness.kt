package dev.brahmkshatriya.wasmtime.benchmark

import kotlin.time.TimeSource

internal object BenchmarkHarness {
    private var blackhole = 0

    suspend fun compare(
        name: String,
        warmups: Int,
        runs: Int,
        native: suspend () -> Int,
        wasm: suspend () -> Int,
    ) {
        repeat(warmups) {
            consume(native())
            consume(wasm())
        }

        val nativeSamples = mutableListOf<Long>()
        val wasmSamples = mutableListOf<Long>()
        repeat(runs) { index ->
            if (index % 2 == 0) {
                nativeSamples += timed(native)
                wasmSamples += timed(wasm)
            } else {
                wasmSamples += timed(wasm)
                nativeSamples += timed(native)
            }
        }

        val nativeSummary = nativeSamples.summary()
        val wasmSummary = wasmSamples.summary()
        val ratio = wasmSummary.medianNs.toDouble() / nativeSummary.medianNs.toDouble()

        println(
            "RESULT|$name|native_ns=${nativeSummary.medianNs}|wasm_ns=${wasmSummary.medianNs}|" +
                "ratio=${fixed3(ratio)}|native_range=${nativeSummary.minNs}-${nativeSummary.maxNs}|" +
                "wasm_range=${wasmSummary.minNs}-${wasmSummary.maxNs}",
        )
        println(
            "  native ${formatTime(nativeSummary.medianNs)}, " +
                "wasm ${formatTime(wasmSummary.medianNs)}, ${fixed3(ratio)}x",
        )
    }

    fun printBlackhole() {
        println("BLACKHOLE|$blackhole")
    }

    private suspend fun timed(block: suspend () -> Int): Long {
        val mark = TimeSource.Monotonic.markNow()
        consume(block())
        return mark.elapsedNow().inWholeNanoseconds
    }

    private fun consume(value: Int) {
        blackhole = blackhole xor value
    }
}

private data class SampleSummary(val medianNs: Long, val minNs: Long, val maxNs: Long)

private fun List<Long>.summary(): SampleSummary {
    val sorted = sorted()
    return SampleSummary(sorted[sorted.size / 2], sorted.first(), sorted.last())
}

internal fun formatTime(ns: Long): String = when {
    ns >= 1_000_000_000L -> "${fixed3(ns / 1_000_000_000.0)} s"
    ns >= 1_000_000L -> "${fixed3(ns / 1_000_000.0)} ms"
    ns >= 1_000L -> "${fixed3(ns / 1_000.0)} us"
    else -> "$ns ns"
}

private fun fixed3(value: Double): String {
    val scaled = (value * 1000.0 + 0.5).toLong()
    val whole = scaled / 1000
    val fraction = (scaled % 1000).toString().padStart(3, '0')
    return "$whole.$fraction"
}
