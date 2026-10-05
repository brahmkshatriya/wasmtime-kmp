plugins {
    alias(libs.plugins.kotlinMultiplatform)
    `maven-publish`
}

group = property("GROUP").toString()
version = providers.gradleProperty("VERSION")
    .orElse(providers.environmentVariable("WASMTIME_KMP_VERSION"))
    .orElse("unspecified")
    .get()

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }

    sourceSets.wasmWasiMain.dependencies {
        implementation(libs.kotlin.coroutines.core)
    }
}
