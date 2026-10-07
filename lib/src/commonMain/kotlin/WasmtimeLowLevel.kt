package dev.brahmkshatriya.wasmtime

/** Scalar WebAssembly value types supported by the low-level ABI. */
public enum class WasmValueType {
    I32,
    I64,
    F32,
    F64,
}

/** A scalar value crossing a low-level WebAssembly function boundary. */
public sealed interface WasmValue {
    public val type: WasmValueType

    public data class I32(public val value: Int) : WasmValue {
        override val type: WasmValueType = WasmValueType.I32
    }

    public data class I64(public val value: Long) : WasmValue {
        override val type: WasmValueType = WasmValueType.I64
    }

    public data class F32(public val value: Float) : WasmValue {
        override val type: WasmValueType = WasmValueType.F32
    }

    public data class F64(public val value: Double) : WasmValue {
        override val type: WasmValueType = WasmValueType.F64
    }
}

/** Expected parameter/result types for a low-level Wasm function. */
public data class WasmtimeFunctionType(
    public val parameters: List<WasmValueType> = emptyList(),
    public val results: List<WasmValueType> = emptyList(),
)

/** Memory access that is valid only while a host import is executing. */
public class WasmtimeCallerMemory internal constructor(
    private val readBlock: (String, Int, Int) -> ByteArray,
    private val writeBlock: (String, Int, ByteArray) -> Unit,
    private val sizeBlock: (String) -> Int,
) {
    public fun size(exportName: String = "memory"): Int = sizeBlock(exportName)

    public fun read(offset: Int, length: Int, exportName: String = "memory"): ByteArray {
        require(offset >= 0) { "offset must be >= 0" }
        require(length >= 0) { "length must be >= 0" }
        return readBlock(exportName, offset, length)
    }

    public fun write(offset: Int, bytes: ByteArray, exportName: String = "memory") {
        require(offset >= 0) { "offset must be >= 0" }
        writeBlock(exportName, offset, bytes)
    }
}

/** One invocation of a consumer-defined host import. */
public class WasmtimeHostCall internal constructor(
    public val arguments: List<WasmValue>,
    public val memory: WasmtimeCallerMemory,
)

/** Synchronous host callback used by a consumer-defined Wasm import. */
public fun interface WasmtimeHostCallback {
    public fun invoke(call: WasmtimeHostCall): List<WasmValue>
}

/** One consumer-defined host function import. */
public data class WasmtimeHostFunction(
    public val module: String,
    public val name: String,
    public val type: WasmtimeFunctionType,
    public val callback: WasmtimeHostCallback,
) {
    init {
        require(module.isNotBlank()) { "host import module must not be blank" }
        require(name.isNotBlank()) { "host import name must not be blank" }
        require('\u0000' !in module) { "host import module must not contain NUL" }
        require('\u0000' !in name) { "host import name must not contain NUL" }
    }
}

/** Consumer-defined imports made available while a Wasm module is instantiated. */
public data class WasmtimeImports(
    public val functions: List<WasmtimeHostFunction>,
) {
    init {
        val names = functions.map { it.module to it.name }
        require(names.distinct().size == names.size) { "host import names must be unique" }
    }

    public companion object {
        public val Empty: WasmtimeImports = WasmtimeImports(emptyList())
    }
}

/** Builder for [WasmtimeImports]. */
public class WasmtimeImportsBuilder {
    private val functions = mutableListOf<WasmtimeHostFunction>()

    public fun function(
        module: String,
        name: String,
        parameters: List<WasmValueType> = emptyList(),
        results: List<WasmValueType> = emptyList(),
        callback: WasmtimeHostCallback,
    ) {
        functions += WasmtimeHostFunction(
            module = module,
            name = name,
            type = WasmtimeFunctionType(parameters, results),
            callback = callback,
        )
    }

    internal fun build(): WasmtimeImports = WasmtimeImports(functions.toList())
}

/** Builds consumer-defined host imports for [Wasmtime.load] or [WasmtimeModule.instantiate]. */
public fun wasmtimeImports(block: WasmtimeImportsBuilder.() -> Unit): WasmtimeImports =
    WasmtimeImportsBuilder().apply(block).build()

internal fun validateWasmValues(
    values: List<WasmValue>,
    expected: List<WasmValueType>,
    label: String,
) {
    require(values.size == expected.size) {
        "$label count mismatch: expected ${expected.size}, got ${values.size}"
    }
    values.forEachIndexed { index, value ->
        require(value.type == expected[index]) {
            "$label[$index] type mismatch: expected ${expected[index]}, got ${value.type}"
        }
    }
}

/** Resolved low-level Wasm function. The owning [WasmtimeInstance] controls its lifetime. */
public class WasmtimeFunction internal constructor(
    public val type: WasmtimeFunctionType,
    private val platform: PlatformWasmtimeFunction,
    private val ownerBeginUse: () -> Unit,
    private val ownerEndUse: () -> Unit,
) {
    public fun call(arguments: List<WasmValue> = emptyList()): List<WasmValue> {
        validateWasmValues(arguments, type.parameters, "argument")
        ownerBeginUse()
        try {
            val results = platform.call(arguments)
            validateWasmValues(results, type.results, "result")
            return results
        } finally {
            ownerEndUse()
        }
    }

    public operator fun invoke(vararg arguments: WasmValue): List<WasmValue> =
        call(arguments.toList())

    internal fun closeInternal() = platform.close()
}

/** Exported linear memory owned by a [WasmtimeInstance]. */
public class WasmtimeMemory internal constructor(
    private val platform: PlatformWasmtimeMemory,
    private val ownerBeginUse: () -> Unit,
    private val ownerEndUse: () -> Unit,
) {
    public val size: Int
        get() = use { platform.size() }

    public fun read(offset: Int, length: Int): ByteArray {
        require(offset >= 0) { "offset must be >= 0" }
        require(length >= 0) { "length must be >= 0" }
        return use { platform.read(offset, length) }
    }

    public fun write(offset: Int, bytes: ByteArray) {
        require(offset >= 0) { "offset must be >= 0" }
        use { platform.write(offset, bytes) }
    }

    public fun readUtf8(offset: Int, length: Int): String = read(offset, length).decodeToString()

    public fun writeUtf8(offset: Int, value: String): Int {
        val bytes = value.encodeToByteArray()
        write(offset, bytes)
        return bytes.size
    }

    private inline fun <T> use(block: () -> T): T {
        ownerBeginUse()
        try {
            return block()
        } finally {
            ownerEndUse()
        }
    }
}

internal interface PlatformWasmtimeFunction {
    fun call(arguments: List<WasmValue>): List<WasmValue>
    fun close()
}

internal interface PlatformWasmtimeMemory {
    fun size(): Int
    fun read(offset: Int, length: Int): ByteArray
    fun write(offset: Int, bytes: ByteArray)
}
