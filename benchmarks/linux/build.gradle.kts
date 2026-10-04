import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    linuxX64 {
        binaries.executable {
            baseName = "wasmtime-kmp-benchmark"
            entryPoint = "dev.brahmkshatriya.wasmtime.benchmark.main"
            linkTaskProvider.configure {
                dependsOn(":benchmarks:guest:exportWasmtimeExtension")
                dependsOn(":demo:client:buildWasmtimeRuntime")
            }
            linkerOpts(
                "-L/usr/lib",
                "-Wl,--gc-sections",
                "-Wl,--exclude-libs,ALL",
            )
            if (buildType == NativeBuildType.RELEASE) linkerOpts("-Wl,--strip-all")
        }
    }

    sourceSets.linuxX64Main.dependencies {
        implementation(projects.lib)
        implementation(libs.kotlin.coroutines.core)
        implementation("io.ktor:ktor-client-core:3.6.0")
        implementation("io.ktor:ktor-client-curl:3.6.0")
        implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.9.1")
    }
}
