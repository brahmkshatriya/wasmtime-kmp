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
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens

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
    val methodId: Int,
)

private data class SourceToken(val type: Any, val text: String)

abstract class GenerateWasmtimeExtensionAdapterTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kotlinSources: ConfigurableFileCollection

    @get:Classpath
    abstract val apiKlibs: ConfigurableFileCollection

    @get:Classpath
    abstract val toolClasspath: ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val entryPointAnnotation: Property<String>

    @get:Input
    @get:Optional
    abstract val contractInterface: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val annotation = entryPointAnnotation.orNull ?: return clearOutput()
        val contract = contractInterface.orNull ?: return clearOutput()
        val entryPoint = discoverAnnotatedObject(annotation)
        val methods = readContractIsolated(contract, apiKlibs.files, toolClasspath, execOperations)
        writeGuestAdapter(entryPoint, contract, methods)
    }

    private fun clearOutput() {
        outputDirectory.get().asFile.deleteRecursively()
    }

    private fun discoverAnnotatedObject(annotationFqName: String): String {
        val candidates = kotlinSources.files
            .asSequence()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { source -> findAnnotatedObjects(source.readText(), annotationFqName).asSequence() }
            .distinct()
            .toList()
        if (candidates.size != 1) {
            throw GradleException(
                "Expected exactly one @$annotationFqName top-level object, found ${candidates.size}: $candidates"
            )
        }
        return candidates.single()
    }

    private fun writeGuestAdapter(
        entryPoint: String,
        contract: String,
        methods: List<ContractMethod>,
    ) {
        val branches = methods.joinToString("\n") { method ->
            if (method.returnType == "kotlin.Unit") {
                """                ${method.methodId} -> {
                    implementation.${method.name}()
                    ByteArray(0)
                }"""
            } else {
                """                ${method.methodId} -> json.encodeToString<${method.returnType}>(
                    implementation.${method.name}()
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
            import kotlinx.serialization.encodeToString
            import kotlinx.serialization.json.Json
            import kotlin.wasm.ExperimentalWasmInterop
            import kotlin.wasm.WasmExport

            private val implementation: $contract = $entryPoint
            private val json = Json { ignoreUnknownKeys = true }
            private val call = WasmtimeExtensionCall { methodId ->
                when (methodId) {
            $branches
                    else -> error("unknown extension method id: ${'$'}methodId")
                }
            }

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
            if (method.returnType == "kotlin.Unit") {
                """    override suspend fun ${method.name}(): kotlin.Unit {
        transport.invoke(${method.methodId})
    }"""
            } else {
                """    override suspend fun ${method.name}(): ${method.returnType} {
        val bytes = transport.invoke(${method.methodId})
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

            public class $className(
                private val transport: WasmtimeExtensionTransport,
                private val json: Json = Json { ignoreUnknownKeys = true },
            ) : $contract {
            $overrides
            }
            """.trimIndent() + "\n"
        )
    }
}

private fun findAnnotatedObjects(source: String, annotationFqName: String): List<String> {
    val packageName = Regex("(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)")
        .find(source)?.groupValues?.get(1).orEmpty()
    val imports = Regex(
        "(?m)^\\s*import\\s+([A-Za-z_][A-Za-z0-9_.]*)(?:\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*))?\\s*$"
    ).findAll(source).associate { match ->
        val fqName = match.groupValues[1]
        val alias = match.groupValues[2].ifBlank { fqName.substringAfterLast('.') }
        alias to fqName
    }

    val lexer = KotlinLexer()
    lexer.start(source)
    val tokens = buildList {
        while (lexer.tokenType != null) {
            val type = lexer.tokenType!!
            if (type != KtTokens.WHITE_SPACE &&
                type != KtTokens.EOL_COMMENT &&
                type != KtTokens.BLOCK_COMMENT
            ) add(SourceToken(type, lexer.tokenText.orEmpty()))
            lexer.advance()
        }
    }

    fun annotationMatches(raw: String): Boolean =
        raw == annotationFqName ||
            imports[raw] == annotationFqName ||
            (raw == annotationFqName.substringAfterLast('.') &&
                packageName == annotationFqName.substringBeforeLast('.'))

    val result = mutableListOf<String>()
    var depth = 0
    var pendingEntry = false
    var index = 0
    while (index < tokens.size) {
        val token = tokens[index]
        when (token.type) {
            KtTokens.LBRACE -> depth++
            KtTokens.RBRACE -> depth--
            KtTokens.AT -> if (depth == 0) {
                var cursor = index + 1
                val name = StringBuilder()
                while (cursor < tokens.size) {
                    val next = tokens[cursor]
                    if (next.type == KtTokens.IDENTIFIER || next.type == KtTokens.DOT) {
                        name.append(next.text)
                        cursor++
                    } else break
                }
                if (annotationMatches(name.toString())) pendingEntry = true
                index = cursor - 1
            }
            KtTokens.OBJECT_KEYWORD -> if (depth == 0) {
                if (pendingEntry) {
                    val name = tokens.drop(index + 1)
                        .firstOrNull { it.type == KtTokens.IDENTIFIER }
                        ?.text
                        ?: throw GradleException("@$annotationFqName object must have a name")
                    result += if (packageName.isBlank()) name else "$packageName.$name"
                }
                pendingEntry = false
            }
            KtTokens.CLASS_KEYWORD,
            KtTokens.INTERFACE_KEYWORD,
            KtTokens.FUN_KEYWORD,
            KtTokens.VAL_KEYWORD,
            KtTokens.VAR_KEYWORD,
            KtTokens.TYPE_ALIAS_KEYWORD -> if (depth == 0) pendingEntry = false
        }
        index++
    }
    return result
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
            val parts = line.split('\t', limit = 3)
            if (parts.size != 3) throw GradleException("Invalid Wasmtime contract ABI tool output: $line")
            ContractMethod(parts[1], parts[2], parts[0].toInt())
        }
        .toList()
}
