package dev.brahmkshatriya.wasmtime.extension

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine

/** Guest-side coroutine state machine used by generated Wasmtime extension adapters. */
public class WasmtimeExtensionCall(
    private val execute: suspend (methodId: Int, arguments: ByteArray) -> WasmtimeExtensionExecution,
) {
    private val dispatcher = ExtensionDispatcher()

    private var state: Int = IDLE
    private var operation: CompletableJob? = null
    private var argumentBytes: ByteArray = ByteArray(0)
    private var pendingResult: ByteArray = ByteArray(0)
    private var resultBytes: ByteArray = ByteArray(0)
    private var errorBytes: ByteArray = ByteArray(0)

    /** Allocates the host-to-guest argument payload for the next call. */
    public fun prepareArguments(length: Int): Int {
        if (state == PENDING || length < 0) return -1
        argumentBytes = ByteArray(length)
        return 0
    }

    /** Writes one unsigned byte into the argument payload prepared by [prepareArguments]. */
    public fun setArgumentByte(index: Int, value: Int): Int {
        if (state == PENDING || index !in argumentBytes.indices || value !in 0..255) return -1
        argumentBytes[index] = value.toByte()
        return 0
    }

    /** Starts [methodId] unless another operation is already pending. */
    public fun start(methodId: Int): Int {
        if (state == PENDING) return PENDING

        operation?.cancel()
        operation = null
        dispatcher.reset()
        pendingResult = ByteArray(0)
        resultBytes = ByteArray(0)
        errorBytes = ByteArray(0)
        state = PENDING
        val arguments = argumentBytes
        argumentBytes = ByteArray(0)

        val job = Job()
        operation = job
        val completion = object : Continuation<Unit> {
            override val context: CoroutineContext = dispatcher + job

            override fun resumeWith(result: Result<Unit>) {
                if (result.isFailure && state == PENDING) {
                    errorBytes = WasmtimeExtensionExecution.failure(
                        type = "RuntimeFailure",
                        message = "guest coroutine failed outside the extension adapter",
                    ).payload
                    state = FAILURE
                }
                job.complete()
            }
        }
        suspend {
            val execution = execute(methodId, arguments)
            when (execution.state) {
                SUCCESS -> {
                    pendingResult = execution.payload
                    resultBytes = execution.payload
                    state = SUCCESS
                }
                FAILURE -> {
                    errorBytes = execution.payload
                    state = FAILURE
                }
                CANCELLED -> {
                    errorBytes = execution.payload
                    state = CANCELLED
                }
                else -> {
                    errorBytes = WasmtimeExtensionExecution.failure(
                        type = "ProtocolError",
                        message = "invalid extension execution state: ${execution.state}",
                    ).payload
                    state = FAILURE
                }
            }
        }.startCoroutine(completion)

        dispatcher.runReady()
        return state
    }

    /** Advances guest virtual time by [elapsedMillis], runs ready coroutines, and returns the current state. */
    public fun poll(elapsedMillis: Int): Int {
        if (state != PENDING) return state
        dispatcher.advance(elapsedMillis.coerceAtLeast(0))
        return state
    }

    public fun nextWakeMillis(): Int = if (state == PENDING) dispatcher.nextWakeMillis() else 0

    public fun cancel(): Int {
        if (state == PENDING) {
            operation?.cancel(CancellationException("host cancelled extension call"))
            dispatcher.runReady()
        }
        return state
    }

    public fun resultLength(): Int = if (state == SUCCESS) resultBytes.size else -1
    public fun resultByte(index: Int): Int = resultBytes[index].toInt() and 0xff
    public fun errorLength(): Int = if (state == FAILURE || state == CANCELLED) errorBytes.size else 0
    public fun errorByte(index: Int): Int = errorBytes[index].toInt() and 0xff

    @OptIn(InternalCoroutinesApi::class)
    private class ExtensionDispatcher : CoroutineDispatcher(), Delay {
        private val ready = ArrayDeque<Runnable>()
        private val scheduled = mutableListOf<ScheduledTask>()
        private var virtualTimeMillis = 0L

        override fun dispatch(context: CoroutineContext, block: Runnable) { ready.addLast(block) }

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            val task = schedule(timeMillis) { continuation.resume(Unit) { _, _, _ -> } }
            continuation.invokeOnCancellation { task.dispose() }
        }

        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle =
            schedule(timeMillis) { block.run() }

        fun reset() {
            ready.clear()
            scheduled.clear()
            virtualTimeMillis = 0L
        }

        fun runReady() {
            while (ready.isNotEmpty()) ready.removeFirst().run()
        }

        fun advance(elapsedMillis: Int) {
            virtualTimeMillis += elapsedMillis.toLong()
            if (scheduled.isNotEmpty()) {
                val due = scheduled.filter { !it.disposed && it.dueAtMillis <= virtualTimeMillis }
                scheduled.removeAll { it.disposed || it.dueAtMillis <= virtualTimeMillis }
                due.forEach { task -> if (!task.disposed) task.action() }
            }
            runReady()
        }

        fun nextWakeMillis(): Int {
            if (ready.isNotEmpty()) return 0
            scheduled.removeAll { it.disposed }
            val dueAt = scheduled.minOfOrNull { it.dueAtMillis } ?: return -1
            return (dueAt - virtualTimeMillis).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        private fun schedule(delayMillis: Long, action: () -> Unit): ScheduledTask =
            ScheduledTask(virtualTimeMillis + delayMillis.coerceAtLeast(0L), action).also(scheduled::add)
    }

    private class ScheduledTask(val dueAtMillis: Long, val action: () -> Unit) : DisposableHandle {
        var disposed: Boolean = false
            private set
        override fun dispose() { disposed = true }
    }

    public companion object {
        public const val PENDING: Int = 0
        public const val SUCCESS: Int = 1
        public const val FAILURE: Int = 2
        public const val CANCELLED: Int = 3
        private const val IDLE: Int = -1
    }
}


/** Plain cross-module completion value returned by generated extension adapters. */
public class WasmtimeExtensionExecution private constructor(
    public val state: Int,
    public val payload: ByteArray,
) {
    public companion object {
        public fun success(bytes: ByteArray): WasmtimeExtensionExecution =
            WasmtimeExtensionExecution(WasmtimeExtensionCall.SUCCESS, bytes)

        public fun failure(
            type: String,
            message: String,
            stack: String = "",
        ): WasmtimeExtensionExecution = WasmtimeExtensionExecution(
            WasmtimeExtensionCall.FAILURE,
            encodeFailurePayload(type, message, stack),
        )

        public fun cancelled(
            type: String = "CancellationException",
            message: String = "extension call cancelled",
            stack: String = "",
        ): WasmtimeExtensionExecution = WasmtimeExtensionExecution(
            WasmtimeExtensionCall.CANCELLED,
            encodeFailurePayload(type, message, stack),
        )
    }
}

private fun encodeFailurePayload(type: String, message: String, stack: String): ByteArray {
    val fields = listOf(type, message, stack).map(String::encodeToByteArray)
    val output = ByteArray(4 + fields.sumOf { 4 + it.size })
    output[0] = 'W'.code.toByte()
    output[1] = 'E'.code.toByte()
    output[2] = 'X'.code.toByte()
    output[3] = 1
    var offset = 4
    for (field in fields) {
        val size = field.size
        output[offset++] = size.toByte()
        output[offset++] = (size ushr 8).toByte()
        output[offset++] = (size ushr 16).toByte()
        output[offset++] = (size ushr 24).toByte()
        field.copyInto(output, offset)
        offset += size
    }
    return output
}
