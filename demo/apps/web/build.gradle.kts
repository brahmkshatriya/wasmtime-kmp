plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "wasmtime-kmp-demo.js"
            }
        }
        binaries.executable()
    }

    sourceSets.wasmJsMain.dependencies {
        implementation(projects.demo.client)
        implementation(libs.bundles.compose.android)
    }
}
