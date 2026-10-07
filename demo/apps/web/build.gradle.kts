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
            testTask {
                useKarma {
                    useFirefoxHeadless()
                }
            }
        }
        binaries.executable()
    }

    sourceSets.wasmJsMain.dependencies {
        implementation(projects.demo.client)
        implementation(libs.bundles.compose.android)
    }
    sourceSets.wasmJsTest.dependencies {
        implementation(kotlin("test"))
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        implementation(projects.lib)
        implementation(projects.demo.shared)
    }
}

tasks.named<Copy>("wasmJsTestProcessResources") {
    dependsOn(":demo:client:wasmJsProcessResources")
    from(project(":demo:client").layout.buildDirectory.dir("processedResources/wasmJs/main"))
}
