import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKMPLibrary)
    `maven-publish`
}

group = property("GROUP").toString()
version = property("VERSION").toString()

val slimWasmtimeLinux = project.file("src/native/.deps/slim/x86_64-linux/lib/libwasmtime.a")
val slimWasmtimeLinuxArm64 = project.file("src/native/.deps/slim/aarch64-linux/lib/libwasmtime.a")
val slimWasmtimeWindowsX64 = project.file("src/native/.deps/slim/x86_64-windows-gnu/lib/libwasmtime.a")
val slimWasmtimeMacosX64 = project.file("src/native/.deps/slim/x86_64-macos/lib/libwasmtime.a")
val slimWasmtimeMacosArm64 = project.file("src/native/.deps/slim/aarch64-macos/lib/libwasmtime.a")
val slimWasmtimeIosArm64 = project.file("src/native/.deps/slim/aarch64-ios/lib/libwasmtime.a")
val slimWasmtimeAndroidArm64 = project.file("src/native/.deps/slim/aarch64-android/lib/libwasmtime.a")
val slimWasmtimeAndroidX64 = project.file("src/native/.deps/slim/x86_64-android/lib/libwasmtime.a")
val useFullWasmtime = providers.environmentVariable("WASMTIME_USE_FULL").orElse("0")
val wasmtimeCompilerOptLevel = providers.environmentVariable("WASMTIME_COMPILER_OPT_LEVEL").orElse("s")

val buildSlimWasmtimeLinux = tasks.register<Exec>("buildSlimWasmtimeLinux") {
    workingDir(rootProject.projectDir)
    commandLine("bash", project.file("src/native/build-slim-wasmtime.sh"), "linux-x64")
    inputs.file(project.file("src/native/build-slim-wasmtime.sh"))
    inputs.property("wasmtimeVersion", providers.environmentVariable("WASMTIME_VERSION").orElse("49.0.1"))
    inputs.property("rustVersion", providers.environmentVariable("WASMTIME_RUST_VERSION").orElse("1.96.0"))
    inputs.property("compilerOptLevel", wasmtimeCompilerOptLevel)
    outputs.file(slimWasmtimeLinux)
}

val buildSlimWasmtimeLinuxArm64 = tasks.register<Exec>("buildSlimWasmtimeLinuxArm64") {
    workingDir(rootProject.projectDir)
    commandLine("bash", project.file("src/native/build-slim-wasmtime.sh"), "linux-arm64")
    inputs.file(project.file("src/native/build-slim-wasmtime.sh"))
    inputs.property("wasmtimeVersion", providers.environmentVariable("WASMTIME_VERSION").orElse("49.0.1"))
    inputs.property("rustVersion", providers.environmentVariable("WASMTIME_RUST_VERSION").orElse("1.96.0"))
    inputs.property("compilerOptLevel", wasmtimeCompilerOptLevel)
    outputs.file(slimWasmtimeLinuxArm64)
}

val buildSlimWasmtimeWindows = tasks.register<Exec>("buildSlimWasmtimeWindows") {
    workingDir(rootProject.projectDir)
    commandLine("bash", project.file("src/native/build-slim-wasmtime.sh"), "windows-x64")
    inputs.file(project.file("src/native/build-slim-wasmtime.sh"))
    inputs.property("wasmtimeVersion", providers.environmentVariable("WASMTIME_VERSION").orElse("49.0.1"))
    inputs.property("rustVersion", providers.environmentVariable("WASMTIME_RUST_VERSION").orElse("1.96.0"))
    inputs.property("compilerOptLevel", wasmtimeCompilerOptLevel)
    outputs.file(slimWasmtimeWindowsX64)
}

val buildSlimWasmtimeMacosX64 = tasks.register<Exec>("buildSlimWasmtimeMacosX64") {
    workingDir(rootProject.projectDir)
    commandLine("bash", project.file("src/native/build-slim-wasmtime.sh"), "macos-x64")
    inputs.file(project.file("src/native/build-slim-wasmtime.sh"))
    inputs.property("wasmtimeVersion", providers.environmentVariable("WASMTIME_VERSION").orElse("49.0.1"))
    inputs.property("rustVersion", providers.environmentVariable("WASMTIME_RUST_VERSION").orElse("1.96.0"))
    inputs.property("compilerOptLevel", wasmtimeCompilerOptLevel)
    outputs.file(slimWasmtimeMacosX64)
}

val buildSlimWasmtimeMacosArm64 = tasks.register<Exec>("buildSlimWasmtimeMacosArm64") {
    workingDir(rootProject.projectDir)
    commandLine("bash", project.file("src/native/build-slim-wasmtime.sh"), "macos-arm64")
    inputs.file(project.file("src/native/build-slim-wasmtime.sh"))
    inputs.property("wasmtimeVersion", providers.environmentVariable("WASMTIME_VERSION").orElse("49.0.1"))
    inputs.property("rustVersion", providers.environmentVariable("WASMTIME_RUST_VERSION").orElse("1.96.0"))
    inputs.property("compilerOptLevel", wasmtimeCompilerOptLevel)
    outputs.file(slimWasmtimeMacosArm64)
}

val buildSlimWasmtimeIosArm64 = tasks.register<Exec>("buildSlimWasmtimeIosArm64") {
    workingDir(rootProject.projectDir)
    commandLine("bash", project.file("src/native/build-slim-wasmtime.sh"), "ios-arm64")
    inputs.file(project.file("src/native/build-slim-wasmtime.sh"))
    inputs.property("wasmtimeVersion", providers.environmentVariable("WASMTIME_VERSION").orElse("49.0.1"))
    inputs.property("rustVersion", providers.environmentVariable("WASMTIME_RUST_VERSION").orElse("1.96.0"))
    inputs.property("compilerOptLevel", wasmtimeCompilerOptLevel)
    outputs.file(slimWasmtimeIosArm64)
}

val buildSlimWasmtimeAndroidArm64 = tasks.register<Exec>("buildSlimWasmtimeAndroidArm64") {
    workingDir(rootProject.projectDir)
    commandLine("bash", project.file("src/native/build-slim-wasmtime.sh"), "android-arm64")
    inputs.file(project.file("src/native/build-slim-wasmtime.sh"))
    inputs.property("wasmtimeVersion", providers.environmentVariable("WASMTIME_VERSION").orElse("49.0.1"))
    inputs.property("rustVersion", providers.environmentVariable("WASMTIME_RUST_VERSION").orElse("1.96.0"))
    inputs.property("compilerOptLevel", wasmtimeCompilerOptLevel)
    outputs.file(slimWasmtimeAndroidArm64)
}

val buildSlimWasmtimeAndroidX64 = tasks.register<Exec>("buildSlimWasmtimeAndroidX64") {
    workingDir(rootProject.projectDir)
    commandLine("bash", project.file("src/native/build-slim-wasmtime.sh"), "android-x86_64")
    inputs.file(project.file("src/native/build-slim-wasmtime.sh"))
    inputs.property("wasmtimeVersion", providers.environmentVariable("WASMTIME_VERSION").orElse("49.0.1"))
    inputs.property("rustVersion", providers.environmentVariable("WASMTIME_RUST_VERSION").orElse("1.96.0"))
    inputs.property("compilerOptLevel", wasmtimeCompilerOptLevel)
    outputs.file(slimWasmtimeAndroidX64)
}

val buildSlimWasmtimeAndroid = tasks.register("buildSlimWasmtimeAndroid") {
    group = "build"
    description = "Build compact Wasmtime archives for both Android ABIs."
    dependsOn(buildSlimWasmtimeAndroidArm64, buildSlimWasmtimeAndroidX64)
}

tasks.register("buildSlimWasmtime") {
    group = "build"
    description = "Build compact Wasmtime archives for all native targets."
    dependsOn(buildSlimWasmtimeLinux, buildSlimWasmtimeLinuxArm64, buildSlimWasmtimeWindows, buildSlimWasmtimeAndroid)
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        dependsOn(buildSlimWasmtimeMacosX64, buildSlimWasmtimeMacosArm64, buildSlimWasmtimeIosArm64)
    }
}

fun org.gradle.api.Task.trackOptionalSlimArchive(file: File) {
    inputs.files(providers.provider { if (file.isFile) listOf(file) else emptyList() })
    inputs.property("wasmtimeUseFull", providers.environmentVariable("WASMTIME_USE_FULL").orElse("0"))
}

tasks.register<Exec>("buildLinuxNativeBridge") {
    if (useFullWasmtime.get() != "1") {
        dependsOn(buildSlimWasmtimeLinux)
    }
    workingDir(rootProject.projectDir)
    commandLine("bash", project.file("src/native/build.sh"), "linux-x64")
    inputs.files(
        project.file("src/native/build.sh"),
        project.file("src/native/wasmtime_kmp.h"),
        project.file("src/native/wasmtime_kmp.c"),
        project.file("src/native/wasi_lite.h"),
        project.file("src/native/wasi_lite.c"),
    )
    trackOptionalSlimArchive(slimWasmtimeLinux)
    outputs.files(layout.buildDirectory.file("native/linuxX64/libwasmtime_kmp_bridge.a"), layout.buildDirectory.file("native/linuxX64/libwasmtime.a"))
}

tasks.register<Exec>("buildLinuxArm64NativeBridge") {
    if (useFullWasmtime.get() != "1") {
        dependsOn(buildSlimWasmtimeLinuxArm64)
    }
    workingDir(rootProject.projectDir)
    commandLine("bash", project.file("src/native/build.sh"), "linux-arm64")
    inputs.files(
        project.file("src/native/build.sh"),
        project.file("src/native/wasmtime_kmp.h"),
        project.file("src/native/wasmtime_kmp.c"),
        project.file("src/native/wasi_lite.h"),
        project.file("src/native/wasi_lite.c"),
    )
    trackOptionalSlimArchive(slimWasmtimeLinuxArm64)
    outputs.files(
        layout.buildDirectory.file("native/linuxArm64/libwasmtime_kmp_bridge.a"),
        layout.buildDirectory.file("native/linuxArm64/libwasmtime.a"),
    )
}

val buildWindowsNativeBridge = tasks.register<Exec>("buildWindowsNativeBridge") {
    // The official Windows C-API static archive is MSVC-targeted; the Kotlin/Native
    // mingwX64 backend uses our GNU-targeted compact archive even when USE_FULL is set.
    dependsOn(buildSlimWasmtimeWindows)
    workingDir(rootProject.projectDir)
    commandLine("bash", project.file("src/native/build.sh"), "windows-x64")
    inputs.files(
        project.file("src/native/build.sh"),
        project.file("src/native/wasmtime_kmp.h"),
        project.file("src/native/wasmtime_kmp.c"),
        project.file("src/native/wasi_lite.h"),
        project.file("src/native/wasi_lite.c"),
        project.file("src/native/windows_compat.h"),
        project.file("src/native/windows_compat.c"),
    )
    trackOptionalSlimArchive(slimWasmtimeWindowsX64)
    outputs.files(
        layout.buildDirectory.file("native/mingwX64/libwasmtime_kmp_bridge.a"),
        layout.buildDirectory.file("native/mingwX64/libwasmtime.a"),
    )
}

fun registerAppleNativeBridge(name: String, command: String, archive: File, outputTarget: String, slimTask: TaskProvider<Exec>) =
    tasks.register<Exec>(name) {
        if (useFullWasmtime.get() != "1" || command == "ios-arm64") dependsOn(slimTask)
        workingDir(rootProject.projectDir)
        commandLine("bash", project.file("src/native/build.sh"), command)
        inputs.files(
            project.file("src/native/build.sh"),
            project.file("src/native/wasmtime_kmp.h"),
            project.file("src/native/wasmtime_kmp.c"),
            project.file("src/native/wasi_lite.h"),
            project.file("src/native/wasi_lite.c"),
        )
        trackOptionalSlimArchive(archive)
        outputs.files(
            layout.buildDirectory.file("native/$outputTarget/libwasmtime_kmp_bridge.a"),
            layout.buildDirectory.file("native/$outputTarget/libwasmtime.a"),
        )
    }

val buildMacosX64NativeBridge = registerAppleNativeBridge(
    "buildMacosX64NativeBridge", "macos-x64", slimWasmtimeMacosX64, "macosX64", buildSlimWasmtimeMacosX64,
)
val buildMacosArm64NativeBridge = registerAppleNativeBridge(
    "buildMacosArm64NativeBridge", "macos-arm64", slimWasmtimeMacosArm64, "macosArm64", buildSlimWasmtimeMacosArm64,
)
val buildIosArm64NativeBridge = registerAppleNativeBridge(
    "buildIosArm64NativeBridge", "ios-arm64", slimWasmtimeIosArm64, "iosArm64", buildSlimWasmtimeIosArm64,
)

val testLinuxArm64UnderQemu = tasks.register<Exec>("testLinuxArm64UnderQemu") {
    group = "verification"
    description = "Run the Linux ARM64 Kotlin/Native Wasmtime smoke test under Kotlin/Native's bundled QEMU."
    dependsOn("linkDebugTestLinuxArm64")
    workingDir(rootProject.projectDir)
    commandLine(
        "bash",
        project.file("src/native/test-linux-arm64.sh"),
        layout.buildDirectory.file("bin/linuxArm64/debugTest/test.kexe").get().asFile.absolutePath,
    )
    inputs.file(project.file("src/native/test-linux-arm64.sh"))
    inputs.file(layout.buildDirectory.file("bin/linuxArm64/debugTest/test.kexe"))
}

val testNativeSecurity = tasks.register<Exec>("testNativeSecurity") {
    group = "verification"
    description = "Run adversarial native sandbox/cache/lifecycle regression tests."
    dependsOn("buildLinuxNativeBridge")
    workingDir(rootProject.projectDir)
    commandLine(
        "bash",
        project.file("src/native/security/test-security.sh"),
        layout.buildDirectory.dir("native/linuxX64").get().asFile.absolutePath,
    )
    inputs.files(fileTree("src/native/security"))
    inputs.files(
        project.file("src/native/wasmtime_kmp.h"),
        project.file("src/native/wasmtime_kmp.c"),
        project.file("src/native/wasi_lite.h"),
        project.file("src/native/wasi_lite.c"),
    )
}

val buildAndroidArm64NativeBridge = tasks.register<Exec>("buildAndroidArm64NativeBridge") {
    if (useFullWasmtime.get() != "1") dependsOn(buildSlimWasmtimeAndroidArm64)
    workingDir(rootProject.projectDir)
    commandLine("bash", project.file("src/native/build.sh"), "android-arm64")
    inputs.files(
        project.file("src/native/build.sh"),
        project.file("src/native/wasmtime_kmp.h"),
        project.file("src/native/wasmtime_kmp.c"),
        project.file("src/native/wasi_lite.h"),
        project.file("src/native/wasi_lite.c"),
    )
    trackOptionalSlimArchive(slimWasmtimeAndroidArm64)
    outputs.file(layout.buildDirectory.file("generated/jniLibs/arm64-v8a/libwasmtime_kmp.so"))
}

val buildAndroidX64NativeBridge = tasks.register<Exec>("buildAndroidX64NativeBridge") {
    if (useFullWasmtime.get() != "1") dependsOn(buildSlimWasmtimeAndroidX64)
    workingDir(rootProject.projectDir)
    commandLine("bash", project.file("src/native/build.sh"), "android-x86_64")
    inputs.files(
        project.file("src/native/build.sh"),
        project.file("src/native/wasmtime_kmp.h"),
        project.file("src/native/wasmtime_kmp.c"),
        project.file("src/native/wasi_lite.h"),
        project.file("src/native/wasi_lite.c"),
    )
    trackOptionalSlimArchive(slimWasmtimeAndroidX64)
    outputs.file(layout.buildDirectory.file("generated/jniLibs/x86_64/libwasmtime_kmp.so"))
}

val usePrebuiltAndroidNative = providers.gradleProperty("wasmtimeAndroidPrebuilt")
    .map(String::toBoolean)
    .orElse(false)

val buildAndroidNativeBridge = tasks.register("buildAndroidNativeBridge") {
    group = "build"
    description = "Prepare both Android JNI payloads, or validate prebuilt CI payloads."
    if (!usePrebuiltAndroidNative.get()) {
        dependsOn(buildAndroidArm64NativeBridge, buildAndroidX64NativeBridge)
    }
    doLast {
        val arm64 = layout.buildDirectory.file("generated/jniLibs/arm64-v8a/libwasmtime_kmp.so").get().asFile
        val x64 = layout.buildDirectory.file("generated/jniLibs/x86_64/libwasmtime_kmp.so").get().asFile
        check(arm64.isFile && arm64.length() > 0) { "Missing Android ARM64 JNI payload: $arm64" }
        check(x64.isFile && x64.length() > 0) { "Missing Android x86_64 JNI payload: $x64" }
    }
    outputs.dir(layout.buildDirectory.dir("generated/jniLibs"))
}

extensions.configure<KotlinMultiplatformAndroidComponentsExtension> {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("generated/jniLibs").get().asFile.absolutePath
        )
    }
}

tasks.matching { it.name == "mergeAndroidMainJniLibFolders" }.configureEach {
    dependsOn("buildAndroidNativeBridge")
}

val buildJvmLinuxX64NativeBridge = tasks.register<Exec>("buildJvmLinuxX64NativeBridge") {
    if (useFullWasmtime.get() != "1") dependsOn(buildSlimWasmtimeLinux)
    workingDir(rootProject.projectDir)
    commandLine("bash", project.file("src/native/build.sh"), "jvm-linux-x64")
    inputs.files(
        project.file("src/native/build.sh"),
        project.file("src/native/wasmtime_kmp.h"),
        project.file("src/native/wasmtime_kmp.c"),
        project.file("src/native/wasi_lite.h"),
        project.file("src/native/wasi_lite.c"),
    )
    trackOptionalSlimArchive(slimWasmtimeLinux)
    outputs.file(layout.buildDirectory.file("native/jvmLinuxX64/libwasmtime_kmp.so"))
}

val buildJvmLinuxArm64NativeBridge = tasks.register<Exec>("buildJvmLinuxArm64NativeBridge") {
    if (useFullWasmtime.get() != "1") dependsOn(buildSlimWasmtimeLinuxArm64)
    workingDir(rootProject.projectDir)
    commandLine("bash", project.file("src/native/build.sh"), "jvm-linux-arm64")
    inputs.files(
        project.file("src/native/build.sh"),
        project.file("src/native/wasmtime_kmp.h"),
        project.file("src/native/wasmtime_kmp.c"),
        project.file("src/native/wasi_lite.h"),
        project.file("src/native/wasi_lite.c"),
    )
    trackOptionalSlimArchive(slimWasmtimeLinuxArm64)
    outputs.file(layout.buildDirectory.file("native/jvmLinuxArm64/libwasmtime_kmp.so"))
}

tasks.register("buildJvmNativeBridge") {
    group = "build"
    description = "Build all JVM Linux native payloads."
    dependsOn(buildJvmLinuxX64NativeBridge, buildJvmLinuxArm64NativeBridge)
}

val jvmLinuxX64NativeJar = tasks.register<Jar>("jvmLinuxX64NativeJar") {
    group = "build"
    description = "Package the Linux x86_64 JNI bridge without JVM classes."
    dependsOn(buildJvmLinuxX64NativeBridge)
    archiveBaseName.set("${project.name}-jvm")
    archiveClassifier.set("linux-x64")
    from(layout.buildDirectory.file("native/jvmLinuxX64/libwasmtime_kmp.so")) {
        into("native/linux-x64")
    }
}

val jvmLinuxArm64NativeJar = tasks.register<Jar>("jvmLinuxArm64NativeJar") {
    group = "build"
    description = "Package the Linux ARM64 JNI bridge without JVM classes."
    dependsOn(buildJvmLinuxArm64NativeBridge)
    archiveBaseName.set("${project.name}-jvm")
    archiveClassifier.set("linux-arm64")
    from(layout.buildDirectory.file("native/jvmLinuxArm64/libwasmtime_kmp.so")) {
        into("native/linux-arm64")
    }
}

val wasmtimeMavenRepository = providers.gradleProperty("wasmtimeMavenRepository")
    .orElse(providers.environmentVariable("WASMTIME_MAVEN_REPOSITORY"))

fun registerJvmClassifierRepositoryCopy(
    taskName: String,
    jarTask: TaskProvider<Jar>,
) = tasks.register(taskName) {
    group = "publishing"
    dependsOn(jarTask)
    doLast {
        val repository = file(wasmtimeMavenRepository.get())
        val destination = repository
            .resolve(project.group.toString().replace('.', '/'))
            .resolve("${project.name}-jvm")
            .resolve(project.version.toString())
        destination.mkdirs()
        val source = jarTask.get().archiveFile.get().asFile
        source.copyTo(destination.resolve(source.name), overwrite = true)
    }
}

registerJvmClassifierRepositoryCopy(
    "copyJvmLinuxX64ClassifierToWasmtimeRepository",
    jvmLinuxX64NativeJar,
)
registerJvmClassifierRepositoryCopy(
    "copyJvmLinuxArm64ClassifierToWasmtimeRepository",
    jvmLinuxArm64NativeJar,
)

val jvmNativeArchAttribute = Attribute.of(
    "dev.brahmkshatriya.wasmtime.jvm.native.arch",
    String::class.java,
)

fun nativeJvmElements(name: String, arch: String, artifactTask: TaskProvider<Jar>) =
    configurations.create(name) {
        isCanBeConsumed = true
        isCanBeResolved = false
        attributes {
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named("wasmtime-jni"))
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(jvmNativeArchAttribute, arch)
        }
        outgoing.artifact(artifactTask)
    }

nativeJvmElements("jvmLinuxX64NativeElements", "linux-x64", jvmLinuxX64NativeJar)
nativeJvmElements("jvmLinuxArm64NativeElements", "linux-arm64", jvmLinuxArm64NativeJar)

kotlin {
    jvmToolchain(21)
    android {
        namespace = "dev.brahmkshatriya.wasmtime"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = 24
    }
    jvm()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }
    linuxX64 {
        compilations.getByName("main") {
            cinterops.create("wasmtimeKmp") {
                header(project.file("src/native/wasmtime_kmp.h"))
                defFile(project.file("src/native/wasmtimeKmpLinux.def"))
                packageName("dev.brahmkshatriya.wasmtime.cinterop")
                val nativeDir = layout.buildDirectory.dir("native/linuxX64").get().asFile
                extraOpts(
                    "-libraryPath", nativeDir.absolutePath,
                    "-staticLibrary", "libwasmtime_kmp_bridge.a",
                    "-staticLibrary", "libwasmtime.a",
                )
            }
        }
    }
    linuxArm64 {
        compilations.getByName("main") {
            cinterops.create("wasmtimeKmp") {
                header(project.file("src/native/wasmtime_kmp.h"))
                defFile(project.file("src/native/wasmtimeKmpLinux.def"))
                packageName("dev.brahmkshatriya.wasmtime.cinterop")
                val nativeDir = layout.buildDirectory.dir("native/linuxArm64").get().asFile
                extraOpts(
                    "-libraryPath", nativeDir.absolutePath,
                    "-staticLibrary", "libwasmtime_kmp_bridge.a",
                    "-staticLibrary", "libwasmtime.a",
                )
            }
        }
    }

    mingwX64 {
        compilations.getByName("main") {
            cinterops.create("wasmtimeKmp") {
                header(project.file("src/native/wasmtime_kmp.h"))
                defFile(project.file("src/native/wasmtimeKmpWindows.def"))
                packageName("dev.brahmkshatriya.wasmtime.cinterop")
                val nativeDir = layout.buildDirectory.dir("native/mingwX64").get().asFile
                extraOpts(
                    "-libraryPath", nativeDir.absolutePath,
                    "-staticLibrary", "libwasmtime_kmp_bridge.a",
                    "-staticLibrary", "libwasmtime.a",
                )
            }
        }
    }
    macosX64 {
        compilations.getByName("main") {
            cinterops.create("wasmtimeKmp") {
                header(project.file("src/native/wasmtime_kmp.h"))
                defFile(project.file("src/native/wasmtimeKmpApple.def"))
                packageName("dev.brahmkshatriya.wasmtime.cinterop")
                val nativeDir = layout.buildDirectory.dir("native/macosX64").get().asFile
                extraOpts(
                    "-libraryPath", nativeDir.absolutePath,
                    "-staticLibrary", "libwasmtime_kmp_bridge.a",
                    "-staticLibrary", "libwasmtime.a",
                )
            }
        }
    }

    macosArm64 {
        compilations.getByName("main") {
            cinterops.create("wasmtimeKmp") {
                header(project.file("src/native/wasmtime_kmp.h"))
                defFile(project.file("src/native/wasmtimeKmpApple.def"))
                packageName("dev.brahmkshatriya.wasmtime.cinterop")
                val nativeDir = layout.buildDirectory.dir("native/macosArm64").get().asFile
                extraOpts(
                    "-libraryPath", nativeDir.absolutePath,
                    "-staticLibrary", "libwasmtime_kmp_bridge.a",
                    "-staticLibrary", "libwasmtime.a",
                )
            }
        }
    }

    iosArm64 {
        compilations.getByName("main") {
            cinterops.create("wasmtimeKmp") {
                header(project.file("src/native/wasmtime_kmp.h"))
                defFile(project.file("src/native/wasmtimeKmpApple.def"))
                packageName("dev.brahmkshatriya.wasmtime.cinterop")
                val nativeDir = layout.buildDirectory.dir("native/iosArm64").get().asFile
                extraOpts(
                    "-libraryPath", nativeDir.absolutePath,
                    "-staticLibrary", "libwasmtime_kmp_bridge.a",
                    "-staticLibrary", "libwasmtime.a",
                )
            }
        }
    }


    sourceSets.commonMain.dependencies {
        implementation(libs.kotlin.coroutines.core)
    }

    sourceSets {
        val commonMain = getByName("commonMain")
        listOf(
            "linuxX64Main",
            "linuxArm64Main",
            "mingwX64Main",
            "macosX64Main",
            "macosArm64Main",
            "iosArm64Main",
        ).forEach { sourceSetName ->
            getByName(sourceSetName) {
                dependsOn(commonMain)
                // Cinterop declarations are target-specific. Keep the shared native implementation
                // in each leaf source set so root metadata never commonizes host-specific cinterops.
                kotlin.srcDir("src/nativeLeafMain/kotlin")
            }
        }
        val commonTest = getByName("commonTest")
        val nativeHostTest = maybeCreate("nativeHostTest").apply {
            dependsOn(commonTest)
        }
        listOf(
            "linuxX64Test",
            "linuxArm64Test",
            "mingwX64Test",
            "macosX64Test",
            "macosArm64Test",
            "iosArm64Test",
        ).forEach { getByName(it).dependsOn(nativeHostTest) }
        nativeHostTest.dependencies {
            implementation(kotlin("test"))
        }
        val jniMain = create("jniMain") {
            dependsOn(commonMain)
        }
        getByName("androidMain") {
            dependsOn(jniMain)
        }
        getByName("jvmMain") {
            dependsOn(jniMain)
        }
    }
}

fun org.gradle.api.Task.useEmbeddedNativeArchives(buildTask: Any, target: String) {
    dependsOn(buildTask)
    inputs.files(
        layout.buildDirectory.file("native/$target/libwasmtime_kmp_bridge.a"),
        layout.buildDirectory.file("native/$target/libwasmtime.a"),
    )
}

tasks.matching { it.name == "cinteropWasmtimeKmpLinuxX64" }.configureEach {
    useEmbeddedNativeArchives("buildLinuxNativeBridge", "linuxX64")
}
tasks.matching { it.name == "cinteropWasmtimeKmpLinuxArm64" }.configureEach {
    useEmbeddedNativeArchives("buildLinuxArm64NativeBridge", "linuxArm64")
}
tasks.matching { it.name == "cinteropWasmtimeKmpMingwX64" }.configureEach {
    useEmbeddedNativeArchives(buildWindowsNativeBridge, "mingwX64")
}
tasks.matching { it.name == "cinteropWasmtimeKmpMacosX64" }.configureEach {
    useEmbeddedNativeArchives(buildMacosX64NativeBridge, "macosX64")
}
tasks.matching { it.name == "cinteropWasmtimeKmpMacosArm64" }.configureEach {
    useEmbeddedNativeArchives(buildMacosArm64NativeBridge, "macosArm64")
}
tasks.matching { it.name == "cinteropWasmtimeKmpIosArm64" }.configureEach {
    useEmbeddedNativeArchives(buildIosArm64NativeBridge, "iosArm64")
}

val verifyAppleNativeLink = tasks.register("verifyAppleNativeLink") {
    group = "verification"
    description = "Link macOS x64/ARM64 and iOS ARM64 native smoke tests on a macOS/Xcode host."
    onlyIf { System.getProperty("os.name").lowercase().contains("mac") }
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        dependsOn("linkDebugTestMacosX64", "linkDebugTestMacosArm64", "linkDebugTestIosArm64")
    }
}

if (System.getProperty("os.name").lowercase().contains("linux")) {
    tasks.named("check").configure { dependsOn(testNativeSecurity) }
}
