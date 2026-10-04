package dev.brahmkshatriya.wasmtime.extension

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Shared guest-side state machine used by generated Wasmtime extension adapters.
 *
 * Extension modules keep only their contract adapter and implementation. The host
 * provides this module as part of the shared open-world runtime.
 */
public class WasmtimeExtensionCall(
    private val execute: suspend (methodId: Int) -> ByteArray,
) {
    private val dispatcher = ExtensionDispatcher()
    private val exceptionHandler = CoroutineExceptionHandler { _, _ -> }

    private var state: Int = IDLE
    private var operation: Job? = null
    private var pendingResult: ByteArray = ByteArray(0)
    private var resultBytes: ByteArray = ByteArray(0)
    private var errorBytes: ByteArray = ByteArray(0)

    public fun start(): Int = start(0)

    public fun start(methodId: Int): Int {
        if (state == PENDING) return PENDING

        operation?.cancel()
        operation = null
        dispatcher.reset()
        pendingResult = ByteArray(0)
        resultBytes = ByteArray(0)
        errorBytes = ByteArray(0)
        state = PENDING

        val job = CoroutineScope(dispatcher + exceptionHandler).launch(
            start = CoroutineStart.UNDISPATCHED,
        ) {
            pendingResult = execute(methodId)
        }
        operation = job
        job.invokeOnCompletion { cause ->
            when {
                cause == null -> {
                    resultBytes = pendingResult
                    state = SUCCESS
                }
                cause is CancellationException -> {
                    errorBytes = (cause.message ?: "cancelled").encodeToByteArray()
                    state = CANCELLED
                }
                else -> {
                    errorBytes = (cause.message ?: cause.toString()).encodeToByteArray()
                    state = FAILURE
                }
            }
        }

        dispatcher.runReady()
        return state
    }

    public fun poll(elapsedMillis: Int): Int {
        if (state != PENDING) return state
        dispatcher.advance(elapsedMillis.coerceAtLeast(0))
        return state
    }

    public fun nextWakeMillis(): Int =
        if (state == PENDING) dispatcher.nextWakeMillis() else 0

    public fun cancel(): Int {
        if (state == PENDING) {
            operation?.cancel(CancellationException("host cancelled plugin call"))
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

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            ready.addLast(block)
        }

        override fun scheduleResumeAfterDelay(
            timeMillis: Long,
            continuation: CancellableContinuation<Unit>,
        ) {
            val task = schedule(timeMillis) {
                continuation.resume(Unit) { _, _, _ -> }
            }
            continuation.invokeOnCancellation { task.dispose() }
        }

        override fun invokeOnTimeout(
            timeMillis: Long,
            block: Runnable,
            context: CoroutineContext,
        ): DisposableHandle = schedule(timeMillis) { block.run() }

        fun reset() {
            ready.clear()
            scheduled.clear()
            virtualTimeMillis = 0L
        }

        fun runReady() {
            while (ready.isNotEmpty()) {
                ready.removeFirst().run()
            }
        }

        fun advance(elapsedMillis: Int) {
            virtualTimeMillis += elapsedMillis.toLong()
            if (scheduled.isNotEmpty()) {
                val due = scheduled.filter { !it.disposed && it.dueAtMillis <= virtualTimeMillis }
                scheduled.removeAll { it.disposed || it.dueAtMillis <= virtualTimeMillis }
                due.forEach { task ->
                    if (!task.disposed) task.action()
                }
            }
            runReady()
        }

        fun nextWakeMillis(): Int {
            if (ready.isNotEmpty()) return 0
            scheduled.removeAll { it.disposed }
            val dueAt = scheduled.minOfOrNull { it.dueAtMillis } ?: return -1
            return (dueAt - virtualTimeMillis)
                .coerceAtLeast(0L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }

        private fun schedule(delayMillis: Long, action: () -> Unit): ScheduledTask {
            val dueAt = virtualTimeMillis + delayMillis.coerceAtLeast(0L)
            return ScheduledTask(dueAt, action).also(scheduled::add)
        }
    }

    private class ScheduledTask(
        val dueAtMillis: Long,
        val action: () -> Unit,
    ) : DisposableHandle {
        var disposed: Boolean = false
            private set

        override fun dispose() {
            disposed = true
        }
    }

    public companion object {
        public const val PENDING: Int = 0
        public const val SUCCESS: Int = 1
        public const val FAILURE: Int = 2
        public const val CANCELLED: Int = 3
        private const val IDLE: Int = -1
    }
}
