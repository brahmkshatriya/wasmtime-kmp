package dev.brahmkshatriya.wasmtime

import kotlin.test.Test
import kotlin.test.assertEquals

class WasmtimeLinuxArm64Test {
    @Test
    fun loadsAndCallsWasm() {
        val wasm = byteArrayOf(
            0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
            0x01, 0x07, 0x01, 0x60, 0x02, 0x7f, 0x7f, 0x01,
            0x7f, 0x03, 0x02, 0x01, 0x00, 0x07, 0x07, 0x01,
            0x03, 0x61, 0x64, 0x64, 0x00, 0x00, 0x0a, 0x09,
            0x01, 0x07, 0x00, 0x20, 0x00, 0x20, 0x01, 0x6a,
            0x0b,
        )
        val instance = Wasmtime.load(
            wasm = wasm,
            limits = WasmtimeLimits(
                maxMemoryBytes = 8L * 1024 * 1024,
                fuel = 1_000_000,
            ),
        )
        try {
            assertEquals(42, instance.callI32("add", 20, 22))
        } finally {
            instance.close()
        }
    }
}
