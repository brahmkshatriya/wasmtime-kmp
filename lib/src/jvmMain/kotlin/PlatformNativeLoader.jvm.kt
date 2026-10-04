package dev.brahmkshatriya.wasmtime.internal

import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal actual object PlatformNativeLoader {
    private var loaded = false

    actual fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching { System.loadLibrary("wasmtime_kmp") }
                .getOrElse {
                    val os = System.getProperty("os.name").lowercase()
                    check(os.contains("linux")) { "Wasmtime JVM native library is unsupported on $os" }
                    val resourceArch = when (System.getProperty("os.arch").lowercase()) {
                        "amd64", "x86_64" -> "linux-x64"
                        "aarch64", "arm64" -> "linux-arm64"
                        else -> error("Wasmtime JVM native library is unsupported on ${System.getProperty("os.arch")}")
                    }
                    val resource = PlatformNativeLoader::class.java
                        .getResourceAsStream("/native/$resourceArch/libwasmtime_kmp.so")
                        ?: throw IllegalStateException("Wasmtime JVM native library is unavailable for $resourceArch", it)
                    val extracted = Files.createTempFile("wasmtime-kmp-", ".so")
                    extracted.toFile().deleteOnExit()
                    resource.use { input ->
                        Files.copy(input, extracted, StandardCopyOption.REPLACE_EXISTING)
                    }
                    System.load(extracted.toAbsolutePath().toString())
                }
            loaded = true
        }
    }

    actual fun cacheDirectory(): String? =
        System.getProperty("user.home")
            ?.takeIf(String::isNotBlank)
            ?.let { java.nio.file.Paths.get(it, ".cache", "wasmtime-kmp").toString() }
}
