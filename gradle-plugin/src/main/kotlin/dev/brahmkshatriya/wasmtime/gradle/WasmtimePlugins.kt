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

/**
 * Named dependency set shared between the host runtime and one or more extensions.
 *
 * Define a set once in the top-level `wasmtime` block, then consume it from both `wasmtimeHost` and
 * `wasmtimeExtension` so the host runtime and guest compile classpaths stay aligned.
 *
 * ```kotlin
 * wasmtime {
 *     extensionApi("pluginApi") {
 *         add(project(":plugin-api"))
 *     }
 * }
 * ```
 */
public open class WasmtimeDependencySet internal constructor(
    private val setName: String,
) : Named {
    private val values = mutableListOf<Any>()
    private val subscribers = mutableListOf<(Any) -> Unit>()

    override fun getName(): String = setName

    /** Adds any Gradle dependency notation accepted by [org.gradle.api.artifacts.dsl.DependencyHandler.add]. */
    public fun add(dependency: Any) {
        values += dependency
        subscribers.forEach { it(dependency) }
    }

    internal fun subscribe(consumer: (Any) -> Unit) {
        values.forEach(consumer)
        subscribers += consumer
    }
}

/**
 * Shared `wasmtime { ... }` Gradle DSL.
 *
 * Use [extensionApi] to define named dependency groups that must be visible to both an extension at compile
 * time and the host's generated runtime bundle.
 */
public open class WasmtimeSettings @Inject constructor(
    private val project: Project,
    objects: ObjectFactory,
) {
    /** All named dependency sets registered for Wasmtime extensions in this project. */
    public val extensionApis: NamedDomainObjectContainer<WasmtimeDependencySet> =
        objects.domainObjectContainer(WasmtimeDependencySet::class.java) { name ->
            WasmtimeDependencySet(name)
        }

    /** Creates or configures a reusable extension API dependency set named [name]. */
    public fun extensionApi(name: String, configure: Action<in WasmtimeDependencySet>) {
        configure.execute(extensionApis.maybeCreate(name))
    }
}

/**
 * Typed dependency collector exposed by [WasmtimeHostSettings.runtimeDependencies] and
 * [WasmtimeExtensionSettings.compileOnlyDependencies].
 */
public class WasmtimeDependencyCollector internal constructor(
    private val project: Project,
    private val configuration: Configuration,
    private val settings: WasmtimeSettings,
) {
    /** Adds a dependency directly to the underlying Wasmtime configuration. */
    public fun add(dependency: Any) {
        project.dependencies.add(configuration.name, dependency)
    }

    /** Includes every dependency from [set], including dependencies added to that set later. */
    public fun from(set: WasmtimeDependencySet) {
        set.subscribe(::add)
    }

    /** Includes the shared dependency set previously registered as `wasmtime.extensionApi(name)`. */
    public fun useExtensionApi(name: String) {
        from(settings.extensionApis.maybeCreate(name))
    }
}

/**
 * `wasmtimeExtension { ... }` settings for a Kotlin/Wasm-WASI extension project.
 *
 * A typical contract-based extension only needs [contractInterface], [entryPointAnnotation], and any shared
 * compile-only API dependencies:
 *
 * ```kotlin
 * wasmtimeExtension {
 *     contractInterface.set("com.example.Plugin")
 *     entryPointAnnotation.set("com.example.ExtensionEntry")
 *     compileOnlyDependencies { useExtensionApi("pluginApi") }
 * }
 * ```
 */
public open class WasmtimeExtensionSettings internal constructor(
    private val project: Project,
    objects: ObjectFactory,
    compileOnlyConfiguration: Configuration,
    settings: WasmtimeSettings,
) {
    /** Final exported Wasm file name under `build/wasmtime/`. Defaults to `<project-name>.wasm`. */
    public val outputFileName: Property<String> = objects.property(String::class.java)

    /**
     * Fully-qualified annotation placed on exactly one top-level guest implementation object.
     * The plugin uses it to discover the contract implementation and generate the guest adapter.
     */
    public val entryPointAnnotation: Property<String> = objects.property(String::class.java)

    /** Fully-qualified suspend contract interface implemented by the guest and proxied on the host. */
    public val contractInterface: Property<String> = objects.property(String::class.java)

    /** Additional generated Kotlin source directories included in `wasmWasiMain`. */
    public val generatedSourceDirectories: ConfigurableFileCollection = objects.fileCollection()
    private val compileOnly = WasmtimeDependencyCollector(project, compileOnlyConfiguration, settings)

    /**
     * Adds libraries needed to compile the extension but supplied by the host runtime at execution time.
     *
     * Prefer [WasmtimeDependencyCollector.useExtensionApi] for shared contract/API dependency sets.
     */
    public fun compileOnlyDependencies(configure: Action<in WasmtimeDependencyCollector>) {
        configure.execute(compileOnly)
    }

    /**
     * Registers an additional generated Kotlin source directory and its producing task.
     *
     * This is intended for code generators used by an extension; ordinary source directories need no registration.
     */
    public fun generatedSource(directory: Any, builtBy: Any) {
        generatedSourceDirectories.from(directory)
        generatedSourceDirectories.builtBy(builtBy)
    }
}

/**
 * `wasmtimeHost { ... }` settings for the application/library that loads extensions.
 *
 * When [contractInterface] is configured, the plugin generates `<Contract>WasmtimeProxy` into `commonMain`.
 * [runtimeDependencies] controls the Wasm/WASI KLIBs bundled into `build/wasmtime/runtime`.
 */
public open class WasmtimeHostSettings internal constructor(
    project: Project,
    runtimeConfiguration: Configuration,
    settings: WasmtimeSettings,
    objects: ObjectFactory,
) {
    /** Fully-qualified suspend contract interface for which a host-side proxy is generated. */
    public val contractInterface: Property<String> = objects.property(String::class.java)
    private val runtime = WasmtimeDependencyCollector(project, runtimeConfiguration, settings)

    /**
     * Adds Wasm/WASI libraries that the host should build into its shared runtime bundle.
     *
     * Use the same shared API sets as the corresponding extension's compile-only dependencies.
     */
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


/**
 * Gradle plugin `dev.brahmkshatriya.wasmtime.extension`.
 *
 * It configures a Kotlin/Wasm-WASI target, adds the SDK guest runtime as compile-only, generates the contract
 * adapter, and registers `exportWasmtimeExtension`, whose output is `build/wasmtime/<outputFileName>`.
 */
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

/**
 * Gradle plugin `dev.brahmkshatriya.wasmtime.host`.
 *
 * It creates the typed [WasmtimeHostSettings] DSL, generates a host proxy for the configured contract, and
 * registers `buildWasmtimeRuntime`, which produces an ordered `runtime.tsv` plus shared Wasm modules.
 */
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
