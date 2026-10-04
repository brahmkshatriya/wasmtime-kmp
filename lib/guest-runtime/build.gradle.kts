plugins {
    alias(libs.plugins.kotlinMultiplatform)
    `maven-publish`
}

group = property("GROUP").toString()
version = property("VERSION").toString()

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }

    sourceSets.wasmWasiMain.dependencies {
        implementation(libs.kotlin.coroutines.core)
    }
}
