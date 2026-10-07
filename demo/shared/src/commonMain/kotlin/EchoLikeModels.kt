package dev.brahmkshatriya.wasmtime.demo.shared

import kotlinx.serialization.Serializable

@Serializable
data class ExtensionMetadata(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
)

@Serializable
data class Track(
    val id: Int,
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
)

@Serializable
data class Tab(
    val id: String,
    val title: String,
)

@Serializable
data class Shelf(
    val title: String,
    val subtitle: String? = null,
    val tracks: List<Track> = emptyList(),
)

/** Serializable part of an Echo-like Feed. The pager itself stays in the guest as a resource. */
@Serializable
data class Feed(
    val tabs: List<Tab>,
    val pagerHandle: Long,
)

@Serializable
data class Page<T>(
    val data: List<T>,
    val continuation: String? = null,
)

@Serializable
data class FeedPageRequest(
    val tabId: String? = null,
    val continuation: String? = null,
)

@Serializable
data class SettingItem(
    val key: String,
    val title: String,
    val description: String,
    val defaultValue: String,
)

@Serializable
enum class SettingsOperation {
    GetString,
    PutString,
}

@Serializable
data class SettingsRequest(
    val operation: SettingsOperation,
    val key: String,
    val value: String? = null,
    val defaultValue: String? = null,
)

@Serializable
data class SettingsResponse(
    val value: String? = null,
)

@Serializable
data class ExtensionMessage(
    val text: String,
)
