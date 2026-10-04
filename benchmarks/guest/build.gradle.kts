plugins {
    id("dev.brahmkshatriya.wasmtime.extension")
}

group = property("GROUP").toString()
version = property("VERSION").toString()

wasmtimeExtension {
    outputFileName.set("benchmark-guest.wasm")
}

kotlin {
    sourceSets.wasmWasiMain.dependencies {
        // Keep the benchmark on the exact same guest dependency graph as the demo runtime.
        compileOnly(projects.demo.shared)
    }
}
