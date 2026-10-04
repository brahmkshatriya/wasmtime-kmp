import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val hostOs = System.getProperty("os.name").lowercase()
val isLinuxHost = hostOs.contains("linux")
val wasmtimeJvmNative = if (isLinuxHost) {
    val wasmtimeJvmNativeArch = when (System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "linux-x64"
        "aarch64", "arm64" -> "linux-arm64"
        else -> error("Unsupported JVM Linux architecture: ${System.getProperty("os.arch")}")
    }
    val wasmtimeJvmNativeArchAttribute = Attribute.of(
        "dev.brahmkshatriya.wasmtime.jvm.native.arch",
        String::class.java,
    )
    configurations.create("wasmtimeJvmNative") {
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes {
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named("wasmtime-jni"))
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(wasmtimeJvmNativeArchAttribute, wasmtimeJvmNativeArch)
        }
    }
} else {
    null
}

if (wasmtimeJvmNative != null) {
    dependencies {
        add(wasmtimeJvmNative.name, project(":lib"))
    }
}

kotlin {
    jvm()

    sourceSets.jvmMain.dependencies {
        implementation(projects.demo.client)
        implementation(compose.desktop.currentOs)
        if (wasmtimeJvmNative != null) {
            runtimeOnly(files(wasmtimeJvmNative))
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.brahmkshatriya.wasmtime.demo.MainKt"
    }
}
