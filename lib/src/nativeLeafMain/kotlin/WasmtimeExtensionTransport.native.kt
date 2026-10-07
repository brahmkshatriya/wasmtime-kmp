package dev.brahmkshatriya.wasmtime

internal actual fun createPlatformWasmtimeExtensionTransport(
    wasm: ByteArray,
    limits: WasmtimeLimits,
    httpHandler: WasmtimeHttpHandler?,
    storage: WasmtimeStorage?,
    runtime: WasmtimeRuntime?,
    maxArgumentBytes: Int,
    maxResultBytes: Int,
): WasmtimeExtensionTransport? = null
