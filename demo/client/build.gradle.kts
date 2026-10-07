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
    bundleExtension(projects.demo.plugin1)
    bundleExtension(projects.demo.plugin2)
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
        binaries.executable()
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

compose.resources {
    publicResClass = true
    packageOfResClass = "dev.brahmkshatriya.wasmtime.demo.generated.resources"
    generateResClass = always
}