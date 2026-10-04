plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKMPLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeNative)
    alias(libs.plugins.kotlinSerialization)
    id("dev.brahmkshatriya.wasmtime.host")
}

wasmtime {
    extensionApi("demo") {
        add(projects.demo.shared)
    }
}

wasmtimeHost {
    contractInterface.set("dev.brahmkshatriya.wasmtime.demo.shared.Plugin")
    runtimeDependencies {
        useExtensionApi("demo")
    }
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "dev.brahmkshatriya.wasmtime.demo.client"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = 24
    }

    linuxX64()
    linuxArm64()
    jvm()

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        val commonMain = getByName("commonMain")
        val linuxMain = maybeCreate("linuxMain").apply { dependsOn(commonMain) }
        getByName("linuxX64Main").dependsOn(linuxMain)
        getByName("linuxArm64Main").dependsOn(linuxMain)

        commonMain.dependencies {
            implementation(projects.lib)
            implementation(projects.demo.shared)
            implementation(libs.kotlin.coroutines.core)
            implementation(libs.kotlin.serialization.json)
            implementation("io.ktor:ktor-client-core:3.6.0")
        }
        androidMain.dependencies {
            implementation(libs.bundles.compose.android)
            implementation("io.ktor:ktor-client-okhttp:3.6.0")
        }
        linuxMain.dependencies {
            implementation(libs.bundles.compose.native)
            implementation(libs.compose.native.desktop)
            implementation("io.ktor:ktor-client-curl:3.6.0")
        }
        jvmMain.dependencies {
            implementation(libs.bundles.compose.android)
            implementation("io.ktor:ktor-client-okhttp:3.6.0")
        }
        wasmJsMain.dependencies {
            implementation(libs.bundles.compose.android)
            implementation("io.ktor:ktor-client-js:3.6.0")
        }
    }
}


val plugin1Wasm = project(":demo:plugin1").layout.buildDirectory.file("wasmtime/plugin1.wasm")
val plugin2Wasm = project(":demo:plugin2").layout.buildDirectory.file("wasmtime/plugin2.wasm")
val wasmRuntimeBundle = layout.buildDirectory.dir("wasmtime/runtime")

val syncDemoPluginResources = tasks.register("syncDemoPluginResources") {
    dependsOn(":demo:plugin1:exportWasmtimeExtension")
    dependsOn(":demo:plugin2:exportWasmtimeExtension")
    dependsOn("buildWasmtimeRuntime")
    inputs.files(plugin1Wasm, plugin2Wasm)
    inputs.dir(wasmRuntimeBundle)
    val output = layout.projectDirectory.dir("src/commonMain/composeResources/files")
    outputs.files(output.file("plugin1.wasm"), output.file("plugin2.wasm"))
    outputs.dir(output.dir("runtime"))

    doLast {
        val directory = output.asFile
        plugin1Wasm.get().asFile.copyTo(directory.resolve("plugin1.wasm"), overwrite = true)
        plugin2Wasm.get().asFile.copyTo(directory.resolve("plugin2.wasm"), overwrite = true)
        val runtimeOutput = directory.resolve("runtime")
        runtimeOutput.deleteRecursively()
        check(wasmRuntimeBundle.get().asFile.copyRecursively(runtimeOutput, overwrite = true)) {
            "Failed to copy Wasm runtime bundle"
        }
    }
}

val androidComposeAssets = layout.buildDirectory.dir("generated/compose/androidAssets")

val prepareAndroidComposeAssets = tasks.register<Sync>("prepareAndroidComposeAssets") {
    dependsOn(syncDemoPluginResources)
    from(layout.projectDirectory.dir("src/commonMain/composeResources"))
    into(
        androidComposeAssets.map {
            it.dir("composeResources/dev.brahmkshatriya.wasmtime.demo.generated.resources")
        }
    )
}

compose.resources {
    publicResClass = true
    packageOfResClass = "dev.brahmkshatriya.wasmtime.demo.generated.resources"
    generateResClass = always
}

tasks.matching {
    it.name == "prepareComposeResourcesTaskForCommonMain" ||
        it.name == "copyNonXmlValueResourcesForCommonMain"
}.configureEach {
    dependsOn(syncDemoPluginResources)
}