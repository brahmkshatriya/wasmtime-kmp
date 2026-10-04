package dev.brahmkshatriya.wasmtime.demo

import dev.brahmkshatriya.wasmtime.demo.shared.Plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DemoApp(storageRoot: String) {
    var selected by remember { mutableStateOf(demoPlugins.first()) }
    var state by remember { mutableStateOf<UiState>(UiState.Loading) }

    LaunchedEffect(selected, storageRoot) {
        state = UiState.Loading
        state = runCatching {
            val plugin: Plugin = loadPlugin(selected, storageRoot)
            val product = plugin.getProductDetails()
            LoadedProduct(selected, product)
        }.fold(
                onSuccess = {
                    println("${it.plugin.name} -> ${it.product.id}: ${it.product.title}")
                    UiState.Success(it)
                },
                onFailure = {
                    val message = it.message ?: it::class.simpleName ?: "unknown error"
                    println("${selected.name} failed: $message")
                    UiState.Error(message)
                },
            )
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Wasmtime KMP plugins", style = MaterialTheme.typography.headlineMedium)
                Text("Plugins get sandboxed Ktor networking and persistent /data storage.")

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    demoPlugins.forEach { plugin ->
                        Button(onClick = { selected = plugin }) {
                            Text(plugin.name)
                        }
                    }
                }

                when (val value = state) {
                    UiState.Loading -> Text("Loading ${selected.name}…")
                    is UiState.Error -> Text("Plugin failed: ${value.message}")
                    is UiState.Success -> {
                        val loaded = value.value
                        val product = loaded.product
                        Text(loaded.plugin.name)
                        Text(product.title, style = MaterialTheme.typography.headlineSmall)
                        Text(product.description)
                        Text("$${product.price} · rating ${product.rating} · stock ${product.stock}")
                        Text("${product.brand ?: "No brand"} · ${product.sku} · ${product.category}")
                    }
                }
            }
        }
    }
}

private sealed interface UiState {
    data object Loading : UiState
    data class Success(val value: LoadedProduct) : UiState
    data class Error(val message: String) : UiState
}
