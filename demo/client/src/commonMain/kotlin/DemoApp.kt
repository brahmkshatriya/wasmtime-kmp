package dev.brahmkshatriya.wasmtime.demo

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
        state = runCatching { loadHomePreview(selected, storageRoot) }
            .fold(
                onSuccess = { UiState.Success(it) },
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
                Text("Wasmtime KMP · Echo-style extensions", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "The root API is composed from extension, feed, track, settings, and message capabilities. " +
                        "Feed paging stays in the guest as a persistent resource; settings/messages are injected by the host."
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    demoPlugins.forEach { plugin ->
                        Button(onClick = { selected = plugin }) {
                            Text(plugin.name)
                        }
                    }
                }

                when (val value = state) {
                    UiState.Loading -> Text("Loading ${selected.name}…")
                    is UiState.Error -> Text("Extension failed: ${value.message}")
                    is UiState.Success -> {
                        val home = value.value
                        Text(home.metadataName, style = MaterialTheme.typography.headlineSmall)
                        Text(home.description)
                        home.messages.lastOrNull()?.let { Text("MessageFlow: ${it.text}") }
                        home.shelves.forEach { shelf ->
                            Text(shelf.title, style = MaterialTheme.typography.titleMedium)
                            shelf.subtitle?.let { Text(it) }
                            shelf.tracks.forEach { track ->
                                Text("• ${track.title} — ${track.artist}")
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface UiState {
    data object Loading : UiState
    data class Success(val value: LoadedHome) : UiState
    data class Error(val message: String) : UiState
}
