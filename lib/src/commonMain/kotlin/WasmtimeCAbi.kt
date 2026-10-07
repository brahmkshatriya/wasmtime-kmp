package dev.brahmkshatriya.wasmtime

public data class WasmtimePointerLength(
    public val pointer: Int,
    public val length: Int,
) {
    init {
        require(pointer >= 0) { "pointer must be >= 0" }
        require(length >= 0) { "length must be >= 0" }
    }

    public fun arguments(): List<WasmValue> =
        listOf(WasmValue.I32(pointer), WasmValue.I32(length))
}

public class WasmtimeCAllocation internal constructor(
    public val pointer: Int,
    public val length: Int,
    public val allocatedBytes: Int,
) {
    public val region: WasmtimePointerLength
        get() = WasmtimePointerLength(pointer, length)

    public fun arguments(): List<WasmValue> = region.arguments()
}

public sealed interface WasmtimeCDeallocator {
    public data object None : WasmtimeCDeallocator

    public data class Pointer(
        public val exportName: String = "free",
    ) : WasmtimeCDeallocator

    public data class PointerLength(
        public val exportName: String = "free",
    ) : WasmtimeCDeallocator
}

public enum class WasmtimePointerLengthPacking {
    PointerHighLengthLow,
    LengthHighPointerLow,
}

public data class WasmtimeCAbiConfig(
    public val memoryExport: String = "memory",
    public val allocatorExport: String? = "alloc",
    public val deallocator: WasmtimeCDeallocator = WasmtimeCDeallocator.None,
    public val maxCStringBytes: Int = 1024 * 1024,
) {
    init {
        require(memoryExport.isNotBlank()) { "memoryExport must not be blank" }
        allocatorExport?.let {
            require(it.isNotBlank()) { "allocatorExport must not be blank" }
        }
        require(maxCStringBytes > 0) { "maxCStringBytes must be > 0" }
    }
}

public class WasmtimeCAbi internal constructor(
    private val instance: WasmtimeInstance,
    public val config: WasmtimeCAbiConfig,
) {
    public val memory: WasmtimeMemory
        get() = instance.memory(config.memoryExport)

    public fun allocate(length: Int): WasmtimeCAllocation {
        require(length >= 0) { "length must be >= 0" }
        return allocateExact(
            logicalLength = length,
            allocatedBytes = maxOf(1, length),
        )
    }

    public fun writeBytes(bytes: ByteArray): WasmtimeCAllocation =
        allocate(bytes.size).also { allocation ->
            if (bytes.isNotEmpty()) {
                memory.write(allocation.pointer, bytes)
            }
        }

    public fun writeUtf8(value: String): WasmtimeCAllocation =
        writeBytes(value.encodeToByteArray())

    public fun writeCString(value: String): WasmtimeCAllocation {
        require('\u0000' !in value) {
            "C strings must not contain embedded NUL characters"
        }
        val bytes = value.encodeToByteArray()
        val allocation = allocateExact(
            logicalLength = bytes.size,
            allocatedBytes = bytes.size + 1,
        )
        if (bytes.isNotEmpty()) {
            memory.write(allocation.pointer, bytes)
        }
        memory.write(allocation.pointer + bytes.size, byteArrayOf(0))
        return allocation
    }

    public fun readBytes(region: WasmtimePointerLength): ByteArray =
        memory.read(region.pointer, region.length)

    public fun readBytes(pointer: Int, length: Int): ByteArray =
        readBytes(WasmtimePointerLength(pointer, length))

    public fun readUtf8(region: WasmtimePointerLength): String =
        memory.readUtf8(region.pointer, region.length)

    public fun readUtf8(pointer: Int, length: Int): String =
        readUtf8(WasmtimePointerLength(pointer, length))

    public fun readCString(
        pointer: Int,
        maxBytes: Int = config.maxCStringBytes,
    ): String = readCStringBytes(
        pointer = pointer,
        maxBytes = maxBytes,
        memorySize = memory.size,
        read = memory::read,
    ).decodeToString()

    public fun readNullableCString(
        pointer: Int,
        maxBytes: Int = config.maxCStringBytes,
    ): String? =
        if (pointer == 0) null else readCString(pointer, maxBytes)

    public fun release(allocation: WasmtimeCAllocation) {
        when (val deallocator = config.deallocator) {
            WasmtimeCDeallocator.None -> Unit

            is WasmtimeCDeallocator.Pointer -> {
                instance.call(
                    exportName = deallocator.exportName,
                    type = FREE_POINTER_TYPE,
                    arguments = listOf(
                        WasmValue.I32(allocation.pointer),
                    ),
                )
            }

            is WasmtimeCDeallocator.PointerLength -> {
                instance.call(
                    exportName = deallocator.exportName,
                    type = FREE_POINTER_LENGTH_TYPE,
                    arguments = listOf(
                        WasmValue.I32(allocation.pointer),
                        WasmValue.I32(allocation.allocatedBytes),
                    ),
                )
            }
        }
    }

    public fun <T> withBytes(
        bytes: ByteArray,
        block: (WasmtimeCAllocation) -> T,
    ): T = withAllocation(writeBytes(bytes), block)

    public fun <T> withUtf8(
        value: String,
        block: (WasmtimeCAllocation) -> T,
    ): T = withAllocation(writeUtf8(value), block)

    public fun <T> withCString(
        value: String,
        block: (WasmtimeCAllocation) -> T,
    ): T = withAllocation(writeCString(value), block)

    public fun nullableRegion(
        allocation: WasmtimeCAllocation?,
    ): WasmtimePointerLength =
        allocation?.region ?: WasmtimePointerLength(0, 0)

    public fun pack(
        region: WasmtimePointerLength,
        packing: WasmtimePointerLengthPacking =
            WasmtimePointerLengthPacking.PointerHighLengthLow,
    ): Long {
        val pointer = region.pointer.toUInt().toULong()
        val length = region.length.toUInt().toULong()
        val bits = when (packing) {
            WasmtimePointerLengthPacking.PointerHighLengthLow ->
                (pointer shl 32) or length

            WasmtimePointerLengthPacking.LengthHighPointerLow ->
                (length shl 32) or pointer
        }
        return bits.toLong()
    }

    public fun pack(
        allocation: WasmtimeCAllocation,
        packing: WasmtimePointerLengthPacking =
            WasmtimePointerLengthPacking.PointerHighLengthLow,
    ): Long = pack(allocation.region, packing)

    public fun unpack(
        packed: Long,
        packing: WasmtimePointerLengthPacking =
            WasmtimePointerLengthPacking.PointerHighLengthLow,
    ): WasmtimePointerLength {
        val bits = packed.toULong()
        val high = (bits shr 32).toUInt()
        val low = bits.toUInt()
        val pair = when (packing) {
            WasmtimePointerLengthPacking.PointerHighLengthLow ->
                high to low

            WasmtimePointerLengthPacking.LengthHighPointerLow ->
                low to high
        }
        val pointer = pair.first
        val length = pair.second

        require(pointer <= Int.MAX_VALUE.toUInt()) {
            "Packed pointer does not fit the host Int memory API: $pointer"
        }
        require(length <= Int.MAX_VALUE.toUInt()) {
            "Packed length does not fit the host Int memory API: $length"
        }
        return WasmtimePointerLength(
            pointer = pointer.toInt(),
            length = length.toInt(),
        )
    }

    public fun unpack(
        value: WasmValue.I64,
        packing: WasmtimePointerLengthPacking =
            WasmtimePointerLengthPacking.PointerHighLengthLow,
    ): WasmtimePointerLength =
        unpack(value.value, packing)

    public fun readPackedBytes(
        packed: Long,
        packing: WasmtimePointerLengthPacking =
            WasmtimePointerLengthPacking.PointerHighLengthLow,
    ): ByteArray =
        readBytes(unpack(packed, packing))

    public fun readPackedUtf8(
        packed: Long,
        packing: WasmtimePointerLengthPacking =
            WasmtimePointerLengthPacking.PointerHighLengthLow,
    ): String =
        readUtf8(unpack(packed, packing))

    public fun readNullablePackedUtf8(
        packed: Long,
        packing: WasmtimePointerLengthPacking =
            WasmtimePointerLengthPacking.PointerHighLengthLow,
    ): String? =
        if (packed == 0L) null else readPackedUtf8(packed, packing)

    public fun writeIntArray(
        values: IntArray,
    ): WasmtimeCAllocation =
        writeBytes(values.toLittleEndianBytes())

    public fun readIntArray(
        pointer: Int,
        count: Int,
    ): IntArray =
        readBytes(pointer, checkedByteSize(count, 4))
            .toIntArrayLittleEndian()

    public fun writeLongArray(
        values: LongArray,
    ): WasmtimeCAllocation =
        writeBytes(values.toLittleEndianBytes())

    public fun readLongArray(
        pointer: Int,
        count: Int,
    ): LongArray =
        readBytes(pointer, checkedByteSize(count, 8))
            .toLongArrayLittleEndian()

    public fun writeFloatArray(
        values: FloatArray,
    ): WasmtimeCAllocation =
        writeIntArray(
            IntArray(values.size) { index ->
                values[index].toBits()
            },
        )

    public fun readFloatArray(
        pointer: Int,
        count: Int,
    ): FloatArray {
        val bits = readIntArray(pointer, count)
        return FloatArray(bits.size) { index ->
            Float.fromBits(bits[index])
        }
    }

    public fun writeDoubleArray(
        values: DoubleArray,
    ): WasmtimeCAllocation =
        writeLongArray(
            LongArray(values.size) { index ->
                values[index].toBits()
            },
        )

    public fun readDoubleArray(
        pointer: Int,
        count: Int,
    ): DoubleArray {
        val bits = readLongArray(pointer, count)
        return DoubleArray(bits.size) { index ->
            Double.fromBits(bits[index])
        }
    }

    private fun allocateExact(
        logicalLength: Int,
        allocatedBytes: Int,
    ): WasmtimeCAllocation {
        require(logicalLength >= 0)
        require(allocatedBytes > 0)

        val allocator = requireNotNull(config.allocatorExport) {
            "No allocator export is configured"
        }
        val pointer = (
            instance.call(
                exportName = allocator,
                type = ALLOCATOR_TYPE,
                arguments = listOf(
                    WasmValue.I32(allocatedBytes),
                ),
            ).single() as WasmValue.I32
        ).value

        require(pointer >= 0) {
            "Allocator returned a pointer that does not fit the host Int memory API: $pointer"
        }

        val memorySize = memory.size
        val end = pointer.toLong() + allocatedBytes.toLong()
        require(end <= memorySize.toLong()) {
            "Allocator returned an out-of-bounds range: pointer=$pointer length=$allocatedBytes memory=$memorySize"
        }

        return WasmtimeCAllocation(
            pointer = pointer,
            length = logicalLength,
            allocatedBytes = allocatedBytes,
        )
    }

    private fun <T> withAllocation(
        allocation: WasmtimeCAllocation,
        block: (WasmtimeCAllocation) -> T,
    ): T {
        try {
            return block(allocation)
        } finally {
            release(allocation)
        }
    }
}

public fun WasmtimeInstance.cAbi(
    config: WasmtimeCAbiConfig = WasmtimeCAbiConfig(),
): WasmtimeCAbi =
    WasmtimeCAbi(this, config)

public fun WasmtimeCallerMemory.readUtf8(
    pointer: Int,
    length: Int,
    exportName: String = "memory",
): String =
    read(pointer, length, exportName).decodeToString()

public fun WasmtimeCallerMemory.writeUtf8(
    pointer: Int,
    value: String,
    exportName: String = "memory",
): Int {
    val bytes = value.encodeToByteArray()
    write(pointer, bytes, exportName)
    return bytes.size
}

public fun WasmtimeCallerMemory.readCString(
    pointer: Int,
    maxBytes: Int = 1024 * 1024,
    exportName: String = "memory",
): String =
    readCStringBytes(
        pointer = pointer,
        maxBytes = maxBytes,
        memorySize = size(exportName),
        read = { offset, length ->
            read(offset, length, exportName)
        },
    ).decodeToString()

public fun WasmtimeMemory.readCString(
    pointer: Int,
    maxBytes: Int = 1024 * 1024,
): String =
    readCStringBytes(
        pointer = pointer,
        maxBytes = maxBytes,
        memorySize = size,
        read = ::read,
    ).decodeToString()

private fun readCStringBytes(
    pointer: Int,
    maxBytes: Int,
    memorySize: Int,
    read: (Int, Int) -> ByteArray,
): ByteArray {
    require(pointer >= 0) {
        "pointer must be >= 0"
    }
    require(maxBytes > 0) {
        "maxBytes must be > 0"
    }
    require(pointer < memorySize) {
        "pointer is outside Wasm memory"
    }

    val available = minOf(
        maxBytes,
        memorySize - pointer,
    )
    val output = ByteArray(available)
    var written = 0

    while (written < available) {
        val count = minOf(
            4096,
            available - written,
        )
        val chunk = read(
            pointer + written,
            count,
        )
        for (index in chunk.indices) {
            if (chunk[index] == 0.toByte()) {
                return output.copyOf(
                    written + index,
                )
            }
            output[written + index] =
                chunk[index]
        }
        written += chunk.size
    }

    error(
        "C string is not NUL-terminated within $maxBytes bytes",
    )
}

private fun checkedByteSize(
    count: Int,
    elementBytes: Int,
): Int {
    require(count >= 0) {
        "count must be >= 0"
    }
    val bytes =
        count.toLong() * elementBytes.toLong()
    require(bytes <= Int.MAX_VALUE) {
        "array byte size is too large"
    }
    return bytes.toInt()
}

private fun IntArray.toLittleEndianBytes(): ByteArray =
    ByteArray(
        checkedByteSize(size, 4),
    ).also { output ->
        forEachIndexed { index, value ->
            val offset = index * 4
            output[offset] =
                value.toByte()
            output[offset + 1] =
                (value ushr 8).toByte()
            output[offset + 2] =
                (value ushr 16).toByte()
            output[offset + 3] =
                (value ushr 24).toByte()
        }
    }

private fun ByteArray.toIntArrayLittleEndian(): IntArray {
    require(size % 4 == 0) {
        "byte array size must be divisible by 4"
    }
    return IntArray(size / 4) { index ->
        val offset = index * 4
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)
    }
}

private fun LongArray.toLittleEndianBytes(): ByteArray =
    ByteArray(
        checkedByteSize(size, 8),
    ).also { output ->
        forEachIndexed { index, value ->
            val offset = index * 8
            repeat(8) { byteIndex ->
                output[offset + byteIndex] =
                    (value ushr (byteIndex * 8)).toByte()
            }
        }
    }

private fun ByteArray.toLongArrayLittleEndian(): LongArray {
    require(size % 8 == 0) {
        "byte array size must be divisible by 8"
    }
    return LongArray(size / 8) { index ->
        val offset = index * 8
        var value = 0L
        repeat(8) { byteIndex ->
            value = value or
                (
                    (this[offset + byteIndex].toLong() and 0xffL) shl
                        (byteIndex * 8)
                )
        }
        value
    }
}

private val ALLOCATOR_TYPE =
    WasmtimeFunctionType(
        parameters = listOf(
            WasmValueType.I32,
        ),
        results = listOf(
            WasmValueType.I32,
        ),
    )

private val FREE_POINTER_TYPE =
    WasmtimeFunctionType(
        parameters = listOf(
            WasmValueType.I32,
        ),
    )

private val FREE_POINTER_LENGTH_TYPE =
    WasmtimeFunctionType(
        parameters = listOf(
            WasmValueType.I32,
            WasmValueType.I32,
        ),
    )
