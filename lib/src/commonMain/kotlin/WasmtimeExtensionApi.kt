package dev.brahmkshatriya.wasmtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public const val WASMTIME_EXTENSION_API_VERSION: Int = 1

/** Capabilities declared by an extension bundle. They describe requirements; the host still chooses what to grant. */
public enum class WasmtimeExtensionCapability {
    Http,
    PersistentStorage,
    CacheStorage,
    TemporaryStorage,
    Credentials,
    Logging,
    Streaming,
}

/** Build-time metadata packaged next to an extension Wasm artifact. */
public data class WasmtimeExtensionManifest(
    public val id: String,
    public val name: String,
    public val version: String,
    public val apiVersion: Int,
    public val contract: String,
    public val capabilities: Set<WasmtimeExtensionCapability> = emptySet(),
) {
    init {
        require(id.isNotBlank()) { "extension id must not be blank" }
        require(name.isNotBlank()) { "extension name must not be blank" }
        require(version.isNotBlank()) { "extension version must not be blank" }
        require(apiVersion > 0) { "extension apiVersion must be positive" }
        require(contract.isNotBlank()) { "extension contract must not be blank" }
    }
}

/** Parses the deterministic `extension.properties` emitted by the Gradle extension plugin. */
public fun parseWasmtimeExtensionManifest(text: String): WasmtimeExtensionManifest {
    val values = text.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .associate { line ->
            val separator = line.indexOf('=')
            require(separator > 0) { "invalid extension manifest line: $line" }
            line.substring(0, separator).trim() to line.substring(separator + 1).trim()
        }
    val capabilities = values["capabilities"].orEmpty()
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { raw ->
            WasmtimeExtensionCapability.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: error("unknown extension capability: $raw")
        }
        .toSet()
    return WasmtimeExtensionManifest(
        id = values.getValue("id"),
        name = values.getValue("name"),
        version = values.getValue("version"),
        apiVersion = values.getValue("apiVersion").toInt(),
        contract = values.getValue("contract"),
        capabilities = capabilities,
    )
}

/** Host-side log sink for extension loading, calls, failures, and shutdown. */
public fun interface WasmtimeExtensionLogger {
    public fun log(event: WasmtimeExtensionLogEvent)
}

public data class WasmtimeExtensionLogEvent(
    public val extensionId: String?,
    public val level: Level,
    public val message: String,
    public val throwable: Throwable? = null,
) {
    public enum class Level { Debug, Info, Warning, Error }
}

/** Resolves host-owned credentials without exposing their secret values to guest memory. */
public fun interface WasmtimeCredentialProvider {
    /** Returns headers that the host should inject for [name] and [request]. */
    public suspend fun headers(name: String, request: WasmtimeHttpRequest): List<WasmtimeHttpHeader>
}

/** Opens a named host resource as a pull-based byte stream. */
public fun interface WasmtimeResourceProvider {
    public suspend fun open(name: String): WasmtimeByteStream?
}

/**
 * HTTP capability wrapper with optional host-owned credential injection.
 *
 * Guest requests select a credential by sending [CREDENTIAL_HEADER]. The marker is stripped before the
 * delegate sees the request; only the host resolves the secret headers.
 */
public class WasmtimeHttpService(
    private val delegate: WasmtimeHttpHandler,
    private val credentials: WasmtimeCredentialProvider? = null,
) : WasmtimeHttpHandler {
    override suspend fun execute(request: WasmtimeHttpRequest): WasmtimeHttpResponse {
        val credentialNames = request.headers
            .filter { it.name.equals(CREDENTIAL_HEADER, ignoreCase = true) }
            .map(WasmtimeHttpHeader::value)
        val clean = request.copy(
            headers = request.headers.filterNot { it.name.equals(CREDENTIAL_HEADER, ignoreCase = true) },
        )
        val injected = if (credentialNames.isEmpty()) emptyList() else {
            val provider = requireNotNull(credentials) { "extension requested a credential but no credential provider is configured" }
            credentialNames.flatMap { provider.headers(it, clean) }
        }
        return delegate.execute(clean.copy(headers = clean.headers + injected))
    }

    public companion object {
        public const val CREDENTIAL_HEADER: String = "X-Wasmtime-Credential"
    }
}

/** Host capabilities granted to one loaded extension. */
public data class WasmtimeHostServices(
    public val http: WasmtimeHttpHandler? = null,
    public val storage: WasmtimeExtensionStorage? = null,
    public val credentials: WasmtimeCredentialProvider? = null,
    public val resources: WasmtimeResourceProvider? = null,
    public val logger: WasmtimeExtensionLogger? = null,
) {
    internal fun effectiveHttp(extensionId: String? = null): WasmtimeHttpHandler? {
        val network = http?.let { WasmtimeHttpService(it, credentials) }
        if (network == null && resources == null && logger == null) return null
        return WasmtimeHostServiceRouter(network, resources, logger, extensionId)
    }

    internal fun effectiveStorage(): WasmtimeStorage? = storage?.toMount()
}

private class WasmtimeHostServiceRouter(
    private val network: WasmtimeHttpHandler?,
    private val resources: WasmtimeResourceProvider?,
    private val logger: WasmtimeExtensionLogger?,
    private val extensionId: String?,
) : WasmtimeHttpHandler {
    private val streamLock = Mutex()
    private val streams = mutableMapOf<Int, WasmtimeByteStream>()
    private var nextHandle: Int = 1

    override suspend fun execute(request: WasmtimeHttpRequest): WasmtimeHttpResponse {
        if (request.url == LOG_SERVICE_URL) return log(request)
        if (request.url != RESOURCE_SERVICE_URL) {
            return requireNotNull(network) { "HTTP capability is not available" }.execute(request)
        }
        return when (request.method) {
            RESOURCE_OPEN -> openResource(request)
            RESOURCE_READ -> readResource(request)
            RESOURCE_CLOSE -> closeResource(request)
            else -> WasmtimeHttpResponse(405)
        }
    }

    private fun log(request: WasmtimeHttpRequest): WasmtimeHttpResponse {
        val sink = logger ?: return WasmtimeHttpResponse(403)
        if (request.method != LOG_WRITE) return WasmtimeHttpResponse(405)
        val rawLevel = request.header(LOG_LEVEL_HEADER) ?: return WasmtimeHttpResponse(400)
        val level = WasmtimeExtensionLogEvent.Level.entries.firstOrNull {
            it.name.equals(rawLevel, ignoreCase = true)
        } ?: return WasmtimeHttpResponse(400)
        sink.log(
            WasmtimeExtensionLogEvent(
                extensionId = extensionId,
                level = level,
                message = request.body.decodeToString(),
            )
        )
        return WasmtimeHttpResponse(204)
    }

    private suspend fun openResource(request: WasmtimeHttpRequest): WasmtimeHttpResponse {
        val provider = resources ?: return WasmtimeHttpResponse(403)
        val name = request.header(RESOURCE_NAME_HEADER) ?: return WasmtimeHttpResponse(400)
        val stream = provider.open(name) ?: return WasmtimeHttpResponse(404)
        val handle = streamLock.withLock {
            val value = nextHandle++
            streams[value] = stream
            value
        }
        return WasmtimeHttpResponse(200, body = handle.toString().encodeToByteArray())
    }

    private suspend fun readResource(request: WasmtimeHttpRequest): WasmtimeHttpResponse {
        val handle = request.header(RESOURCE_HANDLE_HEADER)?.toIntOrNull() ?: return WasmtimeHttpResponse(400)
        val maxBytes = request.header(RESOURCE_MAX_BYTES_HEADER)?.toIntOrNull() ?: return WasmtimeHttpResponse(400)
        if (maxBytes <= 0) return WasmtimeHttpResponse(400)
        return streamLock.withLock {
            val stream = streams[handle] ?: return@withLock WasmtimeHttpResponse(404)
            val chunk = stream.read(maxBytes)
            if (chunk == null) WasmtimeHttpResponse(204) else WasmtimeHttpResponse(206, body = chunk)
        }
    }

    private suspend fun closeResource(request: WasmtimeHttpRequest): WasmtimeHttpResponse {
        val handle = request.header(RESOURCE_HANDLE_HEADER)?.toIntOrNull() ?: return WasmtimeHttpResponse(400)
        val stream = streamLock.withLock { streams.remove(handle) } ?: return WasmtimeHttpResponse(404)
        stream.close()
        return WasmtimeHttpResponse(204)
    }

    private fun WasmtimeHttpRequest.header(name: String): String? =
        headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value
}

internal const val RESOURCE_SERVICE_URL: String = "wasmtime://resource"
internal const val RESOURCE_OPEN: String = "RESOURCE_OPEN"
internal const val RESOURCE_READ: String = "RESOURCE_READ"
internal const val RESOURCE_CLOSE: String = "RESOURCE_CLOSE"
internal const val RESOURCE_NAME_HEADER: String = "X-Wasmtime-Resource-Name"
internal const val RESOURCE_HANDLE_HEADER: String = "X-Wasmtime-Resource-Handle"
internal const val RESOURCE_MAX_BYTES_HEADER: String = "X-Wasmtime-Resource-Max-Bytes"
internal const val LOG_SERVICE_URL: String = "wasmtime://log"
internal const val LOG_WRITE: String = "LOG"
internal const val LOG_LEVEL_HEADER: String = "X-Wasmtime-Log-Level"

/** Host backing directory mounted at the standard `/extension` guest namespace. */
public data class WasmtimeExtensionStorage(
    public val backingPath: String,
    public val readOnly: Boolean = false,
    public val maxBytes: Long = 64L * 1024L * 1024L,
    public val maxEntries: Int = 4_096,
    public val maxFileBytes: Long = 16L * 1024L * 1024L,
) {
    init {
        require(backingPath.isNotBlank()) { "backingPath must not be blank" }
    }

    internal fun toMount(): WasmtimeStorage = WasmtimeStorage(
        backingPath = backingPath,
        guestPath = "/extension",
        readOnly = readOnly,
        maxBytes = maxBytes,
        maxEntries = maxEntries,
        maxFileBytes = maxFileBytes,
    )
}

/** Resource descriptor generated by the host Gradle plugin for one packaged extension. */
public data class WasmtimeBundledExtension(
    public val id: String,
    public val name: String,
    public val version: String,
    public val apiVersion: Int,
    public val contract: String,
    public val wasmResourcePath: String,
    public val manifestResourcePath: String,
)

/** Loads the shared runtime from a generated resource manifest. */
public suspend fun loadWasmtimeBundledRuntime(
    manifestResourcePath: String,
    readResource: suspend (path: String) -> ByteArray,
): WasmtimeRuntime {
    val prefix = manifestResourcePath.substringBeforeLast('/', missingDelimiterValue = "")
    val manifest = readResource(manifestResourcePath).decodeToString()
    return loadWasmtimeRuntime(manifest) { fileName ->
        readResource(if (prefix.isEmpty()) fileName else "$prefix/$fileName")
    }
}

/** Loads one generated extension descriptor and validates its packaged manifest before instantiation. */
public suspend fun <T> WasmtimeBundledExtension.load(
    readResource: suspend (path: String) -> ByteArray,
    runtime: WasmtimeRuntime? = null,
    services: WasmtimeHostServices = WasmtimeHostServices(),
    limits: WasmtimeLimits = WasmtimeLimits(),
    expectedContract: String = contract,
    proxyFactory: (WasmtimeExtensionTransport) -> T,
): WasmtimeLoadedExtension<T> {
    val manifest = parseWasmtimeExtensionManifest(readResource(manifestResourcePath).decodeToString())
    require(manifest.id == id) { "extension manifest id mismatch: ${manifest.id} != $id" }
    require(manifest.name == name) { "extension manifest name mismatch: ${manifest.name} != $name" }
    require(manifest.version == version) { "extension manifest version mismatch: ${manifest.version} != $version" }
    require(manifest.apiVersion == apiVersion) {
        "extension manifest API version mismatch: ${manifest.apiVersion} != $apiVersion"
    }
    require(manifest.apiVersion == WASMTIME_EXTENSION_API_VERSION) {
        "unsupported Wasmtime extension API version ${manifest.apiVersion}; host supports $WASMTIME_EXTENSION_API_VERSION"
    }
    require(manifest.contract == expectedContract) {
        "extension contract mismatch: ${manifest.contract} != $expectedContract"
    }
    return loadWasmtimeExtension(
        wasm = readResource(wasmResourcePath),
        runtime = runtime,
        manifest = manifest,
        services = services,
        limits = limits,
        proxyFactory = proxyFactory,
    )
}

/** Names used by the recommended single extension storage mount. */
public data class WasmtimeStorageNamespace(
    public val guestRoot: String = "/extension",
) {
    public val persistent: String get() = "$guestRoot/persistent"
    public val cache: String get() = "$guestRoot/cache"
    public val temporary: String get() = "$guestRoot/temporary"

    init {
        require(guestRoot.startsWith('/') && guestRoot != "/") { "guestRoot must be an absolute non-root path" }
    }
}

/** Loaded typed extension plus the transport that owns its Wasm instance. */
private const val EXTENSION_ON_LOAD_METHOD: Int = Int.MIN_VALUE
private const val EXTENSION_ON_UNLOAD_METHOD: Int = Int.MIN_VALUE + 1

public class WasmtimeLoadedExtension<T> internal constructor(
    public val api: T,
    public val manifest: WasmtimeExtensionManifest?,
    private val transport: WasmtimeExtensionTransport,
    private val logger: WasmtimeExtensionLogger?,
) : AutoCloseable {
    private var closed: Boolean = false

    /** Runs the guest `onUnload()` lifecycle hook, then releases the transport. */
    public suspend fun shutdown() {
        if (closed) return
        try {
            transport.invoke(EXTENSION_ON_UNLOAD_METHOD)
        } catch (failure: Throwable) {
            logger?.log(
                WasmtimeExtensionLogEvent(
                    manifest?.id,
                    WasmtimeExtensionLogEvent.Level.Warning,
                    "Extension onUnload failed",
                    failure,
                )
            )
        } finally {
            close()
        }
    }

    /** Immediately releases the Wasm transport without suspending. */
    override fun close() {
        if (closed) return
        closed = true
        transport.close()
        logger?.log(
            WasmtimeExtensionLogEvent(
                manifest?.id,
                WasmtimeExtensionLogEvent.Level.Info,
                "Extension closed",
            )
        )
    }
}

private fun validateWasmtimeExtensionCapabilities(
    manifest: WasmtimeExtensionManifest,
    services: WasmtimeHostServices,
) {
    val missing = manifest.capabilities.filterNot { capability ->
        when (capability) {
            WasmtimeExtensionCapability.Http -> services.http != null
            WasmtimeExtensionCapability.PersistentStorage,
            WasmtimeExtensionCapability.CacheStorage,
            WasmtimeExtensionCapability.TemporaryStorage -> services.storage != null
            WasmtimeExtensionCapability.Credentials -> services.http != null && services.credentials != null
            WasmtimeExtensionCapability.Logging -> services.logger != null
            WasmtimeExtensionCapability.Streaming -> services.resources != null
        }
    }
    require(missing.isEmpty()) {
        "extension ${manifest.id} requires host capabilities that were not granted: ${missing.joinToString()}"
    }
}

/**
 * Loads a typed extension using its generated proxy factory.
 *
 * Generated host code can pass `::MyContractWasmtimeProxy` as [proxyFactory].
 */
public suspend fun <T> loadWasmtimeExtension(
    wasm: ByteArray,
    runtime: WasmtimeRuntime? = null,
    manifest: WasmtimeExtensionManifest? = null,
    services: WasmtimeHostServices = WasmtimeHostServices(),
    limits: WasmtimeLimits = WasmtimeLimits(),
    maxArgumentBytes: Int = limits.maxHostCallBytes,
    maxResultBytes: Int = 16 * 1024 * 1024,
    proxyFactory: (WasmtimeExtensionTransport) -> T,
): WasmtimeLoadedExtension<T> {
    manifest?.let {
        require(it.apiVersion == WASMTIME_EXTENSION_API_VERSION) {
            "unsupported Wasmtime extension API version ${it.apiVersion}; host supports $WASMTIME_EXTENSION_API_VERSION"
        }
        validateWasmtimeExtensionCapabilities(it, services)
    }
    services.logger?.log(
        WasmtimeExtensionLogEvent(
            manifest?.id,
            WasmtimeExtensionLogEvent.Level.Info,
            "Loading extension${manifest?.let { " ${it.name} ${it.version}" }.orEmpty()}",
        )
    )
    val transport = createWasmtimeExtensionTransport(
        wasm = wasm,
        limits = limits,
        services = services,
        runtime = runtime,
        manifest = manifest,
        maxArgumentBytes = maxArgumentBytes,
        maxResultBytes = maxResultBytes,
    )
    try {
        transport.invoke(EXTENSION_ON_LOAD_METHOD)
    } catch (failure: Throwable) {
        transport.close()
        services.logger?.log(
            WasmtimeExtensionLogEvent(
                manifest?.id,
                WasmtimeExtensionLogEvent.Level.Error,
                "Extension onLoad failed",
                failure,
            )
        )
        throw failure
    }
    return WasmtimeLoadedExtension(proxyFactory(transport), manifest, transport, services.logger)
}

/** Test helper that guarantees the extension transport is closed after [block]. */
public suspend fun <T, R> withWasmtimeExtension(
    wasm: ByteArray,
    runtime: WasmtimeRuntime? = null,
    manifest: WasmtimeExtensionManifest? = null,
    services: WasmtimeHostServices = WasmtimeHostServices(),
    limits: WasmtimeLimits = WasmtimeLimits(),
    proxyFactory: (WasmtimeExtensionTransport) -> T,
    block: suspend (T) -> R,
): R {
    val loaded = loadWasmtimeExtension(
        wasm = wasm,
        runtime = runtime,
        manifest = manifest,
        services = services,
        limits = limits,
        proxyFactory = proxyFactory,
    )
    return try {
        block(loaded.api)
    } finally {
        loaded.shutdown()
    }
}

/** Pull-based byte stream for large host-side payloads. */
public class WasmtimeByteStream internal constructor(
    private val readChunk: suspend (maxBytes: Int) -> ByteArray?,
    private val closeAction: () -> Unit = {},
) {
    public suspend fun read(maxBytes: Int = 64 * 1024): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        return readChunk(maxBytes)
    }

    public fun close() { closeAction() }
}

/** Creates a host resource stream whose data is requested lazily in bounded chunks by the guest. */
public fun wasmtimeByteStream(
    readChunk: suspend (maxBytes: Int) -> ByteArray?,
    close: () -> Unit = {},
): WasmtimeByteStream = WasmtimeByteStream(readChunk, close)

/** Creates a chunked stream over existing bytes. Useful for adapters while avoiding giant downstream copies. */
public fun ByteArray.asWasmtimeByteStream(): WasmtimeByteStream {
    var offset = 0
    return WasmtimeByteStream(readChunk = { maxBytes ->
        if (offset >= size) null else {
            val end = (offset + maxBytes).coerceAtMost(size)
            copyOfRange(offset, end).also { offset = end }
        }
    })
}
