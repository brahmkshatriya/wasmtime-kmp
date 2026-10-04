import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeNative)
}

kotlin {
    linuxX64 {
        binaries.executable {
            baseName = "wasmtime-kmp-demo"
            entryPoint = "dev.brahmkshatriya.wasmtime.demo.main"
            linkerOpts(
                "-L/usr/lib",
                "-Wl,--gc-sections",
                "-Wl,--exclude-libs,ALL",
            )
            if (buildType == NativeBuildType.RELEASE) {
                linkerOpts("-Wl,--strip-all")
            }
        }
    }

    linuxArm64 {
        binaries.executable {
            baseName = "wasmtime-kmp-demo"
            entryPoint = "dev.brahmkshatriya.wasmtime.demo.main"
            linkerOpts(
                "-Wl,--gc-sections",
                "-Wl,--exclude-libs,ALL",
            )
            if (buildType == NativeBuildType.RELEASE) {
                linkerOpts("-Wl,--strip-all")
            }
        }
    }

    sourceSets {
        val commonMain = getByName("commonMain")
        val linuxMain = maybeCreate("linuxMain").apply { dependsOn(commonMain) }
        getByName("linuxX64Main").dependsOn(linuxMain)
        getByName("linuxArm64Main").dependsOn(linuxMain)
        linuxMain.dependencies {
            implementation(projects.demo.client)
            implementation(libs.bundles.compose.native)
            implementation(libs.compose.native.desktop)
        }
    }
}
