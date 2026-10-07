package dev.brahmkshatriya.wasmtime.gradle

import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

private const val ABI_ARGUMENT_PREPARE = "__wasmtime_extension_argument_prepare"
private const val ABI_ARGUMENT_BYTE = "__wasmtime_extension_argument_byte"
private const val ABI_START = "__wasmtime_extension_start"
private const val ABI_POLL = "__wasmtime_extension_poll"
private const val ABI_NEXT_WAKE = "__wasmtime_extension_next_wake_millis"
private const val ABI_CANCEL = "__wasmtime_extension_cancel"
private const val ABI_RESULT_LENGTH = "__wasmtime_extension_result_length"
private const val ABI_RESULT_BYTE = "__wasmtime_extension_result_byte"
private const val ABI_ERROR_LENGTH = "__wasmtime_extension_error_length"
private const val ABI_ERROR_BYTE = "__wasmtime_extension_error_byte"
private const val EXTENSION_REMOTE_RESOURCE_METHOD = -2147483646

private data class ContractMethod(
    val name: String,
    val returnType: String,
    val parameterTypes: List<String>,
    val methodId: Int,
    val isSuspend: Boolean,
    val kind: ContractMemberKind,
)

private enum class ContractMemberKind {
    FUNCTION,
    PROPERTY_GETTER,
    PROPERTY_SETTER,
}

/**
 * Generates the guest-side adapter that binds a configured contract interface to the annotated implementation.
 *
 * Registered automatically by [WasmtimeExtensionPlugin]; extension authors configure
 * [WasmtimeExtensionSettings.contractInterface] and [WasmtimeExtensionSettings.implementationClass] instead.
 */
abstract class GenerateWasmtimeExtensionAdapterTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Classpath
    abstract val apiKlibs: ConfigurableFileCollection

    @get:Classpath
    abstract val toolClasspath: ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val implementationClass: Property<String>

    @get:Input
    @get:Optional
    abstract val contractInterface: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val implementation = implementationClass.orNull ?: return clearOutput()
        val contract = contractInterface.orNull ?: return clearOutput()
        val methods = readContractIsolated(contract, apiKlibs.files, toolClasspath, execOperations)
        clearOutput()
        writeGuestAdapter(implementation, contract, methods)
    }

    private fun clearOutput() {
        outputDirectory.get().asFile.deleteRecursively()
    }

    private fun writeGuestAdapter(
        entryPoint: String,
        contract: String,
        methods: List<ContractMethod>,
    ) {
        val branches = methods.joinToString("\n") { method ->
            val arguments = method.parameterTypes.mapIndexed { index, type ->
                "cbor.decodeFromByteArray<$type>(arguments[$index])"
            }.joinToString(", ")
            val callExpression = when (method.kind) {
                ContractMemberKind.FUNCTION -> "implementation.${method.name}($arguments)"
                ContractMemberKind.PROPERTY_GETTER -> "implementation.${method.name}"
                ContractMemberKind.PROPERTY_SETTER -> {
                    require(method.parameterTypes.size == 1) { "property setter must have one parameter" }
                    "implementation.${method.name} = $arguments"
                }
            }
            val decodeArguments = if (method.parameterTypes.isEmpty()) {
                "require(argumentBytes.isEmpty()) { \"method ${method.name} does not accept arguments\" }"
            } else {
                "val arguments = decodeWasmtimeArguments(argumentBytes, ${method.parameterTypes.size})"
            }
            if (method.returnType == "kotlin.Unit") {
                """                ${method.methodId} -> {
                    $decodeArguments
                    $callExpression
                    ByteArray(0)
                }"""
            } else {
                """                ${method.methodId} -> {
                    $decodeArguments
                    cbor.encodeToByteArray<${method.returnType}>($callExpression)
                }"""
            }
        }
        val output = outputDirectory.file(
            "dev/brahmkshatriya/wasmtime/generated/WasmtimeGeneratedExtensionAdapter.kt"
        ).get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            """
            @file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

            package dev.brahmkshatriya.wasmtime.generated

            import dev.brahmkshatriya.wasmtime.extension.WasmtimeExtensionCall
            import dev.brahmkshatriya.wasmtime.extension.WasmtimeExtensionExecution
            import dev.brahmkshatriya.wasmtime.extension.WasmtimeExtensionLifecycle
            import dev.brahmkshatriya.wasmtime.extension.ExtensionRemoteResources
            import kotlinx.coroutines.CancellationException
            import kotlinx.serialization.decodeFromByteArray
            import kotlinx.serialization.encodeToByteArray
            import kotlinx.serialization.cbor.Cbor
            import kotlin.wasm.ExperimentalWasmInterop
            import kotlin.wasm.WasmExport

            private val implementation: $contract = $entryPoint
            private val cbor = Cbor { ignoreUnknownKeys = true }
            private val call = WasmtimeExtensionCall { methodId, argumentBytes ->
                try {
                    val result = when (methodId) {
                    -2147483648 -> {
                        (implementation as? WasmtimeExtensionLifecycle)?.onLoad()
                        ByteArray(0)
                    }
                    -2147483647 -> {
                        try {
                            (implementation as? WasmtimeExtensionLifecycle)?.onUnload()
                        } finally {
                            ExtensionRemoteResources.clear()
                        }
                        ByteArray(0)
                    }
                    $EXTENSION_REMOTE_RESOURCE_METHOD -> ExtensionRemoteResources.dispatch(argumentBytes)
            $branches
                    else -> error("unknown extension method id: ${'$'}methodId")
                    }
                    WasmtimeExtensionExecution.success(result)
                } catch (cause: CancellationException) {
                    WasmtimeExtensionExecution.cancelled(
                        type = cause::class.simpleName ?: "CancellationException",
                        message = cause.message ?: "extension call cancelled",
                    )
                } catch (cause: Throwable) {
                    WasmtimeExtensionExecution.failure(
                        type = cause::class.simpleName ?: "Throwable",
                        message = cause.message ?: "extension call failed",
                    )
                }
            }

            private fun decodeWasmtimeArguments(bytes: ByteArray, expectedCount: Int): List<ByteArray> {
                require(bytes.size >= 4) { "truncated Wasmtime argument frame" }
                var offset = 0
                fun readInt(): Int {
                    require(offset + 4 <= bytes.size) { "truncated Wasmtime argument frame" }
                    return ((bytes[offset].toInt() and 0xff) or
                        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                        ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                        ((bytes[offset + 3].toInt() and 0xff) shl 24)).also { offset += 4 }
                }
                val count = readInt()
                require(count == expectedCount) {
                    "Wasmtime argument count mismatch: expected ${'$'}expectedCount, got ${'$'}count"
                }
                val result = ArrayList<ByteArray>(count)
                repeat(count) {
                    val length = readInt()
                    require(length >= 0 && offset + length <= bytes.size) {
                        "invalid Wasmtime argument length: ${'$'}length"
                    }
                    result += bytes.copyOfRange(offset, offset + length)
                    offset += length
                }
                require(offset == bytes.size) { "trailing bytes in Wasmtime argument frame" }
                return result
            }

            @OptIn(ExperimentalWasmInterop::class)
            @WasmExport("$ABI_ARGUMENT_PREPARE")
            fun wasmtimeExtensionArgumentPrepare(length: Int, unused: Int): Int = call.prepareArguments(length)

            @OptIn(ExperimentalWasmInterop::class)
            @WasmExport("$ABI_ARGUMENT_BYTE")
            fun wasmtimeExtensionArgumentByte(index: Int, value: Int): Int = call.setArgumentByte(index, value)

            @OptIn(ExperimentalWasmInterop::class)
            @WasmExport("$ABI_START")
            fun wasmtimeExtensionStart(methodId: Int, unused: Int): Int = call.start(methodId)

            @OptIn(ExperimentalWasmInterop::class)
            @WasmExport("$ABI_POLL")
            fun wasmtimeExtensionPoll(elapsedMillis: Int, unused: Int): Int = call.poll(elapsedMillis)

            @OptIn(ExperimentalWasmInterop::class)
            @WasmExport("$ABI_NEXT_WAKE")
            fun wasmtimeExtensionNextWakeMillis(unused1: Int, unused2: Int): Int = call.nextWakeMillis()

            @OptIn(ExperimentalWasmInterop::class)
            @WasmExport("$ABI_CANCEL")
            fun wasmtimeExtensionCancel(unused1: Int, unused2: Int): Int = call.cancel()

            @OptIn(ExperimentalWasmInterop::class)
            @WasmExport("$ABI_RESULT_LENGTH")
            fun wasmtimeExtensionResultLength(unused1: Int, unused2: Int): Int = call.resultLength()

            @OptIn(ExperimentalWasmInterop::class)
            @WasmExport("$ABI_RESULT_BYTE")
            fun wasmtimeExtensionResultByte(index: Int, unused: Int): Int = call.resultByte(index)

            @OptIn(ExperimentalWasmInterop::class)
            @WasmExport("$ABI_ERROR_LENGTH")
            fun wasmtimeExtensionErrorLength(unused1: Int, unused2: Int): Int = call.errorLength()

            @OptIn(ExperimentalWasmInterop::class)
            @WasmExport("$ABI_ERROR_BYTE")
            fun wasmtimeExtensionErrorByte(index: Int, unused: Int): Int = call.errorByte(index)

            fun main() = Unit
            """.trimIndent() + "\n"
        )
    }

}

/**
 * Generates the host-side `<Contract>WasmtimeProxy` implementation for a compiled contract KLIB.
 *
 * Registered automatically by [WasmtimeHostPlugin]; consumers normally configure
 * [WasmtimeHostSettings.contractInterface] rather than this task directly.
 */
abstract class GenerateWasmtimeHostProxyTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Classpath
    abstract val apiKlibs: ConfigurableFileCollection

    @get:Classpath
    abstract val toolClasspath: ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val contractInterface: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val contract = contractInterface.orNull ?: return clearOutput()
        val methods = readContractIsolated(contract, apiKlibs.files, toolClasspath, execOperations)
        clearOutput()
        writeHostProxy(contract, methods)
    }

    private fun clearOutput() {
        outputDirectory.get().asFile.deleteRecursively()
    }

    private fun writeHostProxy(contract: String, methods: List<ContractMethod>) {
        val simpleName = contract.substringAfterLast('.')
        val className = "${simpleName}WasmtimeProxy"
        val functionOverrides = methods
            .filter { it.kind == ContractMemberKind.FUNCTION }
            .joinToString("\n\n") { method ->
            val parameters = method.parameterTypes.mapIndexed { index, type -> "p$index: $type" }.joinToString(", ")
            val payload = if (method.parameterTypes.isEmpty()) {
                "ByteArray(0)"
            } else {
                val elements = method.parameterTypes.mapIndexed { index, type ->
                    "cbor.encodeToByteArray<$type>(p$index)"
                }.joinToString(",\n            ")
                """encodeWasmtimeArguments(
            $elements
        )"""
            }
            val suspendModifier = if (method.isSuspend) "suspend " else ""
            val invoke = if (method.isSuspend) "transport.invoke" else "transport.invokeSync"
            if (method.returnType == "kotlin.Unit") {
                """    override ${suspendModifier}fun ${method.name}($parameters): kotlin.Unit {
        val arguments = $payload
        $invoke(${method.methodId}, arguments)
    }"""
            } else {
                """    override ${suspendModifier}fun ${method.name}($parameters): ${method.returnType} {
        val arguments = $payload
        val bytes = $invoke(${method.methodId}, arguments)
        return cbor.decodeFromByteArray<${method.returnType}>(bytes)
    }"""
            }
        }
        val propertyOverrides = methods
            .filter { it.kind != ContractMemberKind.FUNCTION }
            .groupBy(ContractMethod::name)
            .values
            .joinToString("\n\n") { members ->
                val getter = members.singleOrNull { it.kind == ContractMemberKind.PROPERTY_GETTER }
                    ?: throw GradleException("Wasmtime contract property is missing a getter: ${members.first().name}")
                val setter = members.singleOrNull { it.kind == ContractMemberKind.PROPERTY_SETTER }
                val type = getter.returnType
                if (setter == null) {
                    """    override val ${getter.name}: $type
        get() {
            val bytes = transport.invokeSync(${getter.methodId}, ByteArray(0))
            return cbor.decodeFromByteArray<$type>(bytes)
        }"""
                } else {
                    require(setter.parameterTypes.singleOrNull() == type) {
                        "Wasmtime property setter type mismatch for ${getter.name}"
                    }
                    """    override var ${getter.name}: $type
        get() {
            val bytes = transport.invokeSync(${getter.methodId}, ByteArray(0))
            return cbor.decodeFromByteArray<$type>(bytes)
        }
        set(value) {
            val arguments = encodeWasmtimeArguments(cbor.encodeToByteArray<$type>(value))
            transport.invokeSync(${setter.methodId}, arguments)
        }"""
                }
            }
        val overrides = listOf(functionOverrides, propertyOverrides)
            .filter(String::isNotBlank)
            .joinToString("\n\n")
        val output = outputDirectory.file(
            "dev/brahmkshatriya/wasmtime/generated/$className.kt"
        ).get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            """
            @file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

            package dev.brahmkshatriya.wasmtime.generated

            import dev.brahmkshatriya.wasmtime.WasmtimeExtensionTransport
            import kotlinx.serialization.decodeFromByteArray
            import kotlinx.serialization.encodeToByteArray
            import kotlinx.serialization.cbor.Cbor

            @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
            public class $className(
                private val transport: WasmtimeExtensionTransport,
            ) : $contract {
                private val cbor: Cbor = Cbor { ignoreUnknownKeys = true }

            $overrides

                public companion object {
                    public const val CONTRACT: String = "$contract"
                }
            }

            private fun encodeWasmtimeArguments(vararg arguments: ByteArray): ByteArray {
                val size = 4 + arguments.sumOf { 4 + it.size }
                return ByteArray(size).also { output ->
                    var offset = 0
                    fun writeInt(value: Int) {
                        output[offset++] = value.toByte()
                        output[offset++] = (value ushr 8).toByte()
                        output[offset++] = (value ushr 16).toByte()
                        output[offset++] = (value ushr 24).toByte()
                    }
                    writeInt(arguments.size)
                    arguments.forEach { argument ->
                        writeInt(argument.size)
                        argument.copyInto(output, offset)
                        offset += argument.size
                    }
                }
            }
            """.trimIndent() + "\n"
        )
    }

}

private fun readContractIsolated(
    contractFqName: String,
    files: Set<File>,
    toolClasspath: ConfigurableFileCollection,
    execOperations: ExecOperations,
): List<ContractMethod> {
    val stdout = ByteArrayOutputStream()
    val self = File(
        WasmtimeContractAbiToolMain::class.java.protectionDomain.codeSource.location.toURI()
    )
    execOperations.javaexec {
        classpath(toolClasspath)
        classpath(self)
        mainClass.set(WasmtimeContractAbiToolMain::class.java.name)
        args(contractFqName)
        args(files.map(File::getAbsolutePath))
        standardOutput = stdout
        errorOutput = System.err
    }.assertNormalExitValue()
    return stdout.toString(Charsets.UTF_8.name())
        .lineSequence()
        .filter(String::isNotBlank)
        .map { line ->
            val parts = line.split('\t')
            if (parts.size < 5) throw GradleException("Invalid Wasmtime contract ABI tool output: $line")
            ContractMethod(
                name = parts[3],
                returnType = parts[4],
                parameterTypes = parts.drop(5),
                methodId = parts[0].toInt(),
                isSuspend = parts[1].toBooleanStrict(),
                kind = ContractMemberKind.valueOf(parts[2]),
            )
        }
        .toList()
}
