plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKMPLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "dev.brahmkshatriya.wasmtime.demo.shared"
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

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmWasi {
        wasmtime()
        nodejs()
    }

    sourceSets {
        val commonMain = getByName("commonMain")
        val linuxMain = maybeCreate("linuxMain").apply { dependsOn(commonMain) }
        getByName("linuxX64Main").dependsOn(linuxMain)
        getByName("linuxArm64Main").dependsOn(linuxMain)
    }

    sourceSets.commonMain.dependencies {
        api(libs.kotlin.coroutines.core)
        api(libs.kotlin.serialization.json)
    }

}
