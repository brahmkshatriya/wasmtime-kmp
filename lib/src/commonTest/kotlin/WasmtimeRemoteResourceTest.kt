package dev.brahmkshatriya.wasmtime

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class WasmtimeRemoteResourceTest {
    @Test
    fun synchronousResourceCallUsesReservedFraming() {
        val transport = RecordingTransport()
        val resource = WasmtimeRemoteResource(
            transport = transport,
            handle = 0x0102030405060708L,
        )

        val response = resource.invokeSync(
            operation = 0x11223344,
            payload = byteArrayOf(9, 8, 7),
        )

        assertContentEquals(byteArrayOf(4, 5, 6), response)
        assertEquals(EXTENSION_RESOURCE_METHOD_ID, transport.methodId)
        assertContentEquals(
            byteArrayOf(
                0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01,
                0x44, 0x33, 0x22, 0x11,
                9, 8, 7,
            ),
            transport.arguments,
        )
    }

    @Test
    fun rejectsResourcesOnStatelessTransport() {
        val transport = object : WasmtimeExtensionTransport {
            override suspend fun invoke(methodId: Int, arguments: ByteArray): ByteArray = ByteArray(0)
        }

        assertFailsWith<IllegalArgumentException> {
            WasmtimeRemoteResource(transport, 1L)
        }
    }

    @Test
    fun hostRegistryInvokesAndReleasesResources() {
        val registry = WasmtimeHostResourceRegistry(maxResources = 2)
        var closed = false
        val handle = runImmediate {
            registry.register(
                handler = WasmtimeHostResourceHandler { operation, payload ->
                    byteArrayOf(operation.toByte()) + payload
                },
                close = { closed = true },
            )
        }

        val response = runImmediate {
            registry.invokeOrNull(handle, 7, byteArrayOf(1, 2))
        }
        assertContentEquals(byteArrayOf(7, 1, 2), response)

        runImmediate { registry.release(handle) }
        assertEquals(true, closed)
        assertNull(runImmediate { registry.invokeOrNull(handle, 7, ByteArray(0)) })
    }

    private class RecordingTransport : WasmtimeExtensionTransport {
        override val supportsPersistentResources: Boolean = true
        var methodId: Int? = null
        var arguments: ByteArray? = null

        override suspend fun invoke(methodId: Int, arguments: ByteArray): ByteArray =
            error("suspend invocation is not expected in this test")

        override fun invokeSync(methodId: Int, arguments: ByteArray): ByteArray {
            this.methodId = methodId
            this.arguments = arguments
            return byteArrayOf(4, 5, 6)
        }
    }
}

private fun <T> runImmediate(block: suspend () -> T): T {
    var completed: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) {
            completed = result
        }
    })
    return requireNotNull(completed) { "test coroutine unexpectedly suspended" }.getOrThrow()
}
