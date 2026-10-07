package dev.brahmkshatriya.wasmtime

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class WasmtimeLowLevelTest {
    @Test
    fun callsCStyleAbiWithMemoryAndHostImports() {
        var hostSaw = ByteArray(0)
        val imports = wasmtimeImports {
            function(
                module = "host",
                name = "sum_bytes",
                parameters = listOf(WasmValueType.I32, WasmValueType.I32),
                results = listOf(WasmValueType.I32),
            ) { call ->
                val pointer = (call.arguments[0] as WasmValue.I32).value
                val length = (call.arguments[1] as WasmValue.I32).value
                val text = call.memory.readUtf8(pointer, length)
                val bytes = text.encodeToByteArray()
                hostSaw = bytes
                listOf(WasmValue.I32(bytes.sumOf { it.toUByte().toInt() }))
            }
        }

        val instance = Wasmtime.load(
            wasm = C_ABI_WASM,
            imports = imports,
            limits = WasmtimeLimits(fuel = 1_000_000),
        )
        try {
            val allocType = WasmtimeFunctionType(
                parameters = listOf(WasmValueType.I32),
                results = listOf(WasmValueType.I32),
            )
            val pointer = (
                instance.call(
                    exportName = "alloc",
                    type = allocType,
                    arguments = listOf(WasmValue.I32(5)),
                ).single() as WasmValue.I32
            ).value

            val memory = instance.memory()
            assertEquals(64 * 1024, memory.size)
            assertEquals(5, memory.writeUtf8(pointer, "hello"))
            assertEquals("hello", memory.readUtf8(pointer, 5))

            val sum = instance.call(
                exportName = "sum_bytes",
                type = WasmtimeFunctionType(
                    parameters = listOf(WasmValueType.I32, WasmValueType.I32),
                    results = listOf(WasmValueType.I32),
                ),
                arguments = listOf(WasmValue.I32(pointer), WasmValue.I32(5)),
            ).single() as WasmValue.I32
            assertEquals("hello".encodeToByteArray().sumOf { it.toUByte().toInt() }, sum.value)
            assertContentEquals("hello".encodeToByteArray(), hostSaw)

            val i64Value = -9_223_372_036_854_000_000L
            val echoedI64 = instance.call(
                exportName = "echo_i64",
                type = WasmtimeFunctionType(
                    parameters = listOf(WasmValueType.I64),
                    results = listOf(WasmValueType.I64),
                ),
                arguments = listOf(WasmValue.I64(i64Value)),
            ).single() as WasmValue.I64
            assertEquals(i64Value, echoedI64.value)

            val echoedF32 = instance.call(
                exportName = "echo_f32",
                type = WasmtimeFunctionType(
                    parameters = listOf(WasmValueType.F32),
                    results = listOf(WasmValueType.F32),
                ),
                arguments = listOf(WasmValue.F32(3.25f)),
            ).single() as WasmValue.F32
            assertEquals(3.25f, echoedF32.value)

            val addedF64 = instance.call(
                exportName = "add_f64",
                type = WasmtimeFunctionType(
                    parameters = listOf(WasmValueType.F64, WasmValueType.F64),
                    results = listOf(WasmValueType.F64),
                ),
                arguments = listOf(WasmValue.F64(1.25), WasmValue.F64(2.5)),
            ).single() as WasmValue.F64
            assertEquals(3.75, addedF64.value)

            assertFails {
                memory.read(memory.size - 1, 2)
            }
        } finally {
            instance.close()
        }
    }

    @Test
    fun cAbiConveniencesHandleStringsBuffersArraysAndPacking() {
        val imports = wasmtimeImports {
            function(
                module = "host",
                name = "sum_bytes",
                parameters = listOf(WasmValueType.I32, WasmValueType.I32),
                results = listOf(WasmValueType.I32),
            ) { call ->
                val pointer = (call.arguments[0] as WasmValue.I32).value
                val length = (call.arguments[1] as WasmValue.I32).value
                val bytes = call.memory.read(pointer, length)
                listOf(WasmValue.I32(bytes.sumOf { it.toUByte().toInt() }))
            }
        }
        val instance = Wasmtime.load(
            wasm = C_ABI_WASM,
            imports = imports,
            limits = WasmtimeLimits(fuel = 1_000_000),
        )
        try {
            val abi = instance.cAbi(
                WasmtimeCAbiConfig(
                    deallocator = WasmtimeCDeallocator.PointerLength("free"),
                ),
            )

            abi.withUtf8("héllo") { allocation ->
                assertEquals("héllo", abi.readUtf8(allocation.region))
                assertEquals("héllo".encodeToByteArray().size, allocation.length)
                assertEquals(
                    listOf(WasmValue.I32(allocation.pointer), WasmValue.I32(allocation.length)),
                    allocation.arguments(),
                )
            }

            abi.withUtf8("") { allocation ->
                assertEquals(0, allocation.length)
                assertEquals(1, allocation.allocatedBytes)
                assertEquals(1024, allocation.pointer)
            }

            abi.withCString("hello-c") { allocation ->
                assertEquals("hello-c", abi.readCString(allocation.pointer))
                assertEquals(8, allocation.allocatedBytes)
            }

            val cStringPointer = (
                instance.call(
                    exportName = "cstring",
                    type = WasmtimeFunctionType(results = listOf(WasmValueType.I32)),
                ).single() as WasmValue.I32
            ).value
            assertEquals("hello-c", abi.readCString(cStringPointer))
            assertNull(abi.readNullableCString(0))

            val packedWorld = (
                instance.call(
                    exportName = "packed_world",
                    type = WasmtimeFunctionType(results = listOf(WasmValueType.I64)),
                ).single() as WasmValue.I64
            ).value
            assertEquals("world", abi.readPackedUtf8(packedWorld))
            assertEquals(WasmtimePointerLength(2048, 5), abi.unpack(packedWorld))
            assertNull(abi.readNullablePackedUtf8(0L))

            val alternate = WasmtimePointerLength(1234, 77)
            val alternatePacked = abi.pack(
                alternate,
                WasmtimePointerLengthPacking.LengthHighPointerLow,
            )
            assertEquals(
                alternate,
                abi.unpack(
                    alternatePacked,
                    WasmtimePointerLengthPacking.LengthHighPointerLow,
                ),
            )
            assertEquals(WasmtimePointerLength(0, 0), abi.nullableRegion(null))

            abi.withBytes(byteArrayOf(1, 2, 3, 4)) { allocation ->
                assertContentEquals(byteArrayOf(1, 2, 3, 4), abi.readBytes(allocation.region))
            }

            val ints = intArrayOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)
            val intAllocation = abi.writeIntArray(ints)
            try {
                assertContentEquals(ints, abi.readIntArray(intAllocation.pointer, ints.size))
            } finally {
                abi.release(intAllocation)
            }

            val longs = longArrayOf(Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE)
            val longAllocation = abi.writeLongArray(longs)
            try {
                assertContentEquals(longs, abi.readLongArray(longAllocation.pointer, longs.size))
            } finally {
                abi.release(longAllocation)
            }

            val floats = floatArrayOf(-3.5f, 0f, 4.25f)
            val floatAllocation = abi.writeFloatArray(floats)
            try {
                assertContentEquals(floats, abi.readFloatArray(floatAllocation.pointer, floats.size))
            } finally {
                abi.release(floatAllocation)
            }

            val doubles = doubleArrayOf(-9.25, 0.0, 100.5)
            val doubleAllocation = abi.writeDoubleArray(doubles)
            try {
                assertContentEquals(doubles, abi.readDoubleArray(doubleAllocation.pointer, doubles.size))
            } finally {
                abi.release(doubleAllocation)
            }
        } finally {
            instance.close()
        }
    }
}

private val C_ABI_WASM: ByteArray =
    """
        0061736d0100000001290860027f7f017f60017f017f60027f7f0060017e017e60017d017d60
        027c7c017c6000017e6000017f02120104686f73740973756d5f62797465730000030908010203
        04050006070503010001075e09066d656d6f7279020005616c6c6f630001046672656500020865
        63686f5f6936340003086563686f5f6633320004076164645f66363400050973756d5f62797465
        7300060c7061636b65645f776f726c6400070763737472696e6700080a360805004180080b0200
        0b040020000b040020000b070020002001a00b08002000200110000b0a0042858080808080020b
        05004185100b0b1a02004180100b05776f726c64004185100b0868656c6c6f2d63000013046e61
        6d65010c01000973756d5f6279746573
    """.trimIndent()
        .filterNot(Char::isWhitespace)
        .chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
