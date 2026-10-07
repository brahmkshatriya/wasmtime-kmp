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

private data class ContractMethod(
    val name: String,
    val returnType: String,
    val parameterTypes: List<String>,
    val methodId: Int,
)

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
                "json.decodeFromJsonElement<$type>(arguments[$index])"
            }.joinToString(", ")
            val callExpression = "implementation.${method.name}($arguments)"
            if (method.returnType == "kotlin.Unit") {
                """                ${method.methodId} -> {
                    $callExpression
                    ByteArray(0)
                }"""
            } else {
                """                ${method.methodId} -> json.encodeToString<${method.returnType}>(
                    $callExpression
                ).encodeToByteArray()"""
            }
        }
        val output = outputDirectory.file(
            "dev/brahmkshatriya/wasmtime/generated/WasmtimeGeneratedExtensionAdapter.kt"
        ).get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            """
            package dev.brahmkshatriya.wasmtime.generated

            import dev.brahmkshatriya.wasmtime.extension.WasmtimeExtensionCall
            import dev.brahmkshatriya.wasmtime.extension.WasmtimeExtensionExecution
            import dev.brahmkshatriya.wasmtime.extension.WasmtimeExtensionLifecycle
            import kotlinx.coroutines.CancellationException
            import kotlinx.serialization.encodeToString
            import kotlinx.serialization.json.Json
            import kotlinx.serialization.json.decodeFromJsonElement
            import kotlinx.serialization.json.jsonArray
            import kotlin.wasm.ExperimentalWasmInterop
            import kotlin.wasm.WasmExport

            private val implementation: $contract = $entryPoint
            private val json = Json { ignoreUnknownKeys = true }
            private val call = WasmtimeExtensionCall { methodId, argumentBytes ->
                val arguments = if (argumentBytes.isEmpty()) {
                    emptyList()
                } else {
                    json.parseToJsonElement(argumentBytes.decodeToString()).jsonArray
                }
                try {
                    val result = when (methodId) {
                    -2147483648 -> {
                        (implementation as? WasmtimeExtensionLifecycle)?.onLoad()
                        ByteArray(0)
                    }
                    -2147483647 -> {
                        (implementation as? WasmtimeExtensionLifecycle)?.onUnload()
                        ByteArray(0)
                    }
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
        writeHostProxy(contract, methods)
    }

    private fun clearOutput() {
        outputDirectory.get().asFile.deleteRecursively()
    }

    private fun writeHostProxy(contract: String, methods: List<ContractMethod>) {
        val simpleName = contract.substringAfterLast('.')
        val className = "${simpleName}WasmtimeProxy"
        val overrides = methods.joinToString("\n\n") { method ->
            val parameters = method.parameterTypes.mapIndexed { index, type -> "p$index: $type" }.joinToString(", ")
            val payload = if (method.parameterTypes.isEmpty()) {
                "ByteArray(0)"
            } else {
                val elements = method.parameterTypes.mapIndexed { index, type ->
                    "add(json.encodeToJsonElement<$type>(p$index))"
                }.joinToString("\n            ")
                """buildJsonArray {
            $elements
        }.toString().encodeToByteArray()"""
            }
            if (method.returnType == "kotlin.Unit") {
                """    override suspend fun ${method.name}($parameters): kotlin.Unit {
        val arguments = $payload
        transport.invoke(${method.methodId}, arguments)
    }"""
            } else {
                """    override suspend fun ${method.name}($parameters): ${method.returnType} {
        val arguments = $payload
        val bytes = transport.invoke(${method.methodId}, arguments)
        return json.decodeFromString<${method.returnType}>(bytes.decodeToString())
    }"""
            }
        }
        val output = outputDirectory.file(
            "dev/brahmkshatriya/wasmtime/generated/$className.kt"
        ).get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            """
            package dev.brahmkshatriya.wasmtime.generated

            import dev.brahmkshatriya.wasmtime.WasmtimeExtensionTransport
            import kotlinx.serialization.decodeFromString
            import kotlinx.serialization.json.Json
            import kotlinx.serialization.json.buildJsonArray
            import kotlinx.serialization.json.encodeToJsonElement

            @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
            public class $className(
                private val transport: WasmtimeExtensionTransport,
                private val json: Json = Json { ignoreUnknownKeys = true },
            ) : $contract {
            $overrides

                public companion object {
                    public const val CONTRACT: String = "$contract"
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
            if (parts.size < 3) throw GradleException("Invalid Wasmtime contract ABI tool output: $line")
            ContractMethod(
                name = parts[1],
                returnType = parts[2],
                parameterTypes = parts.drop(3),
                methodId = parts[0].toInt(),
            )
        }
        .toList()
}
