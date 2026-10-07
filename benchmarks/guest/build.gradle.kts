plugins {
    id("dev.brahmkshatriya.wasmtime.extension")
}

group = property("GROUP").toString()

wasmtimeExtension {
    extensionId.set("benchmark-guest")
    displayName.set("Wasmtime Benchmark Guest")
    contractInterface.set("benchmark.raw")
    capabilities("Http", "PersistentStorage")
}

kotlin {
    sourceSets.wasmWasiMain.dependencies {
        compileOnly(projects.demo.shared)
        compileOnly("dev.brahmkshatriya.ktorwasi:ktor-client-wasi:3.6.0")
        compileOnly("org.jetbrains.kotlinx:kotlinx-io-core:0.9.1")
    }
}
