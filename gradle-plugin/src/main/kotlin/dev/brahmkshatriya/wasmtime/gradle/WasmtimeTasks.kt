package dev.brahmkshatriya.wasmtime.gradle

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

private val WASM_HEADER = byteArrayOf(0, 97, 115, 109, 1, 0, 0, 0)
private val STRIPPED_CUSTOM_SECTIONS = setOf("name", "producers", "sourceMappingURL")

private fun readUleb(bytes: ByteArray, start: Int): Pair<Int, Int> {
    var cursor = start
    var value = 0
    var shift = 0
    while (true) {
        check(cursor < bytes.size) { "Truncated ULEB128" }
        val byte = bytes[cursor++].toInt() and 0xff
        value = value or ((byte and 0x7f) shl shift)
        if (byte and 0x80 == 0) return value to cursor
        shift += 7
        check(shift < 32) { "Wasm section is too large" }
    }
}

internal fun stripWasmMetadata(source: File, target: File) {
    val bytes = source.readBytes()
    check(bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(WASM_HEADER)) {
        "Not a WebAssembly v1 binary: $source"
    }
    val output = ByteArrayOutputStream(bytes.size)
    output.write(bytes, 0, 8)
    var cursor = 8
    while (cursor < bytes.size) {
        val sectionStart = cursor
        val sectionId = bytes[cursor++].toInt() and 0xff
        val (payloadSize, payloadStart) = readUleb(bytes, cursor)
        val payloadEnd = payloadStart + payloadSize
        check(payloadEnd <= bytes.size) { "Truncated Wasm section in $source" }
        val customName = if (sectionId == 0) {
            val (nameSize, nameStart) = readUleb(bytes, payloadStart)
            val nameEnd = nameStart + nameSize
            if (nameEnd <= payloadEnd) bytes.copyOfRange(nameStart, nameEnd).decodeToString() else null
        } else null
        if (customName !in STRIPPED_CUSTOM_SECTIONS) {
            output.write(bytes, sectionStart, payloadEnd - sectionStart)
        }
        cursor = payloadEnd
    }
    target.parentFile.mkdirs()
    target.writeBytes(output.toByteArray())
}

internal fun runTypePruner(pruner: File, source: File, target: File) {
    target.parentFile.mkdirs()
    val process = ProcessBuilder(pruner.absolutePath, source.absolutePath, target.absolutePath)
        .inheritIO()
        .start()
    if (process.waitFor() != 0) {
        throw GradleException("Wasm type pruner failed for $source")
    }
}

abstract class BuildWasmTypePrunerTask : DefaultTask() {
    @get:Input
    abstract val rustVersion: Property<String>

    @get:OutputFile
    abstract val outputExecutable: RegularFileProperty

    @TaskAction
    fun build() {
        val source = File(temporaryDir, "source").apply {
            deleteRecursively()
            mkdirs()
        }
        fun extract(resource: String, target: File) {
            target.parentFile.mkdirs()
            javaClass.getResourceAsStream(resource)?.use { input ->
                target.outputStream().use(input::copyTo)
            } ?: throw GradleException("Missing Gradle plugin resource: $resource")
        }
        extract("/wasm-type-pruner/Cargo.toml", File(source, "Cargo.toml"))
        extract("/wasm-type-pruner/Cargo.lock", File(source, "Cargo.lock"))
        extract("/wasm-type-pruner/main.rs", File(source, "src/main.rs"))

        val privateDeps = File(project.rootDir, "lib/src/native/.deps")
        val privateCargoHome = File(privateDeps, "cargo")
        val privateRustupHome = File(privateDeps, "rustup")
        val privateCargo = File(privateCargoHome, "bin/cargo")
        val configuredCargo = System.getenv("CARGO")?.takeIf(String::isNotBlank)
        val cargo = configuredCargo ?: privateCargo.takeIf(File::isFile)?.absolutePath ?: "cargo"
        val cargoTarget = File(temporaryDir, "target")

        val builder = ProcessBuilder(
            cargo,
            "build",
            "--release",
            "--locked",
            "--manifest-path",
            File(source, "Cargo.toml").absolutePath,
        ).directory(project.rootDir).inheritIO()
        builder.environment()["CARGO_TARGET_DIR"] = cargoTarget.absolutePath
        if (configuredCargo == null && privateCargo.isFile) {
            builder.environment()["CARGO_HOME"] = privateCargoHome.absolutePath
            builder.environment()["RUSTUP_HOME"] = privateRustupHome.absolutePath
            builder.environment()["RUSTUP_TOOLCHAIN"] = rustVersion.get()
        }
        val result = builder.start().waitFor()
        if (result != 0) throw GradleException("Failed to build wasm-type-pruner")

        val suffix = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) ".exe" else ""
        val built = File(cargoTarget, "release/wasm-type-pruner$suffix")
        if (!built.isFile) throw GradleException("wasm-type-pruner output not found: $built")
        val output = outputExecutable.get().asFile
        output.parentFile.mkdirs()
        built.copyTo(output, overwrite = true)
        output.setExecutable(true)
    }
}

abstract class ExportWasmtimeExtensionTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val extensionKlib: DirectoryProperty

    @get:Classpath
    abstract val externalLibraries: ConfigurableFileCollection

    @get:Classpath
    abstract val compilerClasspath: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val typePruner: RegularFileProperty

    @get:OutputFile
    abstract val outputWasm: RegularFileProperty

    @TaskAction
    fun export() {
        val klib = extensionKlib.get().asFile
        val properties = Properties().apply {
            File(klib, "default/manifest").inputStream().use(::load)
        }
        val uniqueName = properties.getProperty("unique_name")
            ?: throw GradleException("Extension KLIB has no unique_name: $klib")
        val outputName = properties.getProperty("jsOutputName")
            ?: uniqueName.replace(Regex("[^A-Za-z0-9._-]+"), "_")
        val linkedDir = File(temporaryDir, "linked").apply {
            deleteRecursively()
            mkdirs()
        }
        val libraries = externalLibraries.files
            .filter(File::exists)
            .joinToString(File.pathSeparator) { it.absolutePath }
        if (libraries.isBlank()) {
            throw GradleException("Extension compile classpath is empty; compileOnly runtime KLIBs are required")
        }

        execOperations.javaexec {
            classpath(compilerClasspath)
            mainClass.set("org.jetbrains.kotlin.cli.js.KotlinWasmCompiler")
            maxHeapSize = "3g"
            args(
                "-Xinclude=${klib.absolutePath}",
                "-Xir-produce-js",
                "-main", "call",
                "-Xmulti-platform",
                "-ir-output-dir", linkedDir.absolutePath,
                "-source-map",
                "-source-map-embed-sources", "never",
                "-Xir-module-name=$uniqueName",
                "-Xwasm-target=wasm-wasi",
                "-Xir-dce",
                "-Xwasm-included-module-only",
                "-libraries", libraries,
                "-ir-output-name=$outputName",
            )
        }.assertNormalExitValue()

        val linked = File(linkedDir, "$outputName.wasm")
        if (!linked.isFile) throw GradleException("Kotlin compiler did not emit $linked")
        val stripped = File(temporaryDir, "stripped.wasm")
        stripWasmMetadata(linked, stripped)
        runTypePruner(typePruner.get().asFile, stripped, outputWasm.get().asFile)
    }
}

private data class RuntimeKlib(
    val file: File,
    val uniqueName: String,
    val outputName: String,
    val dependencies: Set<String>,
)

abstract class BuildWasmtimeRuntimeTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Classpath
    abstract val runtimeKlibs: ConfigurableFileCollection

    @get:Classpath
    abstract val compilerClasspath: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val typePruner: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun buildRuntime() {
        val output = outputDirectory.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val scratch = File(temporaryDir, "compiled").apply {
            deleteRecursively()
            mkdirs()
        }
        val klibs = runtimeKlibs.files.filter(File::exists)
            .map { file ->
                val properties = readKlibProperties(file)
                val uniqueName = properties.getProperty("unique_name")
                    ?: throw GradleException("KLIB has no unique_name: $file")
                RuntimeKlib(
                    file = file,
                    uniqueName = uniqueName,
                    outputName = properties.getProperty("jsOutputName")
                        ?: uniqueName.replace(Regex("[^A-Za-z0-9._-]+"), "_"),
                    dependencies = properties.getProperty("depends")
                        ?.split(Regex("\\s+"))
                        ?.filter(String::isNotBlank)
                        ?.toSet()
                        .orEmpty(),
                )
            }
        val duplicates = klibs.groupBy(RuntimeKlib::uniqueName).filterValues { it.size > 1 }
        if (duplicates.isNotEmpty()) {
            throw GradleException("Duplicate Wasm runtime KLIB unique names: ${duplicates.keys}")
        }
        val byName = klibs.associateBy(RuntimeKlib::uniqueName)
        val compileOrder = klibs.sortedWith(compareBy<RuntimeKlib>({ it.uniqueName != "kotlin" }, { it.uniqueName }))
        val allLibraries = klibs.joinToString(File.pathSeparator) { it.file.absolutePath }
        val compiled = linkedMapOf<String, File>()

        for (entry in compileOrder) {
            val moduleDir = File(scratch, entry.outputName).apply { mkdirs() }
            val libraries = klibs
                .asSequence()
                .filterNot { it.file == entry.file }
                .joinToString(File.pathSeparator) { it.file.absolutePath }
            execOperations.javaexec {
                classpath(compilerClasspath)
                mainClass.set("org.jetbrains.kotlin.cli.js.KotlinWasmCompiler")
                maxHeapSize = "3g"
                args(
                    "-Xinclude=${entry.file.absolutePath}",
                    "-Xir-produce-js",
                    "-main", "call",
                    "-Xmulti-platform",
                    "-ir-output-dir", moduleDir.absolutePath,
                    "-source-map",
                    "-source-map-embed-sources", "never",
                    "-Xir-module-name=${entry.uniqueName}",
                    "-Xwasm-target=wasm-wasi",
                    "-Xir-dce",
                    "-Xwasm-included-module-only",
                    "-libraries", libraries.ifBlank { allLibraries },
                    "-ir-output-name=${entry.outputName}",
                )
            }.assertNormalExitValue()
            val wasm = File(moduleDir, "${entry.outputName}.wasm")
            if (!wasm.isFile) throw GradleException("Kotlin compiler did not emit $wasm")
            compiled[entry.uniqueName] = wasm
        }

        val pruner = typePruner.get().asFile
        val processed = linkedMapOf<String, File>()
        val actualDependencies = linkedMapOf<String, Set<String>>()
        for (entry in compileOrder) {
            val safe = entry.uniqueName.replace(Regex("[^A-Za-z0-9._-]+"), "_")
            val stripped = File(temporaryDir, "stripped-$safe.wasm")
            val pruned = File(temporaryDir, "pruned-$safe.wasm")
            stripWasmMetadata(checkNotNull(compiled[entry.uniqueName]), stripped)
            runTypePruner(pruner, stripped, pruned)
            processed[entry.uniqueName] = pruned
            actualDependencies[entry.uniqueName] = readImportModules(pruner, pruned)
                .mapNotNull { module ->
                    if (module.startsWith("<") && module.endsWith(">")) {
                        module.substring(1, module.length - 1).takeIf(byName::containsKey)
                    } else null
                }
                .filterNot { it == entry.uniqueName }
                .toSet()
        }
        val ordered = topologicalOrder(klibs, actualDependencies)

        val manifest = mutableListOf<String>()
        var fileIndex = 0
        for (entry in ordered) {
            val safe = entry.uniqueName.replace(Regex("[^A-Za-z0-9._-]+"), "_")
            val fileName = "%02d-%s.wasm".format(fileIndex++, safe)
            checkNotNull(processed[entry.uniqueName]).copyTo(File(output, fileName), overwrite = true)
            manifest += "<${entry.uniqueName}>\t$fileName"

            if (entry.uniqueName == "kotlin") {
                val bridgeName = "%02d-host-memory-bridge.wasm".format(fileIndex++)
                val bridge = File(temporaryDir, bridgeName)
                javaClass.getResourceAsStream("/wasmtime/host-memory-bridge.wasm")?.use { input ->
                    bridge.outputStream().use(input::copyTo)
                } ?: throw GradleException("Missing host-memory-bridge.wasm Gradle plugin resource")
                val strippedBridge = File(temporaryDir, "stripped-$bridgeName")
                stripWasmMetadata(bridge, strippedBridge)
                runTypePruner(pruner, strippedBridge, File(output, bridgeName))
                manifest += "wasi_snapshot_preview1\t$bridgeName"
                manifest += "ktor_wasi\t$bridgeName"
            }
        }
        File(output, "runtime.tsv").writeText(manifest.joinToString("\n", postfix = "\n"))
    }

    private fun topologicalOrder(
        entries: List<RuntimeKlib>,
        dependencies: Map<String, Set<String>>,
    ): List<RuntimeKlib> {
        val remaining = entries.associateBy(RuntimeKlib::uniqueName).toMutableMap()
        val emitted = linkedSetOf<String>()
        val result = mutableListOf<RuntimeKlib>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.values
                .filter { entry -> dependencies[entry.uniqueName].orEmpty().all(emitted::contains) }
                .sortedWith(compareBy<RuntimeKlib>({ it.uniqueName != "kotlin" }, { it.uniqueName }))
            if (ready.isEmpty()) {
                val unresolved = remaining.keys.associateWith { name ->
                    dependencies[name].orEmpty().filter { it in remaining.keys }
                }
                throw GradleException("Cyclic/unresolved Wasm runtime imports: $unresolved")
            }
            for (entry in ready) {
                remaining.remove(entry.uniqueName)
                emitted += entry.uniqueName
                result += entry
            }
        }
        return result
    }

    private fun readImportModules(pruner: File, wasm: File): Set<String> {
        val process = ProcessBuilder(pruner.absolutePath, "imports", wasm.absolutePath)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        val modules = process.inputStream.bufferedReader().use { reader ->
            reader.readLines().filter(String::isNotBlank).toSet()
        }
        if (process.waitFor() != 0) {
            throw GradleException("Failed to inspect Wasm imports for $wasm")
        }
        return modules
    }

    private fun readKlibProperties(file: File): Properties = Properties().apply {
        if (file.isDirectory) {
            File(file, "default/manifest").inputStream().use(::load)
        } else {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("default/manifest")
                    ?: throw GradleException("KLIB has no default/manifest: $file")
                zip.getInputStream(entry).use(::load)
            }
        }
    }
}
