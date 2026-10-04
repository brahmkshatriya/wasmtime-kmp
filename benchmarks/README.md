# Wasmtime vs Kotlin/Native benchmark

This benchmark compares the Wasmtime extension path against direct Kotlin/Native Linux execution in the same release executable.

The code is intentionally split by responsibility so the benchmark runner reads like the demo rather than exposing Wasmtime plumbing in `main()`:

- `linux/Main.kt` only loads the environment and runs the suite;
- `linux/BenchmarkEnvironment.kt` owns runtime/module loading, storage, and the host HTTP capability;
- `linux/WasmBenchmarkGuest.kt` wraps the benchmark Wasm exports behind typed Kotlin methods and named operations;
- `linux/NativeBenchmarks.kt` contains the matching Kotlin/Native baselines;
- `linux/BenchmarkHarness.kt` owns warmups, alternating samples, medians, formatting, and machine-readable output;
- `linux/BenchmarkSuite.kt` is the readable list of benchmark cases and semantic checks;
- `guest/BenchmarkGuest.kt` keeps the tiny measurement-specific Wasm ABI in one place so splitting helpers does not change guest code size or coroutine behavior.

The native and Wasm cases use the same algorithm and, where applicable, the same libraries:

- compute: identical Int mixing loop;
- coroutines: kotlinx.coroutines `yield()` and `delay(1)`;
- files: kotlinx-io `SystemFileSystem`, with Wasm using sandboxed `/data` and native using `/tmp`;
- network: Ktor Curl directly for native, versus guest Ktor -> `ktor_wasi` -> the same host Ktor Curl client for Wasm.

Network traffic uses the local HTTP/1.1 server in `server.py` with `TCP_NODELAY`, so measurements do not depend on internet latency.

Build:

```bash
./gradlew :benchmarks:guest:exportWasmtimeExtension \
  :benchmarks:linux:linkReleaseExecutableLinuxX64
```

Run the server in one terminal:

```bash
python3 benchmarks/server.py
```

Run the benchmark from the repository root:

```bash
benchmarks/linux/build/bin/linuxX64/releaseExecutable/wasmtime-kmp-benchmark.kexe
```

Results are emitted as machine-readable `RESULT|...` lines plus human-readable summaries. The benchmark alternates native and Wasm samples after warmup to reduce frequency/thermal drift.


## Current optimized results

Linux x86_64 release benchmark, local HTTP/1.1 loopback server. Times below are medians from the benchmark harness; ratios can move when the very fast native file/network baseline varies, so absolute Wasm time is the more useful comparison.

| Workload | Kotlin/Native | Wasmtime extension | Wasm / native |
| --- | ---: | ---: | ---: |
| 20M integer-mix iterations | 42.350 ms | 51.678 ms | 1.220x |
| 50k coroutine `yield()` | 9.736 ms | 32.772 ms | 3.366x |
| 50 x `delay(1 ms)` | 51.815 ms | 55.631 ms | 1.074x |
| 500 x 4 KiB write + read | 4.923 ms | 62.006 ms | 12.595x |
| 32 x 256 KiB write + read | 9.812 ms | 176.389 ms | 17.976x |
| 10 x local HTTP, 1 KiB | 2.473 ms | **21.684 ms** | 8.767x |
| 5 x local HTTP, 64 KiB | 1.837 ms | **14.853 ms** | 8.085x |

Uncached shared-runtime compile + instantiate is about 3.32–3.42 s on this host. Runtime resource reads are only a few milliseconds.


### Compiled-module startup cache

Native/JVM hosts persist Wasmtime's serialized compiled modules in a SHA-256-addressed local cache. The cache uses Wasmtime's existing serialization/deserialization APIs, so no additional Wasmtime cache/zstd feature is linked into the compact runtime. Cache files are opened with `O_NOFOLLOW`, validated on the open descriptor, deserialized through `/proc/self/fd/<fd>` so Wasmtime consumes that exact inode, and rechecked afterward; a bounded descriptor-to-memory fallback is retained for environments without procfs fd paths. Epoch-instrumented and normal finite-fuel code use distinct cache keys. Incompatible or corrupt artifacts are discarded and recompiled automatically.

For the current 21-module runtime plus benchmark guest:

| Startup mode | Compile + instantiate |
| --- | ---: |
| cache disabled | 3.320 s |
| empty cache, while producing artifacts | 3.420 s |
| warm serialized cache | **60.582 ms** |

The warm path is about **55x faster** than the uncached baseline. The current cache contains 22 artifacts and occupies about 55 MiB on disk. Cache files are content-addressed by SHA-256 and created with mode `0600`. Set `WASMTIME_KMP_CACHE_DIR=/absolute/path` to choose a location or set it to `off` / `0` to disable compiled-module caching.

Compared with the initial implementation, the Wasm-side median time improved approximately:

- 4 KiB file cycles: ~160 ms -> ~63 ms (about 2.5x faster);
- 256 KiB file cycles: ~650 ms -> ~186 ms (about 3.5x faster);
- 1 KiB loopback HTTP: ~207 ms -> typically ~19-23 ms for 10 requests (about 9-11x faster);
- 64 KiB loopback HTTP: ~119 ms -> typically ~14-16 ms for 5 requests (about 7-8x faster).

The main changes are bulk `ktor_wasi` request/response copies instead of one host import per byte, completion-driven async HTTP waiting instead of millisecond polling, larger WASI file transfer windows, direct kotlinx-io segment-array access, and 64-bit linear-memory transfers for file buffers. Compute and coroutine execution code was intentionally left unchanged.

Plain Wasm/WASI `HttpClient()` GET requests now also have a narrowly scoped buffered fast path. It is used only when the client has the default configuration, the engine explicitly advertises support, the request is a GET with `EmptyContent`, there are no request attributes/capabilities, and the client pipelines have not been modified after construction. The fast path preserves request/response lifecycle events, default redirect behavior (including relative `Location`, HTTPS downgrade blocking, and cross-authority authorization stripping), header/capability validation, and cancellation wiring. It reuses the already-buffered response body and handles the common default body transforms (`ByteArray`, `Source`/`bodyAsText`, `ByteReadChannel`, `Unit`, `Int`, and `HttpStatusCode`) without rebuilding the generic Ktor send/receive pipeline. Clients with plugins, direct pipeline interceptors, non-GET requests, request bodies, `expectSuccess`, or other non-default behavior automatically fall back to the normal Ktor path.

A same-process A/B against that forced generic path isolates the guest Ktor savings from socket noise:

| Mock-host request batch | Fast default GET | Generic Ktor pipeline | Fast-path speedup |
| --- | ---: | ---: | ---: |
| 20 x 1 KiB | **24.269 ms** (1.213 ms/request) | 41.618 ms (2.081 ms/request) | **1.715x** |
| 10 x 64 KiB | **20.862 ms** (2.086 ms/request) | 30.723 ms (3.072 ms/request) | **1.473x** |

The benchmark also checks repeated body reads, POST fallback, configured-client fallback, a synthetic 302 redirect, and direct request-pipeline interceptor fallback. The raw Wasm/host HTTP ABI remains in the tens-of-microseconds range; the remaining ~1-3 ms/request on localhost is predominantly guest Ktor object/coroutine work plus the host HTTP stack rather than Wasmtime boundary cost.
