package dev.brahmkshatriya.wasmtime.demo.shared

/** Base client implemented by every demo extension, following Echo's extension-client pattern. */
interface ExtensionClient : SettingsProvider {
    suspend fun metadata(): ExtensionMetadata
    suspend fun onInitialize()
    suspend fun onExtensionSelected()
}

/** Echo-style capability for the application's home surface. */
interface HomeFeedClient {
    suspend fun loadHomeFeed(): Feed
}

/** Echo-style capability for resolving a playable/media item. */
interface TrackClient {
    suspend fun loadTrack(trackId: Int): Track
}

/** Host-owned settings are injected as a capability instead of copied into the guest. */
interface SettingsProvider {
    suspend fun getSettingItems(): List<SettingItem>
    suspend fun setSettings(settingsHandle: Long)
}

/** Wasm-friendly equivalent of Echo's MessageFlowProvider injection. */
interface MessageFlowProvider {
    suspend fun setMessageFlow(messageHandle: Long)
}

/**
 * Demo-only capabilities kept to exercise the lower-level runtime features alongside the Echo-shaped API.
 */
interface DiagnosticsClient {
    suspend fun echoProducts(products: List<Product>): List<Product>
    suspend fun invokeHostCallback(handle: Long, value: String): String
    suspend fun sumHostStream(handle: Long): Int
    suspend fun readResourceSize(name: String): Int
    suspend fun credentialAvailable(): Boolean
    suspend fun failForTest()
}

/**
 * Root contract. Like Echo's MusicExtension clients, features are expressed as small capability interfaces.
 * All cross-boundary members are suspend so the same contract works through Browser Web Workers.
 */
interface MusicExtensionClient :
    ExtensionClient,
    HomeFeedClient,
    TrackClient,
    MessageFlowProvider,
    DiagnosticsClient
