package dev.brahmkshatriya.wasmtime.gradle

import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.file.RegularFile
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.Task
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import javax.inject.Inject

private val SDK_VERSION: String =
    WasmtimeExtensionPlugin::class.java.`package`.implementationVersion ?: "0.1.0-SNAPSHOT"
private val GUEST_RUNTIME_COORDINATE: String
    get() = "dev.brahmkshatriya.wasmtime:guest-runtime:$SDK_VERSION"

/** A reusable dependency set that consumer modules or convention plugins can share between host and extensions. */
public open class WasmtimeDependencySet internal constructor(
    private val setName: String,
) : Named {
    private val values = mutableListOf<Any>()
    private val subscribers = mutableListOf<(Any) -> Unit>()

    override fun getName(): String = setName

    public fun add(dependency: Any) {
        values += dependency
        subscribers.forEach { it(dependency) }
    }

    internal fun subscribe(consumer: (Any) -> Unit) {
        values.forEach(consumer)
        subscribers += consumer
    }
}

/** Generic Wasmtime Gradle settings shared by the host and extension plugins. */
public open class WasmtimeSettings @Inject constructor(
    private val project: Project,
    objects: ObjectFactory,
) {
    public val extensionApis: NamedDomainObjectContainer<WasmtimeDependencySet> =
        objects.domainObjectContainer(WasmtimeDependencySet::class.java) { name ->
            WasmtimeDependencySet(name)
        }

    public fun extensionApi(name: String, configure: Action<in WasmtimeDependencySet>) {
        configure.execute(extensionApis.maybeCreate(name))
    }
}

/** Dependency collector used by the typed host/extension DSLs. */
public class WasmtimeDependencyCollector internal constructor(
    private val project: Project,
    private val configuration: Configuration,
    private val settings: WasmtimeSettings,
) {
    public fun add(dependency: Any) {
        project.dependencies.add(configuration.name, dependency)
    }

    public fun from(set: WasmtimeDependencySet) {
        set.subscribe(::add)
    }

    public fun useExtensionApi(name: String) {
        from(settings.extensionApis.maybeCreate(name))
    }
}

public open class WasmtimeExtensionSettings internal constructor(
    private val project: Project,
    objects: ObjectFactory,
    compileOnlyConfiguration: Configuration,
    settings: WasmtimeSettings,
) {
    public val outputFileName: Property<String> = objects.property(String::class.java)
    public val entryPointAnnotation: Property<String> = objects.property(String::class.java)
    public val contractInterface: Property<String> = objects.property(String::class.java)
    public val generatedSourceDirectories: ConfigurableFileCollection = objects.fileCollection()
    private val compileOnly = WasmtimeDependencyCollector(project, compileOnlyConfiguration, settings)

    public fun compileOnlyDependencies(configure: Action<in WasmtimeDependencyCollector>) {
        configure.execute(compileOnly)
    }

    /** Registers generated Kotlin source and the task that produces it. */
    public fun generatedSource(directory: Any, builtBy: Any) {
        generatedSourceDirectories.from(directory)
        generatedSourceDirectories.builtBy(builtBy)
    }
}

public open class WasmtimeHostSettings internal constructor(
    project: Project,
    runtimeConfiguration: Configuration,
    settings: WasmtimeSettings,
    objects: ObjectFactory,
) {
    public val contractInterface: Property<String> = objects.property(String::class.java)
    private val runtime = WasmtimeDependencyCollector(project, runtimeConfiguration, settings)

    public fun runtimeDependencies(configure: Action<in WasmtimeDependencyCollector>) {
        configure.execute(runtime)
    }
}

private fun Project.wasmtimeSettings(): WasmtimeSettings =
    extensions.findByType(WasmtimeSettings::class.java)
        ?: extensions.create("wasmtime", WasmtimeSettings::class.java, this, objects)

private fun Project.guestRuntimeDependency(): Any =
    rootProject.findProject(":lib:guest-runtime")
        ?: GUEST_RUNTIME_COORDINATE

private fun Project.contractToolClasspath(): Configuration {
    configurations.findByName("wasmtimeContractTool")?.let { return it }
    return configurations.create("wasmtimeContractTool") {
        isCanBeConsumed = false
        isCanBeResolved = true
        description = "Isolated Kotlin KLIB ABI reader used by generated Wasmtime contracts."
    }.also { configuration ->
        dependencies.add(configuration.name, "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20")
    }
}

private data class TypePrunerRegistration(
    val task: TaskProvider<out Task>,
    val output: org.gradle.api.provider.Provider<RegularFile>,
)

private fun Project.registerTypePruner(): TypePrunerRegistration {
    val root = rootProject
    val taskName = "buildWasmtimeTypePruner"
    val suffix = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) ".exe" else ""
    val output = root.layout.buildDirectory.file("wasmtime/tools/wasm-type-pruner$suffix")
    val existing = root.tasks.findByName(taskName)
    val task = if (existing != null) {
        root.tasks.named(taskName)
    } else {
        root.tasks.register<BuildWasmTypePrunerTask>(taskName) {
            group = "wasmtime"
            description = "Builds the shared GC type-pruning helper used for Wasmtime Wasm artifacts."
            rustVersion.convention(root.providers.environmentVariable("WASMTIME_RUST_VERSION").orElse("1.96.0"))
            outputExecutable.convention(output)
        }
    }
    return TypePrunerRegistration(task, output)
}


class WasmtimeExtensionPlugin : Plugin<Project> {
    @OptIn(ExperimentalWasmDsl::class)
    override fun apply(project: Project) = with(project) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        val sharedSettings = wasmtimeSettings()
        val contractTool = contractToolClasspath()

        extensions.getByType<KotlinMultiplatformExtension>().apply {
            if (targets.findByName("wasmWasi") == null) {
                wasmWasi {
                    wasmtime()
                    nodejs()
                }
            }
        }

        val compileOnly = configurations.named("wasmWasiMainCompileOnly").get()
        val settings = extensions.create(
            "wasmtimeExtension",
            WasmtimeExtensionSettings::class.java,
            this,
            objects,
            compileOnly,
            sharedSettings,
        )
        settings.outputFileName.convention("${project.name}.wasm")
        dependencies.add(compileOnly.name, guestRuntimeDependency())

        val generatedAdapter = tasks.register<GenerateWasmtimeExtensionAdapterTask>(
            "generateWasmtimeExtensionAdapter"
        ) {
            kotlinSources.from(
                fileTree("src/commonMain/kotlin") { include("**/*.kt") },
                fileTree("src/wasmWasiMain/kotlin") { include("**/*.kt") },
            )
            apiKlibs.from(configurations.named("wasmWasiCompileClasspath"))
            toolClasspath.from(contractTool)
            entryPointAnnotation.set(settings.entryPointAnnotation)
            contractInterface.set(settings.contractInterface)
            outputDirectory.convention(layout.buildDirectory.dir("generated/wasmtimeExtension/kotlin"))
        }

        extensions.getByType<KotlinMultiplatformExtension>()
            .sourceSets.named("wasmWasiMain") {
                kotlin.srcDir(settings.generatedSourceDirectories)
                kotlin.srcDir(generatedAdapter.flatMap { it.outputDirectory })
            }
        tasks.matching { it.name == "compileKotlinWasmWasi" }.configureEach {
            dependsOn(settings.generatedSourceDirectories, generatedAdapter)
        }

        val compiler = configurations.create("wasmtimeExtensionCompiler") {
            isCanBeConsumed = false
            isCanBeResolved = true
            description = "Kotlin/Wasm compiler used for the extension's open-world export link."
        }
        dependencies {
            add(compiler.name, "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20")
        }

        val pruner = registerTypePruner()
        tasks.register<ExportWasmtimeExtensionTask>("exportWasmtimeExtension") {
            group = "wasmtime"
            description = "Exports the production Wasm extension with metadata and dead GC types removed."
            dependsOn("compileKotlinWasmWasi", pruner.task)
            extensionKlib.convention(layout.buildDirectory.dir("classes/kotlin/wasmWasi/main"))
            externalLibraries.from(configurations.named("wasmWasiCompileClasspath"))
            compilerClasspath.from(compiler)
            typePruner.convention(pruner.output)
            outputWasm.convention(settings.outputFileName.flatMap { name ->
                layout.buildDirectory.file("wasmtime/$name")
            })
        }
        Unit
    }
}

class WasmtimeHostPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        val sharedSettings = wasmtimeSettings()
        val contractTool = contractToolClasspath()
        val runtime = configurations.create("wasmtimeRuntime") {
            isCanBeConsumed = false
            isCanBeResolved = false
            description = "Libraries and modules provided by the host to Wasmtime extensions."
        }
        val runtimeClasspath = configurations.create("wasmtimeRuntimeClasspath") {
            isCanBeConsumed = false
            isCanBeResolved = true
            extendsFrom(runtime)
            description = "Resolved Wasm/WASI KLIB graph used to build the host runtime bundle."
            attributes {
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
                attribute(Usage.USAGE_ATTRIBUTE, objects.named("kotlin-api"))
                attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
                attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named("klib"))
                attribute(
                    TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                    objects.named(TargetJvmEnvironment::class.java, "non-jvm"),
                )
                attribute(KotlinPlatformType.attribute, KotlinPlatformType.wasm)

                @Suppress("UNCHECKED_CAST")
                val wasmTargetClass = Class.forName(
                    "org.jetbrains.kotlin.gradle.targets.js.KotlinWasmTargetAttribute"
                ) as Class<Any>
                val wasmTargetAttribute = Attribute.of("org.jetbrains.kotlin.wasm.target", wasmTargetClass)
                val wasi = wasmTargetClass.enumConstants.first { (it as Named).name == "wasi" }
                attribute(wasmTargetAttribute, wasi)
            }
        }
        val hostSettings = extensions.create(
            "wasmtimeHost",
            WasmtimeHostSettings::class.java,
            this,
            runtime,
            sharedSettings,
            objects,
        )
        dependencies.add(runtime.name, guestRuntimeDependency())

        val generatedProxy = tasks.register<GenerateWasmtimeHostProxyTask>(
            "generateWasmtimeHostProxy"
        ) {
            apiKlibs.from(runtimeClasspath)
            toolClasspath.from(contractTool)
            contractInterface.set(hostSettings.contractInterface)
            outputDirectory.convention(layout.buildDirectory.dir("generated/wasmtimeHost/kotlin"))
        }
        pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            extensions.getByType<KotlinMultiplatformExtension>()
                .sourceSets.named("commonMain") {
                    kotlin.srcDir(generatedProxy.flatMap { it.outputDirectory })
                }
            tasks.matching { it.name.startsWith("compileKotlin") }.configureEach {
                dependsOn(generatedProxy)
            }
        }

        val compiler = configurations.create("wasmtimeRuntimeCompiler") {
            isCanBeConsumed = false
            isCanBeResolved = true
            description = "Kotlin/Wasm compiler used to split the host runtime into open-world modules."
        }
        dependencies {
            add(compiler.name, "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20")
        }

        val pruner = registerTypePruner()
        tasks.register<BuildWasmtimeRuntimeTask>("buildWasmtimeRuntime") {
            group = "wasmtime"
            description = "Builds, strips, prunes, validates, and manifests the host Wasmtime runtime modules."
            dependsOn(pruner.task)
            runtimeKlibs.from(runtimeClasspath)
            compilerClasspath.from(compiler)
            typePruner.convention(pruner.output)
            outputDirectory.convention(layout.buildDirectory.dir("wasmtime/runtime"))
        }
        Unit
    }
}
