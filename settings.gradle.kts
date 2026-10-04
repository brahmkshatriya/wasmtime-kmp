pluginManagement {
    includeBuild("gradle-plugin")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
        maven("https://redirector.kotlinlang.org/maven/compose-dev")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "wasmtime-kmp"

include(":lib")
include(":lib:guest-runtime")
include(":demo:shared")
include(":demo:plugin1")
include(":demo:plugin2")
include(":demo:client")
include(":demo:apps:android")
include(":demo:apps:linux")
include(":demo:apps:jvm")
include(":demo:apps:web")
include(":benchmarks:guest")
include(":benchmarks:linux")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
