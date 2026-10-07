@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.brahmkshatriya.wasmtime.demo

import dev.brahmkshatriya.wasmtime.WasmtimeRemoteCallback
import dev.brahmkshatriya.wasmtime.createWasmtimeExtensionTransport
import dev.brahmkshatriya.wasmtime.demo.generated.resources.Res
import dev.brahmkshatriya.wasmtime.demo.shared.FeedPageRequest
import dev.brahmkshatriya.wasmtime.demo.shared.Page
import dev.brahmkshatriya.wasmtime.demo.shared.Shelf
import dev.brahmkshatriya.wasmtime.generated.MusicExtensionClientWasmtimeProxy
import dev.brahmkshatriya.wasmtime.generated.WasmtimeBundledExtensions
import dev.brahmkshatriya.wasmtime.loadWasmtimeBundledRuntime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val testCbor = Cbor { ignoreUnknownKeys = true }

class PersistentWorkerTest {
    @Test
    fun echoStyleHostInjectionAndFeedWork() = runTest {
        val home = loadHomePreview(
            plugin = demoPlugins.first(),
            storageRoot = "wasmtime-browser-preview-test",
        )
        assertEquals(2, home.shelves.size)
        assertTrue(home.messages.isNotEmpty())
    }

    @Test
    fun guestPagerSurvivesAcrossInvocations() = runTest {
        val runtime = loadWasmtimeBundledRuntime(
            manifestResourcePath = WasmtimeBundledExtensions.runtimeManifestResourcePath,
            readResource = Res::readBytes,
        )
        val artifact = WasmtimeBundledExtensions.plugin1
        val transport = createWasmtimeExtensionTransport(
            wasm = Res.readBytes(artifact.wasmResourcePath),
            runtime = runtime,
        )
        try {
            assertTrue(transport.supportsPersistentResources)
            val api = MusicExtensionClientWasmtimeProxy(transport)
            val feed = api.loadHomeFeed()
            val pager = WasmtimeRemoteCallback(transport, feed.pagerHandle)
            try {
                val first = testCbor.decodeFromByteArray<Page<Shelf>>(
                    pager.invoke(
                        testCbor.encodeToByteArray(
                            FeedPageRequest(feed.tabs.first().id, null)
                        )
                    )
                )
                val continuation = requireNotNull(first.continuation)
                val second = testCbor.decodeFromByteArray<Page<Shelf>>(
                    pager.invoke(
                        testCbor.encodeToByteArray(
                            FeedPageRequest(feed.tabs.first().id, continuation)
                        )
                    )
                )
                assertEquals(1, first.data.size)
                assertEquals(1, second.data.size)
                assertEquals(null, second.continuation)
            } finally {
                pager.release()
            }
        } finally {
            transport.close()
        }
    }
}
