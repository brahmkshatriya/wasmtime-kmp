package dev.brahmkshatriya.wasmtime.demo

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "webApp") {
        DemoApp("wasmtime-kmp-demo/plugins")
    }
}
