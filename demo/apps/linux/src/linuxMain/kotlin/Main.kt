@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.wasmtime.demo

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.getenv

private fun storageRoot(): String {
    val xdg = getenv("XDG_DATA_HOME")?.toKString()
    if (!xdg.isNullOrBlank()) return "$xdg/wasmtime-kmp-demo/plugins"
    val home = getenv("HOME")?.toKString() ?: "."
    return "$home/.local/share/wasmtime-kmp-demo/plugins"
}

fun main(args: Array<String>) {
    if ("--smoke-extension" in args) {
        runBlocking { runDemoExtensionSmoke(storageRoot()) }
        return
    }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Wasmtime KMP",
            state = rememberWindowState(size = DpSize(640.dp, 360.dp)),
        ) {
            DemoApp(storageRoot())
        }
    }
}
