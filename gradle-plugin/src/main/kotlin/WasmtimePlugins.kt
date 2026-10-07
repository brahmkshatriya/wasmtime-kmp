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
import org.gradle.api.file.Directory
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
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
    WasmtimeExtensionPlugin::class.java.`package`.implementationVersion ?: "unspecified"
private val GUEST_RUNTIME_COORDINATE: String
    get() = "dev.brahmkshatriya.wasmtime:guest-runtime:$SDK_VERSION"
private const val SERIALIZATION_CBOR_COORDINATE: String =
    "org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.11.0"
private val WASMTIME_ARTIFACT_ATTRIBUTE: Attribute<String> =
    Attribute.of("dev.brahmkshatriya.wasmtime.artifact", String::class.java)

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
 * A typical contract-based extension only needs [contractInterface], [implementationClass], and any shared
 * compile-only API dependencies:
 *
 * ```kotlin
 * wasmtimeExtension {
 *     contractInterface.set("com.example.Plugin")
 *     implementationClass.set("com.example.MyPlugin")
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
    /** Stable extension identifier used in manifests and generated resource paths. */
    public val extensionId: Property<String> = objects.property(String::class.java)

    /** Human-readable extension name stored in the generated manifest. */
    public val displayName: Property<String> = objects.property(String::class.java)

    /** Extension implementation version stored in the generated manifest. */
    public val extensionVersion: Property<String> = objects.property(String::class.java)

    /** Host/guest extension ABI version. */
    public val apiVersion: Property<Int> = objects.property(Int::class.java)

    /** Declared extension capabilities such as `Http`, `PersistentStorage`, `Streaming`, or `HostResources`. */
    public val capabilities: org.gradle.api.provider.ListProperty<String> = objects.listProperty(String::class.java)

    /** Fully-qualified guest object/class that implements [contractInterface]. */
    public val implementationClass: Property<String> = objects.property(String::class.java)

    /** Fully-qualified contract interface implemented by the guest and proxied on the host. */
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

    /** Adds one or more capability names to the generated extension manifest. */
    public fun capabilities(vararg values: String) {
        capabilities.addAll(values.toList())
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
    extensionConfiguration: Configuration,
    settings: WasmtimeSettings,
    objects: ObjectFactory,
) {
    /** Fully-qualified suspend contract interface for which a host-side proxy is generated. */
    public val contractInterface: Property<String> = objects.property(String::class.java)
    private val runtime = WasmtimeDependencyCollector(project, runtimeConfiguration, settings)
    private val extensions = WasmtimeDependencyCollector(project, extensionConfiguration, settings)
    private var bundlingEnabled: Boolean = false
    private var bundlingCallback: (() -> Unit)? = null

    /**
     * Adds Wasm/WASI libraries that the host should build into its shared runtime bundle.
     *
     * Use the same shared API sets as the corresponding extension's compile-only dependencies.
     */
    public fun runtimeDependencies(configure: Action<in WasmtimeDependencyCollector>) {
        configure.execute(runtime)
    }

    /**
     * Opts this host into bundled-extension packaging and adds one extension artifact/project.
     *
     * Without calling this method the host plugin does not generate `WasmtimeBundledExtensions`, does not
     * assemble extension resources, and does not modify the application's resource packaging.
     */
    public fun bundleExtension(dependency: Any) {
        if (!bundlingEnabled) {
            bundlingEnabled = true
            bundlingCallback?.invoke()
        }
        extensions.add(dependency)
    }

    /** Backwards-compatible alias for [bundleExtension]. */
    @Deprecated(
        message = "Bundling is optional. Use bundleExtension(...) to make the packaging behavior explicit.",
        replaceWith = ReplaceWith("bundleExtension(dependency)"),
    )
    public fun extension(dependency: Any) {
        bundleExtension(dependency)
    }

    internal fun onBundlingEnabled(callback: () -> Unit) {
        bundlingCallback = callback
        if (bundlingEnabled) callback()
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
        settings.extensionId.convention(project.name)
        settings.displayName.convention(project.name)
        settings.extensionVersion.convention(providers.provider { project.version.toString() })
        settings.apiVersion.convention(2)
        settings.capabilities.convention(emptyList())
        dependencies.add(compileOnly.name, guestRuntimeDependency())
        dependencies.add(compileOnly.name, SERIALIZATION_CBOR_COORDINATE)

        val generatedAdapter = tasks.register<GenerateWasmtimeExtensionAdapterTask>(
            "generateWasmtimeExtensionAdapter"
        ) {
            apiKlibs.from(configurations.named("wasmWasiCompileClasspath"))
            toolClasspath.from(contractTool)
            implementationClass.set(settings.implementationClass)
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
        val export = tasks.register<ExportWasmtimeExtensionTask>("exportWasmtimeExtension") {
            group = "wasmtime"
            description = "Exports the production Wasm extension with metadata and dead GC types removed."
            dependsOn("compileKotlinWasmWasi", pruner.task)
            extensionKlib.convention(layout.buildDirectory.dir("classes/kotlin/wasmWasi/main"))
            externalLibraries.from(configurations.named("wasmWasiCompileClasspath"))
            compilerClasspath.from(compiler)
            typePruner.convention(pruner.output)
            extensionId.set(settings.extensionId)
            extensionName.set(settings.displayName)
            extensionVersion.set(settings.extensionVersion)
            apiVersion.set(settings.apiVersion)
            contractInterface.set(settings.contractInterface)
            capabilities.set(settings.capabilities)
            outputWasm.convention(layout.buildDirectory.file("wasmtime/extension/extension.wasm"))
            outputManifest.convention(layout.buildDirectory.file("wasmtime/extension/extension.properties"))
        }

        val extensionElements = configurations.create("wasmtimeExtensionElements") {
            isCanBeConsumed = true
            isCanBeResolved = false
            description = "Packaged Wasmtime extension artifact consumed by host projects."
            attributes {
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
                attribute(WASMTIME_ARTIFACT_ATTRIBUTE, "extension-bundle")
            }
        }
        artifacts.add(
            extensionElements.name,
            layout.buildDirectory.dir("wasmtime/extension"),
        ) {
            builtBy(export)
            type = "directory"
        }
        Unit
    }
}

/**
 * Gradle plugin `dev.brahmkshatriya.wasmtime.host`.
 *
 * It creates the typed [WasmtimeHostSettings] DSL, generates a host proxy for the configured contract, and
 * registers `buildWasmtimeRuntime`, which produces an ordered `runtime.tsv` plus shared Wasm modules.
 * Bundled-extension source/resource generation is opt-in through [WasmtimeHostSettings.bundleExtension].
 */
class WasmtimeHostPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        val sharedSettings = wasmtimeSettings()
        val contractTool = contractToolClasspath()
        val extensionArtifacts = configurations.create("wasmtimeExtensions") {
            isCanBeConsumed = false
            isCanBeResolved = true
            description = "Resolved packaged Wasmtime extension artifacts."
            attributes {
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
                attribute(WASMTIME_ARTIFACT_ATTRIBUTE, "extension-bundle")
            }
        }
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
            extensionArtifacts,
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
            dependencies.add("commonMainImplementation", SERIALIZATION_CBOR_COORDINATE)
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
        val runtimeBundle = tasks.register<BuildWasmtimeRuntimeTask>("buildWasmtimeRuntime") {
            group = "wasmtime"
            description = "Builds, strips, prunes, validates, and manifests the host Wasmtime runtime modules."
            dependsOn(pruner.task)
            runtimeKlibs.from(runtimeClasspath)
            compilerClasspath.from(compiler)
            typePruner.convention(pruner.output)
            outputDirectory.convention(layout.buildDirectory.dir("wasmtime/runtime"))
        }

        hostSettings.onBundlingEnabled {
            val generatedResources = tasks.register<AssembleWasmtimeHostResourcesTask>(
                "assembleWasmtimeHostResources"
            ) {
                dependsOn(runtimeBundle)
                runtimeDirectory.set(runtimeBundle.flatMap { it.outputDirectory })
                extensionBundles.from(extensionArtifacts)
                outputDirectory.convention(layout.buildDirectory.dir("generated/wasmtimeHost/resources"))
            }
            val generatedArtifacts = tasks.register<GenerateWasmtimeArtifactsSourceTask>(
                "generateWasmtimeArtifactsSource"
            ) {
                extensionBundles.from(extensionArtifacts)
                outputDirectory.convention(layout.buildDirectory.dir("generated/wasmtimeHost/artifacts"))
            }

            pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
                extensions.getByType<KotlinMultiplatformExtension>()
                    .sourceSets.named("commonMain") {
                        kotlin.srcDir(generatedArtifacts.flatMap { it.outputDirectory })
                        resources.srcDir(generatedResources.flatMap { it.outputDirectory })
                    }
                tasks.matching { it.name.startsWith("compileKotlin") }.configureEach {
                    dependsOn(generatedArtifacts)
                }
            }

            // Compose Multiplatform uses its own resource pipeline. Register generated extension resources only
            // after the consumer has explicitly opted into bundled-extension packaging.
            pluginManager.withPlugin("org.jetbrains.compose") {
                val compose = extensions.findByName("compose") as? ExtensionAware
                val resources = compose?.extensions?.findByName("resources")
                val method = resources?.javaClass?.methods?.firstOrNull { candidate ->
                    candidate.name == "customDirectory" && candidate.parameterTypes.size == 2
                }
                if (resources != null && method != null) {
                    method.invoke(
                        resources,
                        "commonMain",
                        generatedResources.flatMap { it.outputDirectory },
                    )
                }
            }
        }
        Unit
    }
}
