package dev.brahmkshatriya.wasmtime.demo

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Wasmtime KMP",
        state = rememberWindowState(size = DpSize(640.dp, 420.dp)),
    ) {
        DemoApp(System.getProperty("user.home") + "/.local/share/wasmtime-kmp-demo/plugins")
    }
}
