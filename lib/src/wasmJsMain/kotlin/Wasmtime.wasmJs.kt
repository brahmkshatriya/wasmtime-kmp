@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.brahmkshatriya.wasmtime

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.JsFun
import kotlin.js.Promise
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

internal actual fun createPlatformWasmtimeModule(
    wasm: ByteArray,
): PlatformWasmtimeModule = WasmJsWasmtimeModule(jsCompileModule(wasm.toJsUint8Array()))

internal actual fun createPlatformWasmtimeInstance(
    wasm: ByteArray,
    limits: WasmtimeLimits,
    httpHandler: WasmtimeHttpHandler?,
    storage: WasmtimeStorage?,
    runtime: WasmtimeRuntime?,
): PlatformWasmtimeInstance = WasmJsWasmtimeModule(jsCompileModule(wasm.toJsUint8Array()))
    .instantiate(limits, httpHandler, storage, runtime)


internal actual fun createPlatformWasmtimeExtensionTransport(
    wasm: ByteArray,
    limits: WasmtimeLimits,
    httpHandler: WasmtimeHttpHandler?,
    storage: WasmtimeStorage?,
    runtime: WasmtimeRuntime?,
    maxArgumentBytes: Int,
    maxResultBytes: Int,
): WasmtimeExtensionTransport? {
    if (!jsWorkerAvailable()) {
        return if (jsIsBrowser()) {
            object : WasmtimeExtensionTransport {
                override suspend fun invoke(methodId: Int, arguments: ByteArray): ByteArray =
                    error("Web Workers are required to execute untrusted Wasm extensions in the browser")
            }
        } else {
            null
        }
    }
    return object : WasmtimeExtensionTransport {
        override suspend fun invoke(methodId: Int, arguments: ByteArray): ByteArray {
        require(maxArgumentBytes == 0 || arguments.size <= maxArgumentBytes) {
            "extension argument payload is too large: ${arguments.size} > $maxArgumentBytes bytes"
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val task = jsRunExtensionWorker(
            instantiateSource = WASM_JS_HOST_SOURCE,
            wasm = wasm.toJsUint8Array(),
            runtimeModules = runtime.toJsRuntimeModules(),
            storagePath = storage?.backingPath ?: "",
            guestPath = storage?.guestPath ?: "/data",
            storageReadOnly = storage?.readOnly ?: true,
            maxMemoryBytes = limits.maxMemoryBytes.toDouble(),
            maxTableElements = limits.maxTableElements.toDouble(),
            maxModuleBytes = limits.maxModuleBytes,
            maxHostCallBytes = limits.maxHostCallBytes,
            maxOutputBytes = limits.maxOutputBytes.toDouble(),
            maxWasiPollMillis = limits.maxWasiPollMillis.toDouble(),
            storageMaxBytes = storage?.maxBytes?.toDouble() ?: 0.0,
            storageMaxEntries = storage?.maxEntries ?: 0,
            storageMaxFileBytes = storage?.maxFileBytes?.toDouble() ?: 0.0,
            maxExecutionMillis = limits.maxExecutionMillis.toDouble(),
            methodId = methodId,
            arguments = arguments.toJsUint8Array(),
            maxArgumentBytes = maxArgumentBytes,
            maxResultBytes = maxResultBytes,
        ) { metadata, body ->
            workerHttpPromise(scope, httpHandler, limits, metadata, body)
        }
        return try {
            task.awaitWorkerBytes()
        } finally {
            scope.cancel()
            jsCancelExtensionWorkerTask(task)
        }
        }
    }
}

private fun workerHttpPromise(
    scope: CoroutineScope,
    handler: WasmtimeHttpHandler?,
    limits: WasmtimeLimits,
    metadata: JsAny,
    body: JsAny,
): Promise<JsAny> = Promise { resolve, reject ->
    if (handler == null) {
        reject(jsError("HTTP capability is not available"))
    } else {
        scope.launch {
            try {
                val request = decodeHttpRequestMetadata(
                    metadata.toKotlinByteArray(),
                    body.toKotlinByteArray(),
                )
                val response = encodeBoundedHttpResponse(
                    handler.execute(request),
                    limits.maxHttpResponseBytes,
                )
                resolve(
                    jsHttpResponse(
                        response.metadata.toJsUint8Array(),
                        response.body.toJsUint8Array(),
                    )
                )
            } catch (throwable: Throwable) {
                reject(jsError(throwable.message ?: throwable.toString()))
            }
        }
    }
}

private suspend fun JsAny.awaitWorkerBytes(): ByteArray =
    suspendCancellableCoroutine { continuation ->
        jsExtensionWorkerTaskPromise(this).then<JsAny?>(
            onFulfilled = { value ->
                if (continuation.isActive) continuation.resume(value.toKotlinByteArray()) { _, _, _ -> }
                null
            },
            onRejected = { reason ->
                if (continuation.isActive) {
                    val message = jsErrorMessage(reason)
                    when (jsErrorName(reason)) {
                        "WasmtimeExtensionCancelled", "WasmtimeExtensionTimeout" ->
                            continuation.resumeWithException(CancellationException(message))
                        "WasmtimeExtensionRemote" ->
                            continuation.resumeWithException(
                                WasmtimeExtensionException(
                                    remoteType = jsErrorRemoteType(reason).ifBlank { "Throwable" },
                                    message = message,
                                    guestStackTrace = jsErrorGuestStack(reason).ifBlank { null },
                                )
                            )
                        else -> continuation.resumeWithException(IllegalStateException(message))
                    }
                }
                null
            },
        )
        continuation.invokeOnCancellation { jsCancelExtensionWorkerTask(this) }
    }

private class WasmJsWasmtimeModule(
    private var module: JsAny?,
) : PlatformWasmtimeModule {
    override fun instantiate(
        limits: WasmtimeLimits,
        httpHandler: WasmtimeHttpHandler?,
        storage: WasmtimeStorage?,
        runtime: WasmtimeRuntime?,
    ): PlatformWasmtimeInstance {
        val current = checkNotNull(module) { "Wasmtime module is closed" }
        return WasmJsWasmtimeInstance(current, limits, httpHandler, storage, runtime)
    }

    override fun close() {
        module = null
    }
}

private class WasmJsWasmtimeInstance(
    module: JsAny,
    private val limits: WasmtimeLimits,
    private val httpHandler: WasmtimeHttpHandler?,
    storage: WasmtimeStorage?,
    linkedRuntime: WasmtimeRuntime?,
) : PlatformWasmtimeInstance {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runtime: JsAny? = jsInstantiateModule(
        module,
        linkedRuntime.toJsRuntimeModules(),
        storage?.backingPath ?: "",
        storage?.guestPath ?: "/data",
        storage?.readOnly ?: true,
        limits.maxHostCallBytes,
        limits.maxOutputBytes.toDouble(),
        limits.maxWasiPollMillis.toDouble(),
        storage?.maxBytes?.toDouble() ?: 0.0,
        storage?.maxEntries ?: 0,
        storage?.maxFileBytes?.toDouble() ?: 0.0,
    ) { metadata, body ->
        executeHttp(metadata, body)
    }

    init {
        checkMemoryLimit()
    }

    override fun resolveI32(exportName: String): PlatformWasmtimeI32Function {
        val current = checkNotNull(runtime) { "Wasmtime instance is closed" }
        val function = jsGetExportedFunction(current, exportName)
            ?: error("export not found: $exportName")
        return WasmJsWasmtimeI32Function(this, function)
    }

    override fun close() {
        if (runtime == null) return
        scope.cancel()
        runtime = null
    }

    fun call(function: JsAny, first: Int, second: Int): Int {
        checkNotNull(runtime) { "Wasmtime instance is closed" }
        val result = jsCallI32(function, first, second)
        checkMemoryLimit()
        return result
    }

    suspend fun callAsync(function: JsAny, first: Int, second: Int): Int {
        checkNotNull(runtime) { "Wasmtime instance is closed" }
        val result = jsCallI32Async(function, first, second).awaitInt()
        checkMemoryLimit()
        return result
    }

    private fun checkMemoryLimit() {
        val current = runtime ?: return
        val max = limits.maxMemoryBytes
        if (max > 0) {
            check(jsMemorySize(current).toLong() <= max) {
                "Wasm memory limit exceeded: ${jsMemorySize(current)} > $max bytes"
            }
        }
    }

    private fun executeHttp(metadata: JsAny, body: JsAny): Promise<JsAny> =
        Promise { resolve, reject ->
            val handler = httpHandler
            if (handler == null) {
                reject(jsError("HTTP capability is not available"))
            } else {
                scope.launch {
                    try {
                        val request = decodeHttpRequestMetadata(
                            metadata.toKotlinByteArray(),
                            body.toKotlinByteArray(),
                        )
                        val response = encodeBoundedHttpResponse(
                            handler.execute(request),
                            limits.maxHttpResponseBytes,
                        )
                        resolve(
                            jsHttpResponse(
                                response.metadata.toJsUint8Array(),
                                response.body.toJsUint8Array(),
                            )
                        )
                    } catch (throwable: Throwable) {
                        reject(jsError(throwable.message ?: throwable.toString()))
                    }
                }
            }
        }
}

private class WasmJsWasmtimeI32Function(
    private val instance: WasmJsWasmtimeInstance,
    private var function: JsAny?,
) : PlatformWasmtimeI32Function {
    override fun call(first: Int, second: Int): Int =
        instance.call(checkNotNull(function) { "Wasmtime function is closed" }, first, second)

    override suspend fun callAsync(first: Int, second: Int): Int =
        instance.callAsync(checkNotNull(function) { "Wasmtime function is closed" }, first, second)

    override fun close() {
        function = null
    }
}

private fun ByteArray.toJsUint8Array(): JsAny {
    val array = jsNewUint8Array(size)
    for (index in indices) {
        jsSetUint8(array, index, this[index].toInt() and 0xff)
    }
    return array
}

private fun JsAny.toKotlinByteArray(): ByteArray {
    val size = jsArrayLength(this)
    return ByteArray(size) { index -> jsGetUint8(this, index).toByte() }
}

private suspend fun Promise<JsAny>.awaitInt(): Int =
    suspendCancellableCoroutine { continuation ->
        then<JsAny?>(
            onFulfilled = { value ->
                if (continuation.isActive) {
                    continuation.resume(jsToInt(value)) { _, _, _ -> }
                }
                null
            },
            onRejected = { reason ->
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        IllegalStateException(jsErrorMessage(reason))
                    )
                }
                null
            },
        )
    }

@JsFun("(size) => new Uint8Array(size)")
private external fun jsNewUint8Array(size: Int): JsAny

@JsFun("(array, index, value) => { array[index] = value & 255; }")
private external fun jsSetUint8(array: JsAny, index: Int, value: Int)

@JsFun("(array) => array.length")
private external fun jsArrayLength(array: JsAny): Int

@JsFun("(array, index) => array[index]")
private external fun jsGetUint8(array: JsAny, index: Int): Int

@JsFun("(bytes) => new WebAssembly.Module(bytes)")
private external fun jsCompileModule(bytes: JsAny): JsAny

private const val WASM_JS_HOST_SOURCE: String = """(module, runtimeModules, storagePath, guestPath, storageReadOnly, maxHostCallBytes, maxOutputBytes, maxWasiPollMillis, storageMaxBytes, storageMaxEntries, storageMaxFileBytes, executeHttp) => {
        const rt = {
            instance: null,
            reqMeta: null,
            reqBody: null,
            resMeta: null,
            resBody: null,
            storagePath,
            guestPath,
            storageReadOnly,
            maxHostCallBytes,
            maxOutputBytes,
            outputBytes: 0,
            maxWasiPollMillis,
            storageMaxBytes,
            storageMaxEntries,
            storageMaxFileBytes,
            storageBytes: 0,
            storageEntries: 0,
            storageRootPromise: null,
            fds: new Map(),
            nextFd: 4,
            memory: null,
        };
        const memory = () => {
            if (rt.memory === null) throw new Error('Kotlin/Wasm shared memory is not initialized');
            return rt.memory;
        };
        const dataView = () => new DataView(memory().buffer);
        const u8 = () => new Uint8Array(memory().buffer);
        const errno2big = 1;
        const errnoBadf = 8;
        const errnoExist = 20;
        const errnoInval = 28;
        const errnoIo = 29;
        const errnoNoent = 44;
        const errnoNospc = 51;
        const errnoNosys = 52;
        const errnoNotcapable = 76;
        const preopenFd = 3;
        const decoder = new TextDecoder();
        const encoder = new TextEncoder();

        const storageEnabled = () => rt.storagePath.length > 0;
        const safeParts = (path) => {
            if (encoder.encode(path).length > 4096) throw new Error('path is too long');
            const parts = path.split('/').filter(part => part.length > 0 && part !== '.');
            if (path.startsWith('/') || parts.some(part => part === '..' || part.includes('\0'))) {
                throw new Error('path escapes sandbox');
            }
            return parts;
        };
        const quotaCanAdd = (bytes, entries) =>
            (rt.storageMaxBytes <= 0 || rt.storageBytes + bytes <= rt.storageMaxBytes) &&
            (rt.storageMaxEntries <= 0 || rt.storageEntries + entries <= rt.storageMaxEntries);
        const quotaAdd = (bytes, entries) => {
            rt.storageBytes += bytes;
            rt.storageEntries += entries;
        };
        const quotaRemove = (bytes, entries) => {
            rt.storageBytes = Math.max(0, rt.storageBytes - bytes);
            rt.storageEntries = Math.max(0, rt.storageEntries - entries);
        };
        const scanStorage = async (dir, depth = 0) => {
            if (depth > 64) throw new Error('storage nesting is too deep');
            let bytes = 0;
            let entries = 0;
            for await (const handle of dir.values()) {
                entries++;
                if (handle.kind === 'file') {
                    const file = await handle.getFile();
                    if (rt.storageMaxFileBytes > 0 && file.size > rt.storageMaxFileBytes) {
                        throw new Error('existing storage file exceeds quota');
                    }
                    bytes += file.size;
                } else if (handle.kind === 'directory') {
                    const nested = await scanStorage(handle, depth + 1);
                    bytes += nested.bytes;
                    entries += nested.entries;
                } else {
                    throw new Error('unsupported OPFS entry type');
                }
                if ((rt.storageMaxBytes > 0 && bytes > rt.storageMaxBytes) ||
                    (rt.storageMaxEntries > 0 && entries > rt.storageMaxEntries)) {
                    throw new Error('existing storage exceeds quota');
                }
            }
            return { bytes, entries };
        };
        const storageRoot = async () => {
            if (!storageEnabled()) throw new Error('storage capability is unavailable');
            if (!navigator.storage || typeof navigator.storage.getDirectory !== 'function') {
                throw new Error('OPFS is unavailable');
            }
            if (rt.storageRootPromise === null) {
                rt.storageRootPromise = (async () => {
                    let dir = await navigator.storage.getDirectory();
                    for (const part of safeParts(rt.storagePath)) {
                        dir = await dir.getDirectoryHandle(part, { create: true });
                    }
                    const usage = await scanStorage(dir);
                    rt.storageBytes = usage.bytes;
                    rt.storageEntries = usage.entries;
                    return dir;
                })();
            }
            return rt.storageRootPromise;
        };
        const readPath = (ptr, len) => {
            if (len < 0 || len > 4096) throw new Error('invalid path length');
            const bytes = new Uint8Array(memory().buffer, ptr, len);
            const effectiveLength = len > 0 && bytes[len - 1] === 0 ? len - 1 : len;
            if (effectiveLength === 0) throw new Error('invalid empty path');
            const pathBytes = bytes.subarray(0, effectiveLength);
            if (pathBytes.includes(0)) throw new Error('embedded NUL in path');
            return decoder.decode(pathBytes);
        };
        const iovecTotal = (iovs, iovsLen) => {
            if (iovsLen < 0 || iovsLen > 1024) throw new Error('too many iovecs');
            const view = dataView();
            let total = 0;
            for (let i = 0; i < iovsLen; i++) {
                const base = iovs + i * 8;
                const ptr = view.getUint32(base, true);
                const len = view.getUint32(base + 4, true);
                new Uint8Array(memory().buffer, ptr, len);
                total += len;
                if (!Number.isSafeInteger(total) || (rt.maxHostCallBytes > 0 && total > rt.maxHostCallBytes)) {
                    throw new Error('host call is too large');
                }
            }
            return total;
        };
        const gatherIovecs = (iovs, iovsLen) => {
            const view = dataView();
            const total = iovecTotal(iovs, iovsLen);
            const merged = new Uint8Array(total);
            let offset = 0;
            for (let i = 0; i < iovsLen; i++) {
                const base = iovs + i * 8;
                const ptr = view.getUint32(base, true);
                const len = view.getUint32(base + 4, true);
                merged.set(new Uint8Array(memory().buffer, ptr, len), offset);
                offset += len;
            }
            return merged;
        };
        const sanitizeOutput = (bytes) => {
            const copy = bytes.slice();
            for (let i = 0; i < copy.length; i++) {
                const value = copy[i];
                if (value !== 10 && value !== 13 && value !== 9 && (value < 0x20 || value > 0x7e)) copy[i] = 63;
            }
            return copy;
        };

        const fd_write_impl = async (fd, iovs, iovsLen, nwritten) => {
            try {
                const bytes = gatherIovecs(iovs, iovsLen);
                if (fd === 1 || fd === 2) {
                    if (rt.maxOutputBytes > 0 && rt.outputBytes + bytes.length > rt.maxOutputBytes) {
                        return errnoNotcapable;
                    }
                    const safeBytes = sanitizeOutput(bytes);
                    dataView().setUint32(nwritten, safeBytes.length, true);
                    const text = decoder.decode(safeBytes);
                    if (text.length) {
                        if (fd === 2) console.error(text.replace(/\n$/, ''));
                        else console.log(text.replace(/\n$/, ''));
                    }
                    rt.outputBytes += safeBytes.length;
                    return 0;
                }
                const entry = rt.fds.get(fd);
                if (!entry || entry.kind !== 'file') return errnoBadf;
                if (rt.storageReadOnly || !entry.write) return errnoNotcapable;
                const oldFile = await entry.handle.getFile();
                const oldSize = oldFile.size;
                const projected = Math.max(oldSize, entry.offset + bytes.length);
                const growth = projected - oldSize;
                if ((rt.storageMaxFileBytes > 0 && projected > rt.storageMaxFileBytes) ||
                    !quotaCanAdd(growth, 0)) {
                    return errnoNospc;
                }
                const writable = await entry.handle.createWritable({ keepExistingData: true });
                await writable.seek(entry.offset);
                await writable.write(bytes);
                await writable.close();
                entry.offset += bytes.length;
                quotaAdd(growth, 0);
                dataView().setUint32(nwritten, bytes.length, true);
                return 0;
            } catch (_) { return errnoIo; }
        };

        const fd_read_impl = async (fd, iovs, iovsLen, nread) => {
            try {
                const requested = iovecTotal(iovs, iovsLen);
                const entry = rt.fds.get(fd);
                if (!entry || entry.kind !== 'file' || !entry.read) return errnoBadf;
                const file = await entry.handle.getFile();
                if (rt.storageMaxFileBytes > 0 && file.size > rt.storageMaxFileBytes) return errnoNospc;
                const end = Math.min(file.size, entry.offset + requested);
                const bytes = new Uint8Array(await file.slice(entry.offset, end).arrayBuffer());
                const view = dataView();
                let copied = 0;
                for (let i = 0; i < iovsLen && copied < bytes.length; i++) {
                    const base = iovs + i * 8;
                    const ptr = view.getUint32(base, true);
                    const len = view.getUint32(base + 4, true);
                    const count = Math.min(len, bytes.length - copied);
                    new Uint8Array(memory().buffer, ptr, count).set(bytes.subarray(copied, copied + count));
                    copied += count;
                }
                entry.offset += copied;
                view.setUint32(nread, copied, true);
                return 0;
            } catch (_) { return errnoIo; }
        };

        const fd_close_impl = async (fd) => {
            if (!rt.fds.has(fd)) return errnoBadf;
            rt.fds.delete(fd);
            return 0;
        };

        const fd_prestat_get = (fd, prestatPtr) => {
            if (!storageEnabled() || fd !== preopenFd) return errnoBadf;
            const view = dataView();
            view.setUint8(prestatPtr, 0);
            view.setUint32(prestatPtr + 4, encoder.encode(rt.guestPath).length, true);
            return 0;
        };
        const fd_prestat_dir_name = (fd, pathPtr, pathLen) => {
            if (!storageEnabled() || fd !== preopenFd) return errnoBadf;
            const bytes = encoder.encode(rt.guestPath);
            if (pathLen < bytes.length) return errnoInval;
            new Uint8Array(memory().buffer, pathPtr, bytes.length).set(bytes);
            return 0;
        };

        const path_filestat_get_impl = async (fd, _flags, pathPtr, pathLen, statPtr) => {
            try {
                if (!storageEnabled() || fd !== preopenFd) return errnoBadf;
                const parts = safeParts(readPath(pathPtr, pathLen));
                let handle = await storageRoot();
                let isDirectory = true;
                if (parts.length > 0) {
                    for (let i = 0; i < parts.length - 1; i++) {
                        handle = await handle.getDirectoryHandle(parts[i], { create: false });
                    }
                    const name = parts[parts.length - 1];
                    try {
                        handle = await handle.getFileHandle(name, { create: false });
                        isDirectory = false;
                    } catch (fileError) {
                        handle = await handle.getDirectoryHandle(name, { create: false });
                        isDirectory = true;
                    }
                }
                const view = dataView();
                for (let i = 0; i < 64; i++) view.setUint8(statPtr + i, 0);
                view.setUint8(statPtr + 16, isDirectory ? 3 : 4);
                view.setBigUint64(statPtr + 24, 1n, true);
                if (!isDirectory) {
                    const file = await handle.getFile();
                    view.setBigUint64(statPtr + 32, BigInt(file.size), true);
                    const ns = BigInt(Math.floor(file.lastModified)) * 1000000n;
                    view.setBigUint64(statPtr + 40, ns, true);
                    view.setBigUint64(statPtr + 48, ns, true);
                    view.setBigUint64(statPtr + 56, ns, true);
                }
                return 0;
            } catch (error) {
                if (error && error.name === 'NotFoundError') return errnoNoent;
                return errnoIo;
            }
        };

        const path_open_impl = async (
            fd, _dirflags, pathPtr, pathLen, oflags,
            rightsBase, _rightsInheriting, fdflags, openedFdPtr
        ) => {
            try {
                if (!storageEnabled() || fd !== preopenFd) return errnoBadf;
                const parts = safeParts(readPath(pathPtr, pathLen));
                if (parts.length === 0) return errnoInval;
                const create = (oflags & 1) !== 0;
                const directory = (oflags & 2) !== 0;
                const exclusive = (oflags & 4) !== 0;
                const truncate = (oflags & 8) !== 0;
                const wantsRead = (rightsBase & 2n) !== 0n;
                const wantsWrite = (rightsBase & 64n) !== 0n || create || truncate;
                if (rt.storageReadOnly && wantsWrite) return errnoNotcapable;

                let parent = await storageRoot();
                for (let i = 0; i < parts.length - 1; i++) {
                    parent = await parent.getDirectoryHandle(parts[i], { create: false });
                }
                const name = parts[parts.length - 1];
                let existed = false;
                let oldSize = 0;
                try {
                    if (directory) {
                        await parent.getDirectoryHandle(name, { create: false });
                    } else {
                        const existing = await parent.getFileHandle(name, { create: false });
                        oldSize = (await existing.getFile()).size;
                    }
                    existed = true;
                } catch (error) {
                    if (!error || error.name !== 'NotFoundError') throw error;
                }
                if (exclusive && create && existed) return errnoExist;
                if (!existed && create && !quotaCanAdd(0, 1)) return errnoNospc;

                let handle;
                if (directory) {
                    handle = await parent.getDirectoryHandle(name, { create });
                } else {
                    handle = await parent.getFileHandle(name, { create });
                    if (rt.storageMaxFileBytes > 0 && oldSize > rt.storageMaxFileBytes) return errnoNospc;
                    if (truncate) {
                        const writable = await handle.createWritable({ keepExistingData: false });
                        await writable.close();
                        if (oldSize > 0) quotaRemove(oldSize, 0);
                        oldSize = 0;
                    }
                }
                if (!existed) quotaAdd(0, 1);

                const newFd = rt.nextFd++;
                let offset = 0;
                if (!directory && (fdflags & 1) !== 0) {
                    offset = (await handle.getFile()).size;
                }
                rt.fds.set(newFd, {
                    kind: directory ? 'dir' : 'file',
                    handle,
                    offset,
                    read: wantsRead,
                    write: wantsWrite,
                });
                dataView().setUint32(openedFdPtr, newFd, true);
                return 0;
            } catch (error) {
                if (error && error.name === 'NotFoundError') return errnoNoent;
                return errnoIo;
            }
        };

        const random_get = (ptr, len) => {
            try {
                if (len < 0 || (rt.maxHostCallBytes > 0 && len > rt.maxHostCallBytes)) return errno2big;
                let offset = 0;
                while (offset < len) {
                    const chunk = Math.min(65536, len - offset);
                    crypto.getRandomValues(new Uint8Array(memory().buffer, ptr + offset, chunk));
                    offset += chunk;
                }
                return 0;
            } catch (_) { return errnoIo; }
        };

        const clock_time_get = (clockId, _precision, resultPtr) => {
            try {
                let ns;
                if (clockId === 1 && typeof performance !== 'undefined') {
                    ns = BigInt(Math.floor(performance.now() * 1000000));
                } else {
                    ns = BigInt(Date.now()) * 1000000n;
                }
                dataView().setBigUint64(resultPtr, ns, true);
                return 0;
            } catch (_) { return 29; }
        };

        const pollImpl = async (subscriptions, events, nsubscriptions, nevents) => {
            try {
                if (nsubscriptions <= 0) {
                    dataView().setUint32(nevents, 0, true);
                    return 0;
                }
                if (nsubscriptions > 64) return errno2big;
                const view = dataView();
                let best = null;
                for (let i = 0; i < nsubscriptions; i++) {
                    const base = subscriptions + i * 48;
                    const type = view.getUint8(base + 8);
                    if (type !== 0) continue;
                    const clockId = view.getUint32(base + 16, true);
                    const timeout = view.getBigUint64(base + 24, true);
                    const flags = view.getUint16(base + 40, true);
                    let delayNs = timeout;
                    if ((flags & 1) !== 0) {
                        let now;
                        if (clockId === 1 && typeof performance !== 'undefined') {
                            now = BigInt(Math.floor(performance.now() * 1000000));
                        } else {
                            now = BigInt(Date.now()) * 1000000n;
                        }
                        delayNs = timeout > now ? timeout - now : 0n;
                    }
                    if (best === null || delayNs < best.delayNs) {
                        best = { base, delayNs };
                    }
                }
                if (best === null) return errnoNosys;
                const delayMs = Number(best.delayNs) / 1000000;
                if (rt.maxWasiPollMillis > 0 && delayMs > rt.maxWasiPollMillis) return errnoNotcapable;
                if (delayMs > 0) await new Promise(resolve => setTimeout(resolve, delayMs));
                const userdata = view.getBigUint64(best.base, true);
                view.setBigUint64(events, userdata, true);
                view.setUint16(events + 8, 0, true);
                view.setUint8(events + 10, 0);
                for (let i = 11; i < 32; i++) view.setUint8(events + i, 0);
                view.setUint32(nevents, 1, true);
                return 0;
            } catch (_) { return 29; }
        };

        const request_create = (metadataLen, bodyLen) => {
            if (rt.reqMeta !== null || metadataLen <= 0 || metadataLen > 65536
                || bodyLen < 0 || (rt.maxHostCallBytes > 0 && bodyLen > rt.maxHostCallBytes)) return -1;
            rt.reqMeta = new Uint8Array(metadataLen);
            rt.reqBody = new Uint8Array(bodyLen);
            rt.resMeta = null;
            rt.resBody = null;
            return 1;
        };
        const request_metadata_byte = (handle, index, value) => {
            if (handle !== 1 || rt.reqMeta === null || index < 0 || index >= rt.reqMeta.length) return -1;
            rt.reqMeta[index] = value & 255;
            return 0;
        };
        const request_body_byte = (handle, index, value) => {
            if (handle !== 1 || rt.reqBody === null || index < 0 || index >= rt.reqBody.length) return -1;
            rt.reqBody[index] = value & 255;
            return 0;
        };
        const request_metadata_copy = (handle, ptr, len) => {
            if (handle !== 1 || rt.reqMeta === null || len !== rt.reqMeta.length || ptr < 0) return -1;
            if (len > 0) rt.reqMeta.set(new Uint8Array(memory().buffer, ptr, len));
            return 0;
        };
        const request_body_copy = (handle, ptr, len) => {
            if (handle !== 1 || rt.reqBody === null || len !== rt.reqBody.length || ptr < 0) return -1;
            if (len > 0) rt.reqBody.set(new Uint8Array(memory().buffer, ptr, len));
            return 0;
        };
        const requestExecuteImpl = async (handle) => {
            if (handle !== 1 || rt.reqMeta === null || rt.reqBody === null) return -1;
            try {
                const response = await executeHttp(rt.reqMeta, rt.reqBody);
                rt.resMeta = response.metadata;
                rt.resBody = response.body;
                return 0;
            } catch (_) {
                rt.resMeta = null;
                rt.resBody = null;
                return -1;
            }
        };
        const response_metadata_length = (handle) => handle === 1 && rt.resMeta !== null ? rt.resMeta.length : -1;
        const response_metadata_byte = (handle, index) => handle === 1 && rt.resMeta !== null && index >= 0 && index < rt.resMeta.length ? rt.resMeta[index] : -1;
        const response_body_length = (handle) => handle === 1 && rt.resBody !== null ? rt.resBody.length : -1;
        const response_body_byte = (handle, index) => handle === 1 && rt.resBody !== null && index >= 0 && index < rt.resBody.length ? rt.resBody[index] : -1;
        const response_metadata_copy = (handle, ptr, len) => {
            if (handle !== 1 || rt.resMeta === null || len !== rt.resMeta.length || ptr < 0) return -1;
            if (len > 0) new Uint8Array(memory().buffer, ptr, len).set(rt.resMeta);
            return 0;
        };
        const response_body_copy = (handle, ptr, len) => {
            if (handle !== 1 || rt.resBody === null || len !== rt.resBody.length || ptr < 0) return -1;
            if (len > 0) new Uint8Array(memory().buffer, ptr, len).set(rt.resBody);
            return 0;
        };
        const request_close = (handle) => {
            if (handle !== 1 || rt.reqMeta === null) return -1;
            rt.reqMeta = rt.reqBody = rt.resMeta = rt.resBody = null;
            return 0;
        };

        const hasJspi = typeof WebAssembly.Suspending === 'function' && typeof WebAssembly.promising === 'function';
        const imports = {
            wasi_snapshot_preview1: {
                fd_write: hasJspi ? new WebAssembly.Suspending(fd_write_impl) : (() => errnoNosys),
                fd_read: hasJspi ? new WebAssembly.Suspending(fd_read_impl) : (() => errnoNosys),
                fd_close: hasJspi ? new WebAssembly.Suspending(fd_close_impl) : (() => errnoNosys),
                fd_prestat_get,
                fd_prestat_dir_name,
                fd_readdir: () => errnoNosys,
                fd_sync: () => errnoNosys,
                path_open: hasJspi ? new WebAssembly.Suspending(path_open_impl) : (() => errnoNosys),
                path_filestat_get: hasJspi ? new WebAssembly.Suspending(path_filestat_get_impl) : (() => errnoNosys),
                path_create_directory: () => errnoNosys,
                path_readlink: () => errnoNosys,
                path_remove_directory: () => errnoNosys,
                path_rename: () => errnoNosys,
                path_symlink: () => errnoNosys,
                path_unlink_file: () => errnoNosys,
                random_get,
                clock_time_get,
                poll_oneoff: hasJspi ? new WebAssembly.Suspending(pollImpl) : (() => errnoNosys),
                proc_exit: (code) => { throw new Error('WASI proc_exit(' + code + ')'); },
            },
            ktor_wasi: {
                request_create,
                request_metadata_byte,
                request_body_byte,
                request_metadata_copy,
                request_body_copy,
                request_execute: hasJspi ? new WebAssembly.Suspending(requestExecuteImpl) : (() => -1),
                response_metadata_length,
                response_metadata_byte,
                response_body_length,
                response_body_byte,
                response_metadata_copy,
                response_body_copy,
                request_close,
            },
        };
        for (const linked of runtimeModules) {
            const linkedModule = new WebAssembly.Module(linked.bytes);
            const linkedInstance = new WebAssembly.Instance(linkedModule, imports);
            imports[linked.name] = linkedInstance.exports;
            if (linked.name === '<kotlin>') {
                rt.memory = linkedInstance.exports.memory;
                if (!(rt.memory instanceof WebAssembly.Memory)) {
                    throw new Error('<kotlin> runtime module does not export memory');
                }
            }
        }

        rt.instance = new WebAssembly.Instance(module, imports);
        if (rt.memory === null) rt.memory = rt.instance.exports.memory;
        if (!(rt.memory instanceof WebAssembly.Memory)) {
            throw new Error('Wasm module does not provide shared memory');
        }
        return rt;
    }"""

@JsFun(WASM_JS_HOST_SOURCE)
private external fun jsInstantiateModule(
    module: JsAny,
    runtimeModules: JsAny,
    storagePath: String,
    guestPath: String,
    storageReadOnly: Boolean,
    maxHostCallBytes: Int,
    maxOutputBytes: Double,
    maxWasiPollMillis: Double,
    storageMaxBytes: Double,
    storageMaxEntries: Int,
    storageMaxFileBytes: Double,
    executeHttp: (JsAny, JsAny) -> Promise<JsAny>,
): JsAny

@JsFun("(runtime, name) => { const value = runtime.instance.exports[name]; return typeof value === 'function' ? value : null; }")
private external fun jsGetExportedFunction(runtime: JsAny, name: String): JsAny?

@JsFun("(fn, first, second) => fn(first, second) | 0")
private external fun jsCallI32(fn: JsAny, first: Int, second: Int): Int

@JsFun("""(fn, first, second) => {
    try {
        if (typeof WebAssembly.promising === 'function') {
            return WebAssembly.promising(fn)(first, second);
        }
        return Promise.resolve(fn(first, second));
    } catch (error) {
        return Promise.reject(error);
    }
}""")
private external fun jsCallI32Async(fn: JsAny, first: Int, second: Int): Promise<JsAny>


@JsFun("() => typeof Worker === 'function' && typeof Blob === 'function' && typeof URL === 'function'")
private external fun jsWorkerAvailable(): Boolean

@JsFun("() => typeof window !== 'undefined' && typeof document !== 'undefined'")
private external fun jsIsBrowser(): Boolean

@JsFun(
    """(instantiateSource, wasm, runtimeModules, storagePath, guestPath, storageReadOnly,
        maxMemoryBytes, maxTableElements, maxModuleBytes, maxHostCallBytes, maxOutputBytes,
        maxWasiPollMillis, storageMaxBytes, storageMaxEntries, storageMaxFileBytes,
        maxExecutionMillis, methodId, arguments, maxArgumentBytes, maxResultBytes, executeHttp) => {
        let worker = null;
        let timer = null;
        let objectUrl = null;
        let settled = false;
        let rejectOuter = null;

        const workerBody = String.raw`
const instantiate = (` + instantiateSource + String.raw`);
const PENDING = 0;
const SUCCESS = 1;
const FAILURE = 2;
const CANCELLED = 3;
const pendingHttp = new Map();
let nextHttpId = 1;

const readU32 = (bytes, start) => {
    let value = 0;
    let shift = 0;
    let offset = start;
    for (let i = 0; i < 5; i++) {
        if (offset >= bytes.length) throw new Error('truncated Wasm LEB');
        const byte = bytes[offset++];
        value |= (byte & 0x7f) << shift;
        if ((byte & 0x80) === 0) return { value: value >>> 0, offset };
        shift += 7;
    }
    throw new Error('invalid Wasm LEB');
};
const encodeU32 = (value) => {
    const out = [];
    let current = value >>> 0;
    do {
        let byte = current & 0x7f;
        current >>>= 7;
        if (current !== 0) byte |= 0x80;
        out.push(byte);
    } while (current !== 0);
    return new Uint8Array(out);
};
const concatBytes = (parts) => {
    let total = 0;
    for (const part of parts) total += part.length;
    const out = new Uint8Array(total);
    let offset = 0;
    for (const part of parts) { out.set(part, offset); offset += part.length; }
    return out;
};
const transformModule = (input, maxMemoryBytes, maxTableElements, maxModuleBytes) => {
    const bytes = input instanceof Uint8Array ? input : new Uint8Array(input);
    if (maxModuleBytes > 0 && bytes.length > maxModuleBytes) {
        throw new Error('Wasm module exceeds configured size limit');
    }
    if (bytes.length < 8 || bytes[0] !== 0 || bytes[1] !== 0x61 || bytes[2] !== 0x73 || bytes[3] !== 0x6d) {
        throw new Error('invalid Wasm module');
    }
    const parts = [bytes.slice(0, 8)];
    let offset = 8;
    const maxPages = maxMemoryBytes > 0 ? Math.floor(maxMemoryBytes / 65536) : 0;
    while (offset < bytes.length) {
        const sectionStart = offset;
        const id = bytes[offset++];
        const sizeInfo = readU32(bytes, offset);
        const size = sizeInfo.value;
        offset = sizeInfo.offset;
        const payloadStart = offset;
        const payloadEnd = payloadStart + size;
        if (payloadEnd > bytes.length) throw new Error('truncated Wasm section');

        if (id === 4 && maxTableElements > 0) {
            let cursor = payloadStart;
            const countInfo = readU32(bytes, cursor);
            cursor = countInfo.offset;
            const payloadParts = [encodeU32(countInfo.value)];
            for (let index = 0; index < countInfo.value; index++) {
                if (cursor >= payloadEnd) throw new Error('truncated Wasm table section');
                const refType = bytes[cursor++];
                // Kotlin/Wasm and the core table proposal use funcref/externref here.
                // Typed references have a multi-byte encoding and are rejected rather
                // than being rewritten incorrectly.
                if (refType !== 0x70 && refType !== 0x6f) {
                    throw new Error('unsupported WebAssembly table reference type');
                }
                const flagsInfo = readU32(bytes, cursor);
                const flags = flagsInfo.value;
                cursor = flagsInfo.offset;
                if ((flags & ~1) !== 0) {
                    throw new Error('browser sandbox supports only standard 32-bit unshared Wasm tables');
                }
                const minInfo = readU32(bytes, cursor);
                const minimum = minInfo.value;
                cursor = minInfo.offset;
                let existingMaximum = null;
                if ((flags & 1) !== 0) {
                    const maxInfo = readU32(bytes, cursor);
                    existingMaximum = maxInfo.value;
                    cursor = maxInfo.offset;
                }
                if (minimum > maxTableElements) {
                    throw new Error('Wasm table minimum exceeds configured table limit');
                }
                const maximum = existingMaximum === null
                    ? maxTableElements
                    : Math.min(existingMaximum, maxTableElements);
                payloadParts.push(
                    new Uint8Array([refType]),
                    encodeU32(flags | 1),
                    encodeU32(minimum),
                    encodeU32(maximum),
                );
            }
            if (cursor !== payloadEnd) throw new Error('unsupported Wasm table section encoding');
            const payload = concatBytes(payloadParts);
            parts.push(new Uint8Array([id]), encodeU32(payload.length), payload);
        } else if (id === 5 && maxMemoryBytes > 0) {
            if (maxPages <= 0) throw new Error('configured memory limit is below one Wasm page');
            let cursor = payloadStart;
            const countInfo = readU32(bytes, cursor);
            cursor = countInfo.offset;
            const payloadParts = [encodeU32(countInfo.value)];
            for (let index = 0; index < countInfo.value; index++) {
                const flagsInfo = readU32(bytes, cursor);
                const flags = flagsInfo.value;
                cursor = flagsInfo.offset;
                if ((flags & ~1) !== 0) {
                    throw new Error('browser sandbox supports only standard 32-bit unshared Wasm memory');
                }
                const minInfo = readU32(bytes, cursor);
                const minimum = minInfo.value;
                cursor = minInfo.offset;
                let existingMaximum = null;
                if ((flags & 1) !== 0) {
                    const maxInfo = readU32(bytes, cursor);
                    existingMaximum = maxInfo.value;
                    cursor = maxInfo.offset;
                }
                if (minimum > maxPages) {
                    throw new Error('Wasm memory minimum exceeds configured memory limit');
                }
                const maximum = existingMaximum === null ? maxPages : Math.min(existingMaximum, maxPages);
                payloadParts.push(encodeU32(flags | 1), encodeU32(minimum), encodeU32(maximum));
            }
            if (cursor !== payloadEnd) throw new Error('unsupported Wasm memory section encoding');
            const payload = concatBytes(payloadParts);
            parts.push(new Uint8Array([id]), encodeU32(payload.length), payload);
        } else {
            parts.push(bytes.slice(sectionStart, payloadEnd));
        }
        offset = payloadEnd;
    }
    return concatBytes(parts);
};
const checkMemory = (runtime, maxBytes) => {
    if (maxBytes > 0 && runtime.memory.buffer.byteLength > maxBytes) {
        throw new Error('Wasm memory limit exceeded');
    }
};
const invokeAsync = async (fn, first, second) => {
    if (typeof fn !== 'function') throw new Error('required extension export is missing');
    if (typeof WebAssembly.promising === 'function') {
        return (await WebAssembly.promising(fn)(first, second)) | 0;
    }
    return fn(first, second) | 0;
};
const messageBytes = (exports, lengthName, byteName, maxBytes) => {
    const lengthFn = exports[lengthName];
    const byteFn = exports[byteName];
    if (typeof lengthFn !== 'function' || typeof byteFn !== 'function') throw new Error('required extension export is missing');
    const length = lengthFn(0, 0) | 0;
    if (length <= 0) return new Uint8Array(0);
    if (length > maxBytes) throw new Error('extension result/error exceeds configured size limit');
    const bytes = new Uint8Array(length);
    for (let i = 0; i < length; i++) bytes[i] = byteFn(i, 0) & 255;
    return bytes;
};
const decodeFailure = (bytes) => {
    if (bytes.length < 4 || bytes[0] !== 87 || bytes[1] !== 69 || bytes[2] !== 88 || bytes[3] !== 1) {
        return { type: 'Throwable', message: bytes.length === 0 ? 'unknown extension error' : new TextDecoder().decode(bytes), stack: '' };
    }
    let offset = 4;
    const readField = () => {
        if (offset + 4 > bytes.length) return '';
        const length = (bytes[offset]) |
            (bytes[offset + 1] << 8) |
            (bytes[offset + 2] << 16) |
            (bytes[offset + 3] << 24);
        offset += 4;
        if (length < 0 || offset + length > bytes.length) return '';
        const value = new TextDecoder().decode(bytes.slice(offset, offset + length));
        offset += length;
        return value;
    };
    return {
        type: readField() || 'Throwable',
        message: readField() || 'extension call failed',
        stack: readField(),
    };
};
const executeHttp = (metadata, body) => new Promise((resolve, reject) => {
    const id = nextHttpId++;
    pendingHttp.set(id, { resolve, reject });
    self.postMessage({ type: 'http', id, metadata, body });
});

self.onmessage = async (event) => {
    const msg = event.data;
    if (msg && msg.type === 'http-response') {
        const pending = pendingHttp.get(msg.id);
        if (pending) { pendingHttp.delete(msg.id); pending.resolve({ metadata: msg.metadata, body: msg.body }); }
        return;
    }
    if (msg && msg.type === 'http-error') {
        const pending = pendingHttp.get(msg.id);
        if (pending) { pendingHttp.delete(msg.id); pending.reject(new Error(msg.message || 'HTTP capability failed')); }
        return;
    }
    if (!msg || msg.type !== 'run') return;

    const executeRun = async () => {
        const rootBytes = transformModule(msg.wasm, msg.maxMemoryBytes, msg.maxTableElements, msg.maxModuleBytes);
        const linked = msg.runtimeModules.map((entry) => ({
            name: entry.name,
            bytes: transformModule(entry.bytes, msg.maxMemoryBytes, msg.maxTableElements, msg.maxModuleBytes),
        }));
        const module = new WebAssembly.Module(rootBytes);
        const runtime = instantiate(
            module,
            linked,
            msg.storagePath,
            msg.guestPath,
            msg.storageReadOnly,
            msg.maxHostCallBytes,
            msg.maxOutputBytes,
            msg.maxWasiPollMillis,
            msg.storageMaxBytes,
            msg.storageMaxEntries,
            msg.storageMaxFileBytes,
            executeHttp,
        );
        checkMemory(runtime, msg.maxMemoryBytes);
        const ex = runtime.instance.exports;
        const prepareArguments = ex['__wasmtime_extension_argument_prepare'];
        const setArgumentByte = ex['__wasmtime_extension_argument_byte'];
        const start = ex['__wasmtime_extension_start'];
        const poll = ex['__wasmtime_extension_poll'];
        const nextWake = ex['__wasmtime_extension_next_wake_millis'];
        const cancel = ex['__wasmtime_extension_cancel'];
        if (msg.maxArgumentBytes > 0 && msg.arguments.length > msg.maxArgumentBytes) {
            throw new Error('extension argument payload exceeds configured size limit');
        }
        if (typeof prepareArguments !== 'function' || typeof setArgumentByte !== 'function') {
            throw new Error('required extension argument exports are missing');
        }
        if ((prepareArguments(msg.arguments.length, 0) | 0) !== 0) throw new Error('extension rejected argument payload');
        for (let i = 0; i < msg.arguments.length; i++) {
            if ((setArgumentByte(i, msg.arguments[i]) | 0) !== 0) throw new Error('extension rejected argument byte ' + i);
        }
        let state = await invokeAsync(start, msg.methodId, 0);
        let lastPoll = performance.now();
        try {
            while (state === PENDING) {
                const wake = typeof nextWake === 'function' ? (nextWake(0, 0) | 0) : -1;
                if (wake > 0) await new Promise((resolve) => setTimeout(resolve, wake));
                else if (wake === 0) await Promise.resolve();
                else await new Promise((resolve) => setTimeout(resolve, 1));
                const now = performance.now();
                const elapsed = Math.max(0, Math.min(2147483647, Math.floor(now - lastPoll)));
                state = await invokeAsync(poll, elapsed, 0);
                lastPoll = now;
                checkMemory(runtime, msg.maxMemoryBytes);
            }
        } finally {
            if (state === PENDING && typeof cancel === 'function') cancel(0, 0);
        }

        if (state === SUCCESS) {
            const bytes = messageBytes(ex, '__wasmtime_extension_result_length', '__wasmtime_extension_result_byte', msg.maxResultBytes);
            self.postMessage({ type: 'result', bytes }, [bytes.buffer]);
        } else if (state === FAILURE) {
            const bytes = messageBytes(ex, '__wasmtime_extension_error_length', '__wasmtime_extension_error_byte', msg.maxResultBytes);
            self.postMessage({ type: 'failure', failure: decodeFailure(bytes) });
        } else if (state === CANCELLED) {
            const bytes = messageBytes(ex, '__wasmtime_extension_error_length', '__wasmtime_extension_error_byte', msg.maxResultBytes);
            self.postMessage({ type: 'cancelled', failure: decodeFailure(bytes) });
        } else {
            throw new Error('invalid extension call state: ' + state);
        }
    };

    try {
        if (msg.storagePath && !msg.storageReadOnly) {
            if (!self.navigator || !self.navigator.locks || typeof self.navigator.locks.request !== 'function') {
                throw new Error('Web Locks are required for writable sandbox storage');
            }
            let acquired = false;
            const lockName = 'wasmtime-kmp-storage:' + msg.storagePath;
            await self.navigator.locks.request(lockName, { mode: 'exclusive', ifAvailable: true }, async (lock) => {
                if (lock === null) return;
                acquired = true;
                await executeRun();
            });
            if (!acquired) throw new Error('sandbox storage is already in use');
        } else {
            await executeRun();
        }
    } catch (error) {
        self.postMessage({ type: 'failure', message: error && error.message ? String(error.message) : String(error) });
    }
};
`;

        const promise = new Promise((resolve, reject) => {
            rejectOuter = reject;
            try {
                const blob = new Blob([workerBody], { type: 'text/javascript' });
                objectUrl = URL.createObjectURL(blob);
                worker = new Worker(objectUrl);
            } catch (error) {
                settled = true;
                reject(error);
                return;
            }

            const cleanup = () => {
                if (timer !== null) { clearTimeout(timer); timer = null; }
                if (worker !== null) { worker.terminate(); worker = null; }
                if (objectUrl !== null) { URL.revokeObjectURL(objectUrl); objectUrl = null; }
            };
            const finish = (fn, value) => {
                if (settled) return;
                settled = true;
                cleanup();
                fn(value);
            };

            worker.onmessage = (event) => {
                const msg = event.data;
                if (!msg) return;
                if (msg.type === 'http') {
                    Promise.resolve(executeHttp(msg.metadata, msg.body)).then(
                        (response) => {
                            if (settled || worker === null) return;
                            worker.postMessage({ type: 'http-response', id: msg.id, metadata: response.metadata, body: response.body });
                        },
                        (error) => {
                            if (settled || worker === null) return;
                            worker.postMessage({ type: 'http-error', id: msg.id, message: error && error.message ? String(error.message) : String(error) });
                        },
                    );
                    return;
                }
                if (msg.type === 'result') {
                    finish(resolve, msg.bytes);
                } else if (msg.type === 'cancelled') {
                    const failure = msg.failure || {};
                    const error = new Error(failure.message || 'extension call cancelled');
                    error.name = 'WasmtimeExtensionCancelled';
                    error.remoteType = failure.type || 'CancellationException';
                    error.guestStack = failure.stack || '';
                    finish(reject, error);
                } else if (msg.type === 'failure') {
                    const failure = msg.failure || {};
                    const error = new Error(failure.message || 'extension call failed');
                    error.name = 'WasmtimeExtensionRemote';
                    error.remoteType = failure.type || 'Throwable';
                    error.guestStack = failure.stack || '';
                    finish(reject, error);
                }
            };
            worker.onerror = (event) => finish(reject, new Error(event.message || 'extension worker failed'));

            if (maxExecutionMillis > 0) {
                timer = setTimeout(() => {
                    const error = new Error('extension execution timed out after ' + maxExecutionMillis + ' ms');
                    error.name = 'WasmtimeExtensionTimeout';
                    finish(reject, error);
                }, maxExecutionMillis);
            }

            worker.postMessage({
                type: 'run', wasm, runtimeModules, storagePath, guestPath, storageReadOnly,
                maxMemoryBytes, maxTableElements, maxModuleBytes, maxHostCallBytes, maxOutputBytes,
                maxWasiPollMillis, storageMaxBytes, storageMaxEntries, storageMaxFileBytes,
                methodId, arguments, maxArgumentBytes, maxResultBytes,
            });
        });

        return {
            promise,
            cancel: () => {
                if (settled) return;
                settled = true;
                if (timer !== null) clearTimeout(timer);
                if (worker !== null) worker.terminate();
                if (objectUrl !== null) URL.revokeObjectURL(objectUrl);
                worker = null;
                objectUrl = null;
                if (rejectOuter !== null) {
                    const error = new Error('extension execution cancelled');
                    error.name = 'WasmtimeExtensionCancelled';
                    rejectOuter(error);
                }
            },
        };
    }"""
)
private external fun jsRunExtensionWorker(
    instantiateSource: String,
    wasm: JsAny,
    runtimeModules: JsAny,
    storagePath: String,
    guestPath: String,
    storageReadOnly: Boolean,
    maxMemoryBytes: Double,
    maxTableElements: Double,
    maxModuleBytes: Int,
    maxHostCallBytes: Int,
    maxOutputBytes: Double,
    maxWasiPollMillis: Double,
    storageMaxBytes: Double,
    storageMaxEntries: Int,
    storageMaxFileBytes: Double,
    maxExecutionMillis: Double,
    methodId: Int,
    arguments: JsAny,
    maxArgumentBytes: Int,
    maxResultBytes: Int,
    executeHttp: (JsAny, JsAny) -> Promise<JsAny>,
): JsAny

@JsFun("(task) => task.promise")
private external fun jsExtensionWorkerTaskPromise(task: JsAny): Promise<JsAny>

@JsFun("(task) => task.cancel()")
private external fun jsCancelExtensionWorkerTask(task: JsAny)

@JsFun("(error) => error && error.name ? String(error.name) : ''")
private external fun jsErrorName(error: JsAny): String

@JsFun("(value) => value | 0")
private external fun jsToInt(value: JsAny): Int

@JsFun("(metadata, body) => ({ metadata, body })")
private external fun jsHttpResponse(metadata: JsAny, body: JsAny): JsAny

@JsFun("(message) => new Error(message)")
private external fun jsError(message: String): JsAny

@JsFun("(error) => error && error.message ? String(error.message) : String(error)")
private external fun jsErrorMessage(error: JsAny): String

@JsFun("(error) => error && error.remoteType ? String(error.remoteType) : ''")
private external fun jsErrorRemoteType(error: JsAny): String

@JsFun("(error) => error && error.guestStack ? String(error.guestStack) : ''")
private external fun jsErrorGuestStack(error: JsAny): String

@JsFun("(runtime) => runtime.memory.buffer.byteLength")
private external fun jsMemorySize(runtime: JsAny): Int

private fun WasmtimeRuntime?.toJsRuntimeModules(): JsAny {
    val array = jsNewArray()
    this?.modules?.forEach { module ->
        jsPushRuntimeModule(array, module.name, module.wasm.toJsUint8Array())
    }
    return array
}

@JsFun("() => []")
private external fun jsNewArray(): JsAny

@JsFun("(array, name, bytes) => { array.push({ name, bytes }); }")
private external fun jsPushRuntimeModule(array: JsAny, name: String, bytes: JsAny)
