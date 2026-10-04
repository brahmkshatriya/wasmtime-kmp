package dev.brahmkshatriya.wasmtime.internal

internal actual object PlatformNativeLoader {
    actual fun load() {
        System.loadLibrary("wasmtime_kmp")
    }

    actual fun cacheDirectory(): String? =
        System.getProperty("java.io.tmpdir")
            ?.takeIf(String::isNotBlank)
            ?.let { java.io.File(it, "wasmtime-kmp").absolutePath }
}
