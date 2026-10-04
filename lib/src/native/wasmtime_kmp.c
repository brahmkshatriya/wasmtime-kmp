#define _XOPEN_SOURCE 700
#define _POSIX_C_SOURCE 200809L
#include "wasmtime_kmp.h"
#include "wasi_lite.h"

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <sched.h>
#include <errno.h>
#include <fcntl.h>
#include <dirent.h>
#include <sys/stat.h>
#include <time.h>
#include <sys/types.h>
#include <unistd.h>
#include <stdatomic.h>
#include <limits.h>
#include <pthread.h>

#include "windows_compat.h"

#include <wasmtime.h>
#include <wasmtime/async.h>

#if defined(__ANDROID__) || defined(WASMTIME_KMP_JNI)
#include <jni.h>
#include <poll.h>
#endif

struct wasmtime_kmp_module {
    atomic_uint refs;
    atomic_int closing;
    wasm_engine_t *engine;
    wasmtime_module_t *module;
    char *cache_dir;
    int has_imports;
    int epoch_interruption_enabled;
    pthread_mutex_t epoch_mutex;
    pthread_cond_t epoch_cond;
    pthread_t epoch_thread;
    int epoch_sync_initialized;
    int epoch_thread_started;
    int epoch_stop;
    unsigned timed_instances;
};

static char *wasmtime_kmp_platform_cache_root = NULL;

typedef struct wasmtime_kmp_http_request {
    int active;
    uint8_t *request_metadata;
    size_t request_metadata_len;
    uint8_t *request_body;
    size_t request_body_len;
    uint8_t *response_metadata;
    size_t response_metadata_len;
    uint8_t *response_body;
    size_t response_body_len;
} wasmtime_kmp_http_request_t;

struct wasmtime_kmp_instance {
    atomic_uint refs;
    atomic_int closing;
    atomic_int busy;
    wasmtime_store_t *store;
    wasmtime_linker_t *linker;
    wasmtime_instance_t instance;
    wasmtime_kmp_module_t *module_owner;
    uint64_t max_execution_millis;
    wasmtime_kmp_http_handler_t http_handler;
    wasmtime_kmp_http_request_t http_request;
    wasmtime_kmp_wasi_lite_t *wasi;
    size_t max_host_call_bytes;
    size_t max_http_response_bytes;
    int has_async_http;
};

struct wasmtime_kmp_func_i32_2 {
    atomic_uint refs;
    atomic_int closed;
    wasmtime_kmp_instance_t *instance;
    wasmtime_func_t function;
};

struct wasmtime_kmp_func_i32_2_future {
    wasmtime_kmp_func_i32_2_t *function;
    wasmtime_kmp_instance_t *instance;
    uint64_t deadline_ns;
    wasmtime_call_future_t *future;
    wasmtime_val_t args[2];
    wasmtime_val_t result;
    wasm_trap_t *trap;
    wasmtime_error_t *error;
    int completed;
};

static void wasmtime_kmp_module_release(wasmtime_kmp_module_t *module);
static void wasmtime_kmp_instance_release(wasmtime_kmp_instance_t *instance);
static void wasmtime_kmp_function_release(wasmtime_kmp_func_i32_2_t *function);

static char *copy_message(const char *data, size_t size) {
    char *out = (char *) malloc(size + 1);
    if (out == NULL) return NULL;
    memcpy(out, data, size);
    out[size] = '\0';
    return out;
}

static char *copy_cstr(const char *data) {
    return copy_message(data, strlen(data));
}


typedef struct wasmtime_kmp_sha256 {
    uint32_t state[8];
} wasmtime_kmp_sha256_t;

static uint32_t sha256_rotr(uint32_t value, unsigned shift) {
    return (value >> shift) | (value << (32 - shift));
}

static void sha256_transform(wasmtime_kmp_sha256_t *sha, const uint8_t block[64]) {
    static const uint32_t k[64] = {
        0x428a2f98U,0x71374491U,0xb5c0fbcfU,0xe9b5dba5U,0x3956c25bU,0x59f111f1U,0x923f82a4U,0xab1c5ed5U,
        0xd807aa98U,0x12835b01U,0x243185beU,0x550c7dc3U,0x72be5d74U,0x80deb1feU,0x9bdc06a7U,0xc19bf174U,
        0xe49b69c1U,0xefbe4786U,0x0fc19dc6U,0x240ca1ccU,0x2de92c6fU,0x4a7484aaU,0x5cb0a9dcU,0x76f988daU,
        0x983e5152U,0xa831c66dU,0xb00327c8U,0xbf597fc7U,0xc6e00bf3U,0xd5a79147U,0x06ca6351U,0x14292967U,
        0x27b70a85U,0x2e1b2138U,0x4d2c6dfcU,0x53380d13U,0x650a7354U,0x766a0abbU,0x81c2c92eU,0x92722c85U,
        0xa2bfe8a1U,0xa81a664bU,0xc24b8b70U,0xc76c51a3U,0xd192e819U,0xd6990624U,0xf40e3585U,0x106aa070U,
        0x19a4c116U,0x1e376c08U,0x2748774cU,0x34b0bcb5U,0x391c0cb3U,0x4ed8aa4aU,0x5b9cca4fU,0x682e6ff3U,
        0x748f82eeU,0x78a5636fU,0x84c87814U,0x8cc70208U,0x90befffaU,0xa4506cebU,0xbef9a3f7U,0xc67178f2U,
    };
    uint32_t w[64];
    for (size_t i = 0; i < 16; i++) {
        size_t o = i * 4;
        w[i] = ((uint32_t) block[o] << 24) | ((uint32_t) block[o + 1] << 16)
            | ((uint32_t) block[o + 2] << 8) | block[o + 3];
    }
    for (size_t i = 16; i < 64; i++) {
        uint32_t s0 = sha256_rotr(w[i - 15], 7) ^ sha256_rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = sha256_rotr(w[i - 2], 17) ^ sha256_rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    uint32_t a=sha->state[0], b=sha->state[1], c=sha->state[2], d=sha->state[3];
    uint32_t e=sha->state[4], f=sha->state[5], g=sha->state[6], h=sha->state[7];
    for (size_t i = 0; i < 64; i++) {
        uint32_t s1 = sha256_rotr(e,6) ^ sha256_rotr(e,11) ^ sha256_rotr(e,25);
        uint32_t ch = (e & f) ^ ((~e) & g);
        uint32_t t1 = h + s1 + ch + k[i] + w[i];
        uint32_t s0 = sha256_rotr(a,2) ^ sha256_rotr(a,13) ^ sha256_rotr(a,22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t t2 = s0 + maj;
        h=g; g=f; f=e; e=d+t1; d=c; c=b; b=a; a=t1+t2;
    }
    sha->state[0]+=a; sha->state[1]+=b; sha->state[2]+=c; sha->state[3]+=d;
    sha->state[4]+=e; sha->state[5]+=f; sha->state[6]+=g; sha->state[7]+=h;
}

static void sha256_digest(const uint8_t *data, size_t size, uint8_t out[32]) {
    wasmtime_kmp_sha256_t sha = {{
        0x6a09e667U,0xbb67ae85U,0x3c6ef372U,0xa54ff53aU,
        0x510e527fU,0x9b05688cU,0x1f83d9abU,0x5be0cd19U,
    }};
    size_t offset = 0;
    while (size - offset >= 64) {
        sha256_transform(&sha, data + offset);
        offset += 64;
    }
    uint8_t tail[128] = {0};
    size_t tail_size = size - offset;
    if (tail_size > 0) memcpy(tail, data + offset, tail_size);
    tail[tail_size] = 0x80;
    size_t final_size = tail_size < 56 ? 64 : 128;
    uint64_t bits = (uint64_t) size * 8U;
    for (size_t i = 0; i < 8; i++) {
        tail[final_size - 1 - i] = (uint8_t) (bits >> (i * 8));
    }
    sha256_transform(&sha, tail);
    if (final_size == 128) sha256_transform(&sha, tail + 64);
    for (size_t i = 0; i < 8; i++) {
        out[i * 4] = (uint8_t) (sha.state[i] >> 24);
        out[i * 4 + 1] = (uint8_t) (sha.state[i] >> 16);
        out[i * 4 + 2] = (uint8_t) (sha.state[i] >> 8);
        out[i * 4 + 3] = (uint8_t) sha.state[i];
    }
}

static void sha256_hex(const uint8_t *data, size_t size, char out[65]) {
    static const char hex[] = "0123456789abcdef";
    uint8_t digest[32];
    sha256_digest(data, size, digest);
    for (size_t i = 0; i < sizeof(digest); i++) {
        out[i * 2] = hex[digest[i] >> 4];
        out[i * 2 + 1] = hex[digest[i] & 15];
    }
    out[64] = '\0';
}

static char *error_message(wasmtime_error_t *error) {
    wasm_name_t message;
    wasmtime_error_message(error, &message);
    char *out = copy_message(message.data, message.size);
    wasm_name_delete(&message);
    wasmtime_error_delete(error);
    return out;
}

static char *trap_message(wasm_trap_t *trap) {
    wasm_name_t message;
    wasm_trap_message(trap, &message);
    char *out = copy_message(message.data, message.size);
    wasm_name_delete(&message);
    wasm_trap_delete(trap);
    return out;
}

static void set_error(char **error_out, char *message) {
    if (error_out != NULL) {
        *error_out = message;
    } else {
        free(message);
    }
}

static int atomic_ref_try_retain(atomic_uint *refs) {
    unsigned current = atomic_load_explicit(refs, memory_order_acquire);
    while (current != 0) {
        if (current == UINT_MAX) return 0;
        if (atomic_compare_exchange_weak_explicit(
                refs, &current, current + 1,
                memory_order_acq_rel, memory_order_acquire
            )) return 1;
    }
    return 0;
}

static int wasmtime_kmp_instance_try_retain(wasmtime_kmp_instance_t *instance) {
    if (instance == NULL || atomic_load_explicit(&instance->closing, memory_order_acquire)) return 0;
    if (!atomic_ref_try_retain(&instance->refs)) return 0;
    if (atomic_load_explicit(&instance->closing, memory_order_acquire)) {
        wasmtime_kmp_instance_release(instance);
        return 0;
    }
    return 1;
}

static int wasmtime_kmp_instance_enter(wasmtime_kmp_instance_t *instance, char **error_out) {
    if (!wasmtime_kmp_instance_try_retain(instance)) {
        set_error(error_out, copy_cstr("Wasmtime instance is closed"));
        return 0;
    }
    int expected = 0;
    if (!atomic_compare_exchange_strong_explicit(
            &instance->busy, &expected, 1,
            memory_order_acq_rel, memory_order_acquire
        )) {
        wasmtime_kmp_instance_release(instance);
        set_error(error_out, copy_cstr("Wasmtime instance is already executing"));
        return 0;
    }
    return 1;
}

static void wasmtime_kmp_instance_leave(wasmtime_kmp_instance_t *instance) {
    atomic_store_explicit(&instance->busy, 0, memory_order_release);
    wasmtime_kmp_instance_release(instance);
}

/*
 * Resolved functions retain their owning instance for the lifetime of the
 * function handle. Calls therefore do not need a second instance refcount
 * round-trip on the hot path; they only need the single-store concurrency
 * guard. Resolution itself still uses the fully retained enter/leave helpers
 * above because no function handle exists yet.
 */
static int wasmtime_kmp_instance_enter_borrowed(
    wasmtime_kmp_instance_t *instance,
    char **error_out
) {
    if (instance == NULL || atomic_load_explicit(&instance->closing, memory_order_acquire)) {
        set_error(error_out, copy_cstr("Wasmtime instance is closed"));
        return 0;
    }
    int expected = 0;
    if (!atomic_compare_exchange_strong_explicit(
            &instance->busy, &expected, 1,
            memory_order_acq_rel, memory_order_acquire
        )) {
        set_error(error_out, copy_cstr("Wasmtime instance is already executing"));
        return 0;
    }
    if (atomic_load_explicit(&instance->closing, memory_order_acquire)) {
        atomic_store_explicit(&instance->busy, 0, memory_order_release);
        set_error(error_out, copy_cstr("Wasmtime instance is closed"));
        return 0;
    }
    return 1;
}

static void wasmtime_kmp_instance_leave_borrowed(wasmtime_kmp_instance_t *instance) {
    atomic_store_explicit(&instance->busy, 0, memory_order_release);
}

static int wasmtime_kmp_function_try_retain(wasmtime_kmp_func_i32_2_t *function) {
    if (function == NULL || atomic_load_explicit(&function->closed, memory_order_acquire)) return 0;
    if (!atomic_ref_try_retain(&function->refs)) return 0;
    if (atomic_load_explicit(&function->closed, memory_order_acquire)) {
        wasmtime_kmp_function_release(function);
        return 0;
    }
    return 1;
}



#define WASMTIME_KMP_EPOCH_TICK_MILLIS UINT64_C(10)

static uint64_t monotonic_now_ns(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return (uint64_t) ts.tv_sec * UINT64_C(1000000000) + (uint64_t) ts.tv_nsec;
}

static void realtime_after_millis(uint64_t millis, struct timespec *out) {
    clock_gettime(CLOCK_REALTIME, out);
    uint64_t add_ns = (millis % 1000) * UINT64_C(1000000);
    uint64_t ns = (uint64_t) out->tv_nsec + add_ns;
    out->tv_sec += (time_t) (millis / 1000 + ns / UINT64_C(1000000000));
    out->tv_nsec = (long) (ns % UINT64_C(1000000000));
}

static void *wasmtime_kmp_epoch_thread_main(void *data) {
    wasmtime_kmp_module_t *module = data;
    pthread_mutex_lock(&module->epoch_mutex);
    while (!module->epoch_stop) {
        while (!module->epoch_stop && module->timed_instances == 0) {
            pthread_cond_wait(&module->epoch_cond, &module->epoch_mutex);
        }
        if (module->epoch_stop) break;
        struct timespec deadline;
        realtime_after_millis(WASMTIME_KMP_EPOCH_TICK_MILLIS, &deadline);
        int rc = pthread_cond_timedwait(&module->epoch_cond, &module->epoch_mutex, &deadline);
        if (rc == ETIMEDOUT && !module->epoch_stop && module->timed_instances > 0) {
            wasm_engine_t *engine = module->engine;
            pthread_mutex_unlock(&module->epoch_mutex);
            wasmtime_engine_increment_epoch(engine);
            pthread_mutex_lock(&module->epoch_mutex);
        }
    }
    pthread_mutex_unlock(&module->epoch_mutex);
    return NULL;
}

static int wasmtime_kmp_module_try_retain(wasmtime_kmp_module_t *module) {
    if (module == NULL || atomic_load_explicit(&module->closing, memory_order_acquire)) return 0;
    if (!atomic_ref_try_retain(&module->refs)) return 0;
    if (atomic_load_explicit(&module->closing, memory_order_acquire)) {
        wasmtime_kmp_module_release(module);
        return 0;
    }
    return 1;
}

static int wasmtime_kmp_module_enable_ticker(wasmtime_kmp_module_t *module) {
    pthread_mutex_lock(&module->epoch_mutex);
    if (!module->epoch_thread_started) {
        module->epoch_stop = 0;
        if (pthread_create(&module->epoch_thread, NULL, wasmtime_kmp_epoch_thread_main, module) != 0) {
            pthread_mutex_unlock(&module->epoch_mutex);
            return 0;
        }
        module->epoch_thread_started = 1;
    }
    module->timed_instances++;
    pthread_cond_signal(&module->epoch_cond);
    pthread_mutex_unlock(&module->epoch_mutex);
    return 1;
}

static void wasmtime_kmp_module_disable_ticker(wasmtime_kmp_module_t *module) {
    pthread_mutex_lock(&module->epoch_mutex);
    if (module->timed_instances > 0) module->timed_instances--;
    pthread_cond_signal(&module->epoch_cond);
    pthread_mutex_unlock(&module->epoch_mutex);
}

static uint64_t execution_deadline_ticks(uint64_t millis) {
    if (millis == 0) return 0;
    uint64_t ticks = millis / WASMTIME_KMP_EPOCH_TICK_MILLIS;
    if (millis % WASMTIME_KMP_EPOCH_TICK_MILLIS != 0) ticks++;
    return ticks == 0 ? 1 : ticks;
}

static uint64_t execution_deadline_ns(uint64_t millis) {
    if (millis == 0) return 0;
    uint64_t now = monotonic_now_ns();
    uint64_t add = millis > UINT64_MAX / UINT64_C(1000000)
        ? UINT64_MAX : millis * UINT64_C(1000000);
    return UINT64_MAX - now < add ? UINT64_MAX : now + add;
}

static int execution_deadline_expired(uint64_t deadline_ns) {
    return deadline_ns != 0 && monotonic_now_ns() >= deadline_ns;
}

static void reset_execution_deadline(wasmtime_kmp_instance_t *instance) {
    if (instance == NULL || instance->module_owner == NULL
        || !instance->module_owner->epoch_interruption_enabled) return;
    /* With epoch interruption enabled Wasmtime stores start at deadline 0,
     * which means immediate interruption. A zero SDK limit means disabled, so
     * explicitly place that deadline effectively out of reach. */
    uint64_t ticks = instance->max_execution_millis == 0
        ? UINT64_MAX / UINT64_C(2)
        : execution_deadline_ticks(instance->max_execution_millis);
    wasmtime_context_set_epoch_deadline(wasmtime_store_context(instance->store), ticks);
}

#define WASMTIME_KMP_CACHE_MAX_BYTES (UINT64_C(128) * 1024 * 1024)
#define WASMTIME_KMP_CACHE_MAX_ENTRIES 128
#define WASMTIME_KMP_CACHE_MAX_FILE_BYTES (UINT64_C(64) * 1024 * 1024)

static int ensure_directory_tree(const char *path) {
    if (path == NULL || path[0] == '\0') return 0;
    char *copy = copy_cstr(path);
    if (copy == NULL) return 0;

    size_t len = strlen(copy);
    while (len > 1 && copy[len - 1] == '/') copy[--len] = '\0';
    for (char *cursor = copy + 1; *cursor != '\0'; cursor++) {
        if (*cursor != '/' && *cursor != '\\') continue;
#ifdef _WIN32
        /* Do not attempt to create the drive prefix in C:\... paths. */
        if (cursor == copy + 2 && copy[1] == ':') continue;
#endif
        char separator = *cursor;
        *cursor = '\0';
        if (copy[0] != '\0' && mkdir(copy, 0700) != 0 && errno != EEXIST) {
            free(copy);
            return 0;
        }
        *cursor = separator;
    }
    int ok = mkdir(copy, 0700) == 0 || errno == EEXIST;
    free(copy);
    return ok;
}

static int cache_directory_trusted(const char *path) {
    struct stat st;
    if (path == NULL || lstat(path, &st) != 0) return 0;
    if (!S_ISDIR(st.st_mode) || st.st_uid != geteuid()) return 0;
    return (st.st_mode & (S_IWGRP | S_IWOTH)) == 0;
}

static char *cache_path_join(const char *base, const char *suffix) {
    size_t n = strlen(base) + 1 + strlen(suffix) + 1;
    char *out = malloc(n);
    if (out == NULL) return NULL;
    snprintf(out, n, "%s/%s", base, suffix);
    return out;
}

static void set_platform_cache_root(const char *path) {
    free(wasmtime_kmp_platform_cache_root);
    wasmtime_kmp_platform_cache_root = NULL;
    if (path != NULL && path[0] == '/') wasmtime_kmp_platform_cache_root = copy_cstr(path);
}

static char *trusted_cache_dir(char *path) {
    if (path != NULL && ensure_directory_tree(path) && cache_directory_trusted(path)) return path;
    free(path);
    return NULL;
}

static char *default_module_cache_dir(void) {
#ifdef _WIN32
    /* Serialized modules are native-code-equivalent trusted data. Keep the cache
     * disabled until Windows ACL validation is as strict as the POSIX owner/mode path. */
    return NULL;
#else
    const char *override = getenv("WASMTIME_KMP_CACHE_DIR");
    if (override != NULL && override[0] != '\0') {
        if (strcmp(override, "0") == 0 || strcmp(override, "off") == 0 || strcmp(override, "false") == 0) {
            return NULL;
        }
        if (override[0] != '/') return NULL;
        return trusted_cache_dir(cache_path_join(override, "modules-v1"));
    }
    if (wasmtime_kmp_platform_cache_root != NULL) {
        char *path = trusted_cache_dir(cache_path_join(wasmtime_kmp_platform_cache_root, "modules-v1"));
        if (path != NULL) return path;
    }
    const char *root = getenv("XDG_CACHE_HOME");
    char *base = NULL;
    if (root != NULL && root[0] == '/') base = cache_path_join(root, "wasmtime-kmp");
#ifdef __ANDROID__
    if (base == NULL) {
        root = getenv("TMPDIR");
        if (root != NULL && root[0] == '/') base = cache_path_join(root, "wasmtime-kmp");
    }
#endif
    if (base == NULL) {
        root = getenv("HOME");
        if (root != NULL && root[0] == '/') {
#ifdef __ANDROID__
            base = cache_path_join(root, "cache/wasmtime-kmp");
#else
            base = cache_path_join(root, ".cache/wasmtime-kmp");
#endif
        }
    }
    if (base == NULL) return NULL;
    char *path = cache_path_join(base, "modules-v1");
    free(base);
    return trusted_cache_dir(path);
#endif
}

static int path_is_same_or_child(const char *path, const char *parent) {
    size_t parent_len = strlen(parent);
    if (strncmp(path, parent, parent_len) != 0) return 0;
    return path[parent_len] == '\0' || (parent_len > 0 && parent[parent_len - 1] == '/') || path[parent_len] == '/';
}

static int paths_overlap(const char *left, const char *right) {
    if (left == NULL || right == NULL) return 0;
    char *left_real = realpath(left, NULL);
    char *right_real = realpath(right, NULL);
    if (left_real == NULL || right_real == NULL) {
        free(left_real);
        free(right_real);
        return 0;
    }
    int overlap = path_is_same_or_child(left_real, right_real) || path_is_same_or_child(right_real, left_real);
    free(left_real);
    free(right_real);
    return overlap;
}

static char *module_cache_file(
    const char *cache_dir,
    const uint8_t *wasm,
    size_t wasm_len,
    int epoch_interruption_enabled
) {
    if (cache_dir == NULL) return NULL;
    char hash[65];
    sha256_hex(wasm, wasm_len, hash);
    char file[84];
#ifdef WASMTIME_KMP_FORCE_PULLEY
    const char backend = 'p';
#else
    const char backend = 'n';
#endif
    snprintf(file, sizeof(file), "%s-%c-%c.cwasm", hash, backend, epoch_interruption_enabled ? 'e' : 'f');
    return cache_path_join(cache_dir, file);
}

typedef struct module_cache_entry {
    char *path;
    uint64_t size;
    time_t mtime;
} module_cache_entry_t;

static int module_cache_entry_compare(const void *left, const void *right) {
    const module_cache_entry_t *a = left;
    const module_cache_entry_t *b = right;
    if (a->mtime < b->mtime) return -1;
    if (a->mtime > b->mtime) return 1;
    return strcmp(a->path, b->path);
}

static void module_cache_prune(const char *cache_dir) {
    if (!cache_directory_trusted(cache_dir)) return;
    DIR *dir = opendir(cache_dir);
    if (dir == NULL) return;
    module_cache_entry_t *entries = NULL;
    size_t count = 0, capacity = 0;
    uint64_t total = 0;
    struct dirent *item;
    while ((item = readdir(dir)) != NULL) {
        size_t name_len = strlen(item->d_name);
        if (name_len < 7 || strcmp(item->d_name + name_len - 6, ".cwasm") != 0) continue;
        char *path = cache_path_join(cache_dir, item->d_name);
        if (path == NULL) continue;
        struct stat st;
        if (lstat(path, &st) != 0 || !S_ISREG(st.st_mode) || st.st_uid != geteuid()
            || (st.st_mode & (S_IWGRP | S_IWOTH)) != 0 || st.st_size < 0) {
            free(path);
            continue;
        }
        if (count == capacity) {
            size_t next = capacity == 0 ? 32 : capacity * 2;
            module_cache_entry_t *grown = realloc(entries, next * sizeof(*entries));
            if (grown == NULL) { free(path); break; }
            entries = grown;
            capacity = next;
        }
        entries[count++] = (module_cache_entry_t) {
            .path = path,
            .size = (uint64_t) st.st_size,
            .mtime = st.st_mtime,
        };
        total += (uint64_t) st.st_size;
    }
    closedir(dir);
    qsort(entries, count, sizeof(*entries), module_cache_entry_compare);
    size_t first = 0;
    while (first < count && (count - first > WASMTIME_KMP_CACHE_MAX_ENTRIES || total > WASMTIME_KMP_CACHE_MAX_BYTES)) {
        if (unlink(entries[first].path) == 0) total -= entries[first].size;
        first++;
    }
    for (size_t i = 0; i < count; i++) free(entries[i].path);
    free(entries);
}

static int module_cache_read(
    wasm_engine_t *engine,
    const char *path,
    wasmtime_module_t **out
) {
    if (path == NULL) return 0;
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return 0;
    struct stat before;
    if (fstat(fd, &before) != 0 || !S_ISREG(before.st_mode) || before.st_uid != geteuid()
        || (before.st_mode & (S_IWGRP | S_IWOTH)) != 0 || before.st_size <= 0
        || (uint64_t) before.st_size > WASMTIME_KMP_CACHE_MAX_FILE_BYTES) {
        close(fd);
        return 0;
    }

    /* Keep Wasmtime on the already-open, O_NOFOLLOW-validated inode instead of
     * reopening the attacker-controlled pathname. Linux/Android procfs exposes
     * that descriptor as a stable path and lets Wasmtime use its optimized file
     * deserializer without an extra ~55 MiB graph-wide heap copy. */
    char fd_path[64];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);
    wasmtime_error_t *file_error = wasmtime_module_deserialize_file(engine, fd_path, out);
    if (file_error == NULL) {
        struct stat after;
        int stable = fstat(fd, &after) == 0
            && before.st_dev == after.st_dev && before.st_ino == after.st_ino
            && before.st_size == after.st_size && before.st_mtime == after.st_mtime;
        close(fd);
        if (stable) return 1;
        wasmtime_module_delete(*out);
        *out = NULL;
        unlink(path);
        return 0;
    }
    wasmtime_error_delete(file_error);

    /* Conservative fallback for environments where /proc/self/fd is unavailable:
     * copy the exact validated descriptor contents and deserialize from memory. */
    if (lseek(fd, 0, SEEK_SET) < 0) { close(fd); return 0; }
    size_t size = (size_t) before.st_size;
    uint8_t *bytes = malloc(size);
    if (bytes == NULL) { close(fd); return 0; }
    size_t offset = 0;
    while (offset < size) {
        ssize_t n = read(fd, bytes + offset, size - offset);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        offset += (size_t) n;
    }
    struct stat after;
    int stable = fstat(fd, &after) == 0
        && before.st_dev == after.st_dev && before.st_ino == after.st_ino
        && before.st_size == after.st_size && before.st_mtime == after.st_mtime;
    close(fd);
    if (offset != size || !stable) { free(bytes); return 0; }
    wasmtime_error_t *error = wasmtime_module_deserialize(engine, bytes, size, out);
    free(bytes);
    if (error != NULL) {
        wasmtime_error_delete(error);
        unlink(path);
        return 0;
    }
    return 1;
}

static void module_cache_write(const char *path, const wasmtime_module_t *module) {
    if (path == NULL || module == NULL) return;
    wasm_byte_vec_t serialized;
    wasmtime_error_t *error = wasmtime_module_serialize(module, &serialized);
    if (error != NULL) { wasmtime_error_delete(error); return; }
    if (serialized.size > WASMTIME_KMP_CACHE_MAX_FILE_BYTES) {
        wasm_byte_vec_delete(&serialized);
        return;
    }
    size_t template_size = strlen(path) + strlen(".tmp.XXXXXX") + 1;
    char *temporary = malloc(template_size);
    if (temporary == NULL) { wasm_byte_vec_delete(&serialized); return; }
    snprintf(temporary, template_size, "%s.tmp.XXXXXX", path);
    int fd = mkstemp(temporary);
    if (fd >= 0) {
        (void) fchmod(fd, 0600);
        size_t written = 0;
        while (written < serialized.size) {
            ssize_t n = write(fd, serialized.data + written, serialized.size - written);
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) break;
            written += (size_t) n;
        }
        int sync_ok = written == serialized.size && fsync(fd) == 0;
        int close_ok = close(fd) == 0;
        if (sync_ok && close_ok) {
            if (rename(temporary, path) != 0) unlink(temporary);
        } else {
            unlink(temporary);
        }
    }
    free(temporary);
    wasm_byte_vec_delete(&serialized);
}

static wasmtime_error_t *module_load_or_compile(
    wasm_engine_t *engine,
    const uint8_t *wasm,
    size_t wasm_len,
    const char *cache_dir,
    int epoch_interruption_enabled,
    wasmtime_module_t **out
) {
    char *path = module_cache_file(cache_dir, wasm, wasm_len, epoch_interruption_enabled);
    if (path != NULL && module_cache_read(engine, path, out)) {
        free(path);
        return NULL;
    }
    wasmtime_error_t *error = wasmtime_module_new(engine, wasm, wasm_len, out);
    if (error == NULL && path != NULL) {
        module_cache_write(path, *out);
        module_cache_prune(cache_dir);
    }
    free(path);
    return error;
}

static void http_handler_dispose_value(const wasmtime_kmp_http_handler_t *handler) {
    if (handler != NULL && handler->dispose != NULL) {
        handler->dispose(handler->user_data);
    }
}

static void http_free_response_buffer(
    wasmtime_kmp_instance_t *instance,
    uint8_t *buffer,
    size_t size
) {
    if (buffer == NULL) return;
    if (instance->http_handler.free_buffer != NULL) {
        instance->http_handler.free_buffer(instance->http_handler.user_data, buffer, size);
    } else {
        free(buffer);
    }
}

static void http_request_reset(wasmtime_kmp_instance_t *instance) {
    wasmtime_kmp_http_request_t *request = &instance->http_request;
    free(request->request_metadata);
    free(request->request_body);
    http_free_response_buffer(instance, request->response_metadata, request->response_metadata_len);
    http_free_response_buffer(instance, request->response_body, request->response_body_len);
    memset(request, 0, sizeof(*request));
}

static void host_result_i32(wasmtime_val_t *results, int32_t value) {
    results[0].kind = WASMTIME_I32;
    results[0].of.i32 = value;
}

static int http_caller_memory_range(
    wasmtime_caller_t *caller,
    int32_t pointer,
    int32_t length,
    uint8_t **out
) {
    if (caller == NULL || pointer < 0 || length < 0) return 0;
    wasmtime_extern_t item;
    if (!wasmtime_caller_export_get(caller, "memory", 6, &item)
        || item.kind != WASMTIME_EXTERN_MEMORY) {
        return 0;
    }
    wasmtime_context_t *context = wasmtime_caller_context(caller);
    uint8_t *data = wasmtime_memory_data(context, &item.of.memory);
    size_t size = wasmtime_memory_data_size(context, &item.of.memory);
    if (data == NULL || (uint64_t) (uint32_t) pointer + (uint32_t) length > size) return 0;
    *out = data + (uint32_t) pointer;
    return 1;
}

static int http_request_is_valid(wasmtime_kmp_instance_t *instance, int32_t handle) {
    return instance != NULL && handle == 1 && instance->http_request.active;
}

static wasm_trap_t *http_request_create_callback(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) caller; (void) nargs; (void) nresults;
    wasmtime_kmp_instance_t *instance = env;
    int32_t metadata_len = args[0].of.i32;
    int32_t body_len = args[1].of.i32;
    if (instance == NULL || instance->http_handler.execute_start == NULL || instance->http_request.active
        || metadata_len <= 0 || metadata_len > 65536 || body_len < 0
        || (instance->max_host_call_bytes > 0 && (size_t) body_len > instance->max_host_call_bytes)) {
        host_result_i32(results, -1);
        return NULL;
    }

    uint8_t *metadata = calloc((size_t) metadata_len, 1);
    uint8_t *body = body_len == 0 ? NULL : calloc((size_t) body_len, 1);
    if (metadata == NULL || (body_len > 0 && body == NULL)) {
        free(metadata); free(body);
        host_result_i32(results, -1);
        return NULL;
    }

    instance->http_request.active = 1;
    instance->http_request.request_metadata = metadata;
    instance->http_request.request_metadata_len = (size_t) metadata_len;
    instance->http_request.request_body = body;
    instance->http_request.request_body_len = (size_t) body_len;
    host_result_i32(results, 1);
    return NULL;
}

static wasm_trap_t *http_request_metadata_byte_callback(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) caller; (void) nargs; (void) nresults;
    wasmtime_kmp_instance_t *instance = env;
    int32_t handle = args[0].of.i32, index = args[1].of.i32, value = args[2].of.i32;
    if (!http_request_is_valid(instance, handle) || index < 0
        || (size_t) index >= instance->http_request.request_metadata_len || value < 0 || value > 255) {
        host_result_i32(results, -1); return NULL;
    }
    instance->http_request.request_metadata[index] = (uint8_t) value;
    host_result_i32(results, 0);
    return NULL;
}

static wasm_trap_t *http_request_body_byte_callback(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) caller; (void) nargs; (void) nresults;
    wasmtime_kmp_instance_t *instance = env;
    int32_t handle = args[0].of.i32, index = args[1].of.i32, value = args[2].of.i32;
    if (!http_request_is_valid(instance, handle) || index < 0
        || (size_t) index >= instance->http_request.request_body_len || value < 0 || value > 255) {
        host_result_i32(results, -1); return NULL;
    }
    instance->http_request.request_body[index] = (uint8_t) value;
    host_result_i32(results, 0);
    return NULL;
}

static wasm_trap_t *http_request_metadata_copy_callback(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) nargs; (void) nresults;
    wasmtime_kmp_instance_t *instance = env;
    int32_t handle = args[0].of.i32, pointer = args[1].of.i32, length = args[2].of.i32;
    uint8_t *source = NULL;
    if (!http_request_is_valid(instance, handle) || length < 0
        || (size_t) length != instance->http_request.request_metadata_len
        || (length > 0 && !http_caller_memory_range(caller, pointer, length, &source))) {
        host_result_i32(results, -1); return NULL;
    }
    if (length > 0) memcpy(instance->http_request.request_metadata, source, (size_t) length);
    host_result_i32(results, 0);
    return NULL;
}

static wasm_trap_t *http_request_body_copy_callback(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) nargs; (void) nresults;
    wasmtime_kmp_instance_t *instance = env;
    int32_t handle = args[0].of.i32, pointer = args[1].of.i32, length = args[2].of.i32;
    uint8_t *source = NULL;
    if (!http_request_is_valid(instance, handle) || length < 0
        || (size_t) length != instance->http_request.request_body_len
        || (length > 0 && !http_caller_memory_range(caller, pointer, length, &source))) {
        host_result_i32(results, -1); return NULL;
    }
    if (length > 0) memcpy(instance->http_request.request_body, source, (size_t) length);
    host_result_i32(results, 0);
    return NULL;
}

typedef struct wasmtime_kmp_http_async_call {
    wasmtime_kmp_instance_t *instance;
    void *operation;
    wasmtime_val_t *results;
} wasmtime_kmp_http_async_call_t;

static void http_async_call_finish(
    wasmtime_kmp_http_async_call_t *call,
    int status
) {
    if (call == NULL || call->results == NULL) return;
    host_result_i32(call->results, status == 1 ? 0 : -1);
}

static bool http_request_execute_continuation(void *env) {
    wasmtime_kmp_http_async_call_t *call = env;
    if (call == NULL || call->instance == NULL || call->operation == NULL) return true;

    wasmtime_kmp_instance_t *instance = call->instance;
    wasmtime_kmp_http_request_t *request = &instance->http_request;
    int status = instance->http_handler.execute_poll(
        instance->http_handler.user_data,
        call->operation,
        &request->response_metadata,
        &request->response_metadata_len,
        &request->response_body,
        &request->response_body_len
    );
    if (status == 0) return false;

    if (status != 1 || request->response_metadata == NULL || request->response_metadata_len == 0
        || request->response_metadata_len > 65536
        || (request->response_body_len > 0 && request->response_body == NULL)
        || (instance->max_http_response_bytes > 0
            && request->response_body_len > instance->max_http_response_bytes)) {
        http_free_response_buffer(instance, request->response_metadata, request->response_metadata_len);
        http_free_response_buffer(instance, request->response_body, request->response_body_len);
        request->response_metadata = NULL; request->response_metadata_len = 0;
        request->response_body = NULL; request->response_body_len = 0;
        http_async_call_finish(call, -1);
        return true;
    }

    http_async_call_finish(call, 1);
    return true;
}

static void http_request_execute_continuation_dispose(void *env) {
    wasmtime_kmp_http_async_call_t *call = env;
    if (call == NULL) return;
    if (call->instance != NULL && call->operation != NULL
        && call->instance->http_handler.execute_dispose != NULL) {
        call->instance->http_handler.execute_dispose(
            call->instance->http_handler.user_data,
            call->operation
        );
    }
    free(call);
}

static void http_request_execute_async_callback(
    void *env,
    wasmtime_caller_t *caller,
    const wasmtime_val_t *args,
    size_t nargs,
    wasmtime_val_t *results,
    size_t nresults,
    wasm_trap_t **trap_ret,
    wasmtime_async_continuation_t *continuation_ret
) {
    (void) caller; (void) nargs; (void) nresults; (void) trap_ret;
    wasmtime_kmp_instance_t *instance = env;
    int32_t handle = args[0].of.i32;

    wasmtime_kmp_http_async_call_t *call = calloc(1, sizeof(*call));
    if (call == NULL) {
        host_result_i32(results, -1);
        continuation_ret->callback = http_request_execute_continuation;
        continuation_ret->env = NULL;
        continuation_ret->finalizer = NULL;
        return;
    }
    call->instance = instance;
    call->results = results;

    if (!http_request_is_valid(instance, handle)
        || instance->http_handler.execute_start == NULL
        || instance->http_handler.execute_poll == NULL) {
        host_result_i32(results, -1);
        continuation_ret->callback = http_request_execute_continuation;
        continuation_ret->env = call;
        continuation_ret->finalizer = http_request_execute_continuation_dispose;
        return;
    }

    wasmtime_kmp_http_request_t *request = &instance->http_request;
    http_free_response_buffer(instance, request->response_metadata, request->response_metadata_len);
    http_free_response_buffer(instance, request->response_body, request->response_body_len);
    request->response_metadata = NULL; request->response_metadata_len = 0;
    request->response_body = NULL; request->response_body_len = 0;

    call->operation = instance->http_handler.execute_start(
        instance->http_handler.user_data,
        request->request_metadata,
        request->request_metadata_len,
        request->request_body,
        request->request_body_len
    );
    if (call->operation == NULL) {
        host_result_i32(results, -1);
    }

    continuation_ret->callback = http_request_execute_continuation;
    continuation_ret->env = call;
    continuation_ret->finalizer = http_request_execute_continuation_dispose;
}

static wasm_trap_t *http_response_metadata_length_callback(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) caller; (void) nargs; (void) nresults;
    wasmtime_kmp_instance_t *instance = env; int32_t handle = args[0].of.i32;
    if (!http_request_is_valid(instance, handle) || instance->http_request.response_metadata_len > INT32_MAX) {
        host_result_i32(results, -1); return NULL;
    }
    host_result_i32(results, (int32_t) instance->http_request.response_metadata_len); return NULL;
}

static wasm_trap_t *http_response_metadata_byte_callback(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) caller; (void) nargs; (void) nresults;
    wasmtime_kmp_instance_t *instance = env; int32_t handle = args[0].of.i32, index = args[1].of.i32;
    if (!http_request_is_valid(instance, handle) || index < 0
        || (size_t) index >= instance->http_request.response_metadata_len) {
        host_result_i32(results, -1); return NULL;
    }
    host_result_i32(results, instance->http_request.response_metadata[index]); return NULL;
}

static wasm_trap_t *http_response_body_length_callback(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) caller; (void) nargs; (void) nresults;
    wasmtime_kmp_instance_t *instance = env; int32_t handle = args[0].of.i32;
    if (!http_request_is_valid(instance, handle) || instance->http_request.response_body_len > INT32_MAX) {
        host_result_i32(results, -1); return NULL;
    }
    host_result_i32(results, (int32_t) instance->http_request.response_body_len); return NULL;
}

static wasm_trap_t *http_response_body_byte_callback(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) caller; (void) nargs; (void) nresults;
    wasmtime_kmp_instance_t *instance = env; int32_t handle = args[0].of.i32, index = args[1].of.i32;
    if (!http_request_is_valid(instance, handle) || index < 0
        || (size_t) index >= instance->http_request.response_body_len) {
        host_result_i32(results, -1); return NULL;
    }
    host_result_i32(results, instance->http_request.response_body[index]); return NULL;
}

static wasm_trap_t *http_response_metadata_copy_callback(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) nargs; (void) nresults;
    wasmtime_kmp_instance_t *instance = env;
    int32_t handle = args[0].of.i32, pointer = args[1].of.i32, length = args[2].of.i32;
    uint8_t *target = NULL;
    if (!http_request_is_valid(instance, handle) || length < 0
        || (size_t) length != instance->http_request.response_metadata_len
        || (length > 0 && !http_caller_memory_range(caller, pointer, length, &target))) {
        host_result_i32(results, -1); return NULL;
    }
    if (length > 0) memcpy(target, instance->http_request.response_metadata, (size_t) length);
    host_result_i32(results, 0);
    return NULL;
}

static wasm_trap_t *http_response_body_copy_callback(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) nargs; (void) nresults;
    wasmtime_kmp_instance_t *instance = env;
    int32_t handle = args[0].of.i32, pointer = args[1].of.i32, length = args[2].of.i32;
    uint8_t *target = NULL;
    if (!http_request_is_valid(instance, handle) || length < 0
        || (size_t) length != instance->http_request.response_body_len
        || (length > 0 && !http_caller_memory_range(caller, pointer, length, &target))) {
        host_result_i32(results, -1); return NULL;
    }
    if (length > 0) memcpy(target, instance->http_request.response_body, (size_t) length);
    host_result_i32(results, 0);
    return NULL;
}

static wasm_trap_t *http_request_close_callback(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) caller; (void) nargs; (void) nresults;
    wasmtime_kmp_instance_t *instance = env; int32_t handle = args[0].of.i32;
    if (!http_request_is_valid(instance, handle)) { host_result_i32(results, -1); return NULL; }
    http_request_reset(instance); host_result_i32(results, 0); return NULL;
}

static wasmtime_error_t *define_http_import(
    wasmtime_linker_t *linker, wasmtime_kmp_instance_t *instance, const char *name,
    int params, wasmtime_func_callback_t callback
) {
    wasm_functype_t *type = NULL;
    if (params == 1) type = wasm_functype_new_1_1(wasm_valtype_new_i32(), wasm_valtype_new_i32());
    else if (params == 2) type = wasm_functype_new_2_1(wasm_valtype_new_i32(), wasm_valtype_new_i32(), wasm_valtype_new_i32());
    else if (params == 3) type = wasm_functype_new_3_1(wasm_valtype_new_i32(), wasm_valtype_new_i32(), wasm_valtype_new_i32(), wasm_valtype_new_i32());
    if (type == NULL) return NULL;
    wasmtime_error_t *error = wasmtime_linker_define_func(
        linker, "ktor_wasi", strlen("ktor_wasi"), name, strlen(name), type, callback, instance, NULL
    );
    wasm_functype_delete(type); return error;
}


static wasmtime_error_t *define_http_async_import(
    wasmtime_linker_t *linker,
    wasmtime_kmp_instance_t *instance,
    const char *name,
    int params,
    wasmtime_func_async_callback_t callback
) {
    wasm_functype_t *type = NULL;
    if (params == 1) type = wasm_functype_new_1_1(wasm_valtype_new_i32(), wasm_valtype_new_i32());
    else if (params == 2) type = wasm_functype_new_2_1(wasm_valtype_new_i32(), wasm_valtype_new_i32(), wasm_valtype_new_i32());
    else if (params == 3) type = wasm_functype_new_3_1(wasm_valtype_new_i32(), wasm_valtype_new_i32(), wasm_valtype_new_i32(), wasm_valtype_new_i32());
    if (type == NULL) return NULL;
    wasmtime_error_t *error = wasmtime_linker_define_async_func(
        linker, "ktor_wasi", strlen("ktor_wasi"), name, strlen(name), type, callback, instance, NULL
    );
    wasm_functype_delete(type);
    return error;
}

static wasmtime_error_t *define_http_imports(wasmtime_linker_t *linker, wasmtime_kmp_instance_t *instance) {
    if (instance->http_handler.execute_start == NULL) return NULL;
    if (instance->http_handler.execute_poll == NULL || instance->http_handler.execute_dispose == NULL) {
        return NULL;
    }
    wasmtime_error_t *error = define_http_import(linker, instance, "request_create", 2, http_request_create_callback);
    if (error != NULL) return error;
    error = define_http_import(linker, instance, "request_metadata_byte", 3, http_request_metadata_byte_callback);
    if (error != NULL) return error;
    error = define_http_import(linker, instance, "request_body_byte", 3, http_request_body_byte_callback);
    if (error != NULL) return error;
    error = define_http_import(linker, instance, "request_metadata_copy", 3, http_request_metadata_copy_callback);
    if (error != NULL) return error;
    error = define_http_import(linker, instance, "request_body_copy", 3, http_request_body_copy_callback);
    if (error != NULL) return error;
    error = define_http_async_import(linker, instance, "request_execute", 1, http_request_execute_async_callback);
    if (error != NULL) return error;
    error = define_http_import(linker, instance, "response_metadata_length", 1, http_response_metadata_length_callback);
    if (error != NULL) return error;
    error = define_http_import(linker, instance, "response_metadata_byte", 2, http_response_metadata_byte_callback);
    if (error != NULL) return error;
    error = define_http_import(linker, instance, "response_body_length", 1, http_response_body_length_callback);
    if (error != NULL) return error;
    error = define_http_import(linker, instance, "response_body_byte", 2, http_response_body_byte_callback);
    if (error != NULL) return error;
    error = define_http_import(linker, instance, "response_metadata_copy", 3, http_response_metadata_copy_callback);
    if (error != NULL) return error;
    error = define_http_import(linker, instance, "response_body_copy", 3, http_response_body_copy_callback);
    if (error != NULL) return error;
    return define_http_import(linker, instance, "request_close", 1, http_request_close_callback);
}

static wasmtime_kmp_module_t *wasmtime_kmp_compile_internal(
    const uint8_t *wasm,
    size_t wasm_len,
    int epoch_interruption_enabled,
    char **error_out
) {
    if (error_out != NULL) *error_out = NULL;

    wasm_config_t *config = wasm_config_new();
    if (config == NULL) {
        set_error(error_out, copy_cstr("failed to create Wasmtime config"));
        return NULL;
    }

    wasmtime_config_wasm_gc_set(config, true);
    /* Kotlin/Wasm needs GC and the core exception proposal. Shared-memory threads
     * remain disabled in the compact build. Epoch instrumentation is selected only
     * when execution cannot already be bounded by finite fuel. */
    wasmtime_config_wasm_exceptions_set(config, true);
    wasmtime_config_consume_fuel_set(config, true);
    wasmtime_config_epoch_interruption_set(config, epoch_interruption_enabled != 0);
#ifdef WASMTIME_KMP_FORCE_PULLEY
    wasmtime_error_t *target_error = wasmtime_config_target_set(config, "pulley64");
    if (target_error != NULL) {
        set_error(error_out, error_message(target_error));
        wasm_config_delete(config);
        return NULL;
    }
#endif

    wasm_engine_t *engine = wasm_engine_new_with_config(config);
    if (engine == NULL) {
        set_error(error_out, copy_cstr("failed to create Wasmtime engine"));
        return NULL;
    }

    wasmtime_kmp_module_t *out = calloc(1, sizeof(wasmtime_kmp_module_t));
    if (out == NULL) {
        wasm_engine_delete(engine);
        set_error(error_out, copy_cstr("out of memory"));
        return NULL;
    }
    atomic_init(&out->refs, 1);
    atomic_init(&out->closing, 0);
    if (pthread_mutex_init(&out->epoch_mutex, NULL) != 0) {
        wasm_engine_delete(engine);
        free(out);
        set_error(error_out, copy_cstr("failed to initialize Wasmtime epoch mutex"));
        return NULL;
    }
    if (pthread_cond_init(&out->epoch_cond, NULL) != 0) {
        pthread_mutex_destroy(&out->epoch_mutex);
        wasm_engine_delete(engine);
        free(out);
        set_error(error_out, copy_cstr("failed to initialize Wasmtime epoch condition"));
        return NULL;
    }
    out->epoch_sync_initialized = 1;
    out->epoch_interruption_enabled = epoch_interruption_enabled != 0;
    out->engine = engine;
    out->cache_dir = default_module_cache_dir();
    if (out->cache_dir != NULL) module_cache_prune(out->cache_dir);

    wasmtime_error_t *error = module_load_or_compile(
        engine, wasm, wasm_len, out->cache_dir, out->epoch_interruption_enabled, &out->module
    );
    if (error != NULL) {
        set_error(error_out, error_message(error));
        wasmtime_kmp_module_close(out);
        return NULL;
    }

    wasm_importtype_vec_t imports;
    wasmtime_module_imports(out->module, &imports);
    out->has_imports = imports.size > 0;
    wasm_importtype_vec_delete(&imports);

    return out;
}

wasmtime_kmp_module_t *wasmtime_kmp_compile(
    const uint8_t *wasm,
    size_t wasm_len,
    char **error_out
) {
    /* The reusable compiled-module API preserves strict deadline support because
     * instantiate-time limits are not known at compile time. Direct load paths
     * below can select the faster fuel-bounded engine when appropriate. */
    return wasmtime_kmp_compile_internal(wasm, wasm_len, 1, error_out);
}

static int instantiate_linked_module(
    wasm_engine_t *engine,
    wasmtime_kmp_instance_t *instance,
    wasmtime_context_t *context,
    const wasmtime_kmp_linked_module_t *linked,
    const char *cache_dir,
    char **error_out
) {
    if (linked == NULL || linked->name == NULL || linked->wasm == NULL || linked->wasm_len == 0) {
        set_error(error_out, copy_cstr("invalid linked runtime module"));
        return 0;
    }

    wasmtime_module_t *compiled = NULL;
    wasmtime_error_t *error = module_load_or_compile(
        engine,
        linked->wasm,
        linked->wasm_len,
        cache_dir,
        instance->module_owner->epoch_interruption_enabled,
        &compiled
    );
    if (error != NULL) {
        set_error(error_out, error_message(error));
        return 0;
    }

    wasmtime_instance_t runtime_instance;
    wasm_trap_t *trap = NULL;
    if (instance->has_async_http) {
        wasmtime_call_future_t *future = wasmtime_linker_instantiate_async(
            instance->linker,
            context,
            compiled,
            &runtime_instance,
            &trap,
            &error
        );
        if (future == NULL) {
            wasmtime_module_delete(compiled);
            set_error(error_out, copy_cstr("failed to create async runtime instantiate future"));
            return 0;
        }
        while (!wasmtime_call_future_poll(future)) {
            sched_yield();
        }
        wasmtime_call_future_delete(future);
    } else {
        error = wasmtime_linker_instantiate(
            instance->linker,
            context,
            compiled,
            &runtime_instance,
            &trap
        );
    }
    wasmtime_module_delete(compiled);

    if (error != NULL) {
        set_error(error_out, error_message(error));
        return 0;
    }
    if (trap != NULL) {
        set_error(error_out, trap_message(trap));
        return 0;
    }

    error = wasmtime_linker_define_instance(
        instance->linker,
        context,
        linked->name,
        strlen(linked->name),
        &runtime_instance
    );
    if (error != NULL) {
        set_error(error_out, error_message(error));
        return 0;
    }
    return 1;
}

wasmtime_kmp_instance_t *wasmtime_kmp_instantiate_with_runtime(
    wasmtime_kmp_module_t *module,
    const wasmtime_kmp_linked_module_t *runtime_modules,
    size_t runtime_module_count,
    const wasmtime_kmp_limits_t *limits,
    const wasmtime_kmp_http_handler_t *http_handler,
    const wasmtime_kmp_storage_t *storage,
    char **error_out
) {
    if (error_out != NULL) *error_out = NULL;
    if (module == NULL) {
        http_handler_dispose_value(http_handler);
        set_error(error_out, copy_cstr("module must not be null"));
        return NULL;
    }
    if (limits == NULL) {
        http_handler_dispose_value(http_handler);
        set_error(error_out, copy_cstr("limits must not be null"));
        return NULL;
    }
    if (!wasmtime_kmp_module_try_retain(module)) {
        http_handler_dispose_value(http_handler);
        set_error(error_out, copy_cstr("Wasmtime module is closed"));
        return NULL;
    }

    wasmtime_kmp_instance_t *out = calloc(1, sizeof(wasmtime_kmp_instance_t));
    if (out == NULL) {
        wasmtime_kmp_module_release(module);
        http_handler_dispose_value(http_handler);
        set_error(error_out, copy_cstr("out of memory"));
        return NULL;
    }
    atomic_init(&out->refs, 1);
    atomic_init(&out->closing, 0);
    atomic_init(&out->busy, 0);
    out->module_owner = module;
    out->max_execution_millis = limits->max_execution_millis > 0
        ? (uint64_t) limits->max_execution_millis : 0;
    if (out->max_execution_millis > 0 && module->epoch_interruption_enabled
        && !wasmtime_kmp_module_enable_ticker(module)) {
        set_error(error_out, copy_cstr("failed to start Wasmtime execution deadline ticker"));
        wasmtime_kmp_close(out);
        return NULL;
    }
    if (http_handler != NULL) {
        out->http_handler = *http_handler;
        out->has_async_http = http_handler->execute_start != NULL;
    }
    out->max_host_call_bytes = limits->max_host_call_bytes > 0
        ? (size_t) limits->max_host_call_bytes : 0;
    out->max_http_response_bytes = limits->max_http_response_bytes > 0
        ? (size_t) limits->max_http_response_bytes : 0;

    out->store = wasmtime_store_new(module->engine, NULL, NULL);
    if (out->store == NULL) {
        set_error(error_out, copy_cstr("failed to create Wasmtime store"));
        wasmtime_kmp_close(out);
        return NULL;
    }

    if (limits->max_memory_bytes > 0 || limits->max_table_elements > 0) {
        wasmtime_store_limiter(
            out->store,
            limits->max_memory_bytes > 0 ? limits->max_memory_bytes : -1,
            limits->max_table_elements > 0 ? limits->max_table_elements : -1,
            32,
            32,
            32
        );
    }

    wasmtime_context_t *context = wasmtime_store_context(out->store);
    wasmtime_error_t *error = NULL;
    uint64_t initial_fuel = limits->fuel > 0 ? limits->fuel : UINT64_MAX;
    error = wasmtime_context_set_fuel(context, initial_fuel);
    if (error != NULL) {
        set_error(error_out, error_message(error));
        wasmtime_kmp_close(out);
        return NULL;
    }
    reset_execution_deadline(out);

    if (storage != NULL) {
        if (storage->backing_path == NULL || storage->guest_path == NULL) {
            set_error(error_out, copy_cstr("storage paths must not be null"));
            wasmtime_kmp_close(out);
            return NULL;
        }
        if (!ensure_directory_tree(storage->backing_path)) {
            set_error(error_out, copy_cstr("failed to create sandbox storage directory"));
            wasmtime_kmp_close(out);
            return NULL;
        }
        if (module->cache_dir != NULL && paths_overlap(storage->backing_path, module->cache_dir)) {
            set_error(error_out, copy_cstr("sandbox storage must not overlap the compiled-module cache"));
            wasmtime_kmp_close(out);
            return NULL;
        }
    }

    if (module->has_imports || storage != NULL) {
        out->wasi = wasmtime_kmp_wasi_lite_new(storage, limits, error_out);
        if (out->wasi == NULL) {
            wasmtime_kmp_close(out);
            return NULL;
        }
    }

    if (!module->has_imports) {
        wasm_trap_t *trap = NULL;
        error = wasmtime_instance_new(
            context,
            module->module,
            NULL,
            0,
            &out->instance,
            &trap
        );
        if (error != NULL) {
            set_error(error_out, error_message(error));
            wasmtime_kmp_close(out);
            return NULL;
        }
        if (trap != NULL) {
            set_error(error_out, trap_message(trap));
            wasmtime_kmp_close(out);
            return NULL;
        }
        return out;
    }

    out->linker = wasmtime_linker_new(module->engine);
    if (out->linker == NULL) {
        set_error(error_out, copy_cstr("failed to create Wasmtime linker"));
        wasmtime_kmp_close(out);
        return NULL;
    }

    error = wasmtime_kmp_wasi_lite_define(out->linker, out->wasi);
    if (error != NULL) {
        set_error(error_out, error_message(error));
        wasmtime_kmp_close(out);
        return NULL;
    }

    error = define_http_imports(out->linker, out);
    if (error != NULL) {
        set_error(error_out, error_message(error));
        wasmtime_kmp_close(out);
        return NULL;
    }

    /*
     * Open-world Kotlin/Wasm runtime bundles may deliberately shadow host
     * modules with a memory-forwarding adapter after the <kotlin> module has
     * been instantiated. Ordinary monolithic loads never add linked modules,
     * so this does not change their name resolution.
     */
    if (runtime_module_count > 0) {
        wasmtime_linker_allow_shadowing(out->linker, true);
    }

    for (size_t index = 0; index < runtime_module_count; index++) {
        if (!instantiate_linked_module(
                module->engine,
                out,
                context,
                &runtime_modules[index],
                module->cache_dir,
                error_out
            )) {
            wasmtime_kmp_close(out);
            return NULL;
        }
    }

    wasm_trap_t *trap = NULL;
    if (out->has_async_http) {
        wasmtime_call_future_t *future = wasmtime_linker_instantiate_async(
            out->linker, context, module->module, &out->instance, &trap, &error
        );
        if (future == NULL) {
            set_error(error_out, copy_cstr("failed to create async Wasmtime instantiate future"));
            wasmtime_kmp_close(out);
            return NULL;
        }
        while (!wasmtime_call_future_poll(future)) {
            sched_yield();
        }
        wasmtime_call_future_delete(future);
    } else {
        error = wasmtime_linker_instantiate(
            out->linker, context, module->module, &out->instance, &trap
        );
    }
    if (error != NULL) {
        set_error(error_out, error_message(error));
        wasmtime_kmp_close(out);
        return NULL;
    }
    if (trap != NULL) {
        set_error(error_out, trap_message(trap));
        wasmtime_kmp_close(out);
        return NULL;
    }

    return out;
}

wasmtime_kmp_instance_t *wasmtime_kmp_instantiate_with_capabilities(
    wasmtime_kmp_module_t *module,
    const wasmtime_kmp_limits_t *limits,
    const wasmtime_kmp_http_handler_t *http_handler,
    const wasmtime_kmp_storage_t *storage,
    char **error_out
) {
    return wasmtime_kmp_instantiate_with_runtime(
        module,
        NULL,
        0,
        limits,
        http_handler,
        storage,
        error_out
    );
}

wasmtime_kmp_instance_t *wasmtime_kmp_instantiate_with_http(
    wasmtime_kmp_module_t *module,
    const wasmtime_kmp_limits_t *limits,
    const wasmtime_kmp_http_handler_t *http_handler,
    char **error_out
) {
    return wasmtime_kmp_instantiate_with_capabilities(
        module, limits, http_handler, NULL, error_out
    );
}

wasmtime_kmp_instance_t *wasmtime_kmp_instantiate(
    wasmtime_kmp_module_t *module,
    const wasmtime_kmp_limits_t *limits,
    char **error_out
) {
    return wasmtime_kmp_instantiate_with_capabilities(module, limits, NULL, NULL, error_out);
}

wasmtime_kmp_instance_t *wasmtime_kmp_load_with_capabilities(
    const uint8_t *wasm,
    size_t wasm_len,
    const wasmtime_kmp_limits_t *limits,
    const wasmtime_kmp_http_handler_t *http_handler,
    const wasmtime_kmp_storage_t *storage,
    char **error_out
) {
    char *compile_error = NULL;
    int strict_epoch = limits != NULL && limits->max_execution_millis > 0 && limits->fuel <= 0;
    wasmtime_kmp_module_t *module = wasmtime_kmp_compile_internal(
        wasm, wasm_len, strict_epoch, &compile_error
    );
    if (module == NULL) {
        http_handler_dispose_value(http_handler);
        set_error(error_out, compile_error);
        return NULL;
    }

    char *instantiate_error = NULL;
    wasmtime_kmp_instance_t *instance = wasmtime_kmp_instantiate_with_capabilities(
        module, limits, http_handler, storage, &instantiate_error
    );
    if (instance == NULL) {
        wasmtime_kmp_module_close(module);
        set_error(error_out, instantiate_error);
        return NULL;
    }
    wasmtime_kmp_module_close(module);
    return instance;
}

wasmtime_kmp_instance_t *wasmtime_kmp_load_with_runtime(
    const uint8_t *wasm,
    size_t wasm_len,
    const wasmtime_kmp_linked_module_t *runtime_modules,
    size_t runtime_module_count,
    const wasmtime_kmp_limits_t *limits,
    const wasmtime_kmp_http_handler_t *http_handler,
    const wasmtime_kmp_storage_t *storage,
    char **error_out
) {
    char *compile_error = NULL;
    int strict_epoch = limits != NULL && limits->max_execution_millis > 0 && limits->fuel <= 0;
    wasmtime_kmp_module_t *module = wasmtime_kmp_compile_internal(
        wasm, wasm_len, strict_epoch, &compile_error
    );
    if (module == NULL) {
        http_handler_dispose_value(http_handler);
        set_error(error_out, compile_error);
        return NULL;
    }

    char *instantiate_error = NULL;
    wasmtime_kmp_instance_t *instance = wasmtime_kmp_instantiate_with_runtime(
        module,
        runtime_modules,
        runtime_module_count,
        limits,
        http_handler,
        storage,
        &instantiate_error
    );
    if (instance == NULL) {
        wasmtime_kmp_module_close(module);
        set_error(error_out, instantiate_error);
        return NULL;
    }
    wasmtime_kmp_module_close(module);
    return instance;
}

wasmtime_kmp_instance_t *wasmtime_kmp_load_with_http(
    const uint8_t *wasm,
    size_t wasm_len,
    const wasmtime_kmp_limits_t *limits,
    const wasmtime_kmp_http_handler_t *http_handler,
    char **error_out
) {
    return wasmtime_kmp_load_with_capabilities(
        wasm, wasm_len, limits, http_handler, NULL, error_out
    );
}

wasmtime_kmp_instance_t *wasmtime_kmp_load(
    const uint8_t *wasm,
    size_t wasm_len,
    const wasmtime_kmp_limits_t *limits,
    char **error_out
) {
    return wasmtime_kmp_load_with_capabilities(wasm, wasm_len, limits, NULL, NULL, error_out);
}

int wasmtime_kmp_call_i32_2(
    wasmtime_kmp_instance_t *instance,
    const char *export_name,
    int32_t first,
    int32_t second,
    int32_t *result_out,
    char **error_out
) {
    if (error_out != NULL) *error_out = NULL;
    wasmtime_kmp_func_i32_2_t *function = wasmtime_kmp_resolve_i32_2(
        instance, export_name, error_out
    );
    if (function == NULL) return 0;
    int ok = wasmtime_kmp_func_i32_2_call(
        function, first, second, result_out, error_out
    );
    wasmtime_kmp_func_i32_2_close(function);
    return ok;
}

wasmtime_kmp_func_i32_2_t *wasmtime_kmp_resolve_i32_2(
    wasmtime_kmp_instance_t *instance,
    const char *export_name,
    char **error_out
) {
    if (error_out != NULL) *error_out = NULL;
    if (instance == NULL || export_name == NULL) {
        set_error(error_out, copy_cstr("invalid resolve arguments"));
        return NULL;
    }
    if (!wasmtime_kmp_instance_enter(instance, error_out)) return NULL;

    wasmtime_context_t *context = wasmtime_store_context(instance->store);
    wasmtime_extern_t item;
    if (!wasmtime_instance_export_get(
            context,
            &instance->instance,
            export_name,
            strlen(export_name),
            &item
        )) {
        wasmtime_kmp_instance_leave(instance);
        set_error(error_out, copy_cstr("export not found"));
        return NULL;
    }
    if (item.kind != WASMTIME_EXTERN_FUNC) {
        wasmtime_extern_delete(&item);
        wasmtime_kmp_instance_leave(instance);
        set_error(error_out, copy_cstr("export is not a function"));
        return NULL;
    }

    wasm_functype_t *type = wasmtime_func_type(context, &item.of.func);
    if (type == NULL) {
        wasmtime_extern_delete(&item);
        wasmtime_kmp_instance_leave(instance);
        set_error(error_out, copy_cstr("failed to inspect function type"));
        return NULL;
    }
    const wasm_valtype_vec_t *params = wasm_functype_params(type);
    const wasm_valtype_vec_t *results = wasm_functype_results(type);
    int valid = params->size == 2
        && results->size == 1
        && wasm_valtype_kind(params->data[0]) == WASM_I32
        && wasm_valtype_kind(params->data[1]) == WASM_I32
        && wasm_valtype_kind(results->data[0]) == WASM_I32;
    wasm_functype_delete(type);
    if (!valid) {
        wasmtime_extern_delete(&item);
        wasmtime_kmp_instance_leave(instance);
        set_error(error_out, copy_cstr("function must have signature (i32, i32) -> i32"));
        return NULL;
    }

    wasmtime_kmp_func_i32_2_t *out = malloc(sizeof(wasmtime_kmp_func_i32_2_t));
    if (out == NULL) {
        wasmtime_extern_delete(&item);
        wasmtime_kmp_instance_leave(instance);
        set_error(error_out, copy_cstr("out of memory"));
        return NULL;
    }
    if (!wasmtime_kmp_instance_try_retain(instance)) {
        free(out);
        wasmtime_extern_delete(&item);
        wasmtime_kmp_instance_leave(instance);
        set_error(error_out, copy_cstr("Wasmtime instance is closed"));
        return NULL;
    }
    atomic_init(&out->refs, 1);
    atomic_init(&out->closed, 0);
    out->instance = instance;
    out->function = item.of.func;
    wasmtime_extern_delete(&item);
    wasmtime_kmp_instance_leave(instance);
    return out;
}

static int wasmtime_kmp_func_i32_2_call_locked(
    wasmtime_kmp_func_i32_2_t *function,
    int32_t first,
    int32_t second,
    int32_t *result_out,
    char **error_out
) {
    reset_execution_deadline(function->instance);
    wasmtime_val_raw_t values[2] = {0};
    values[0].i32 = first;
    values[1].i32 = second;
    wasm_trap_t *trap = NULL;
    wasmtime_error_t *error = wasmtime_func_call_unchecked(
        wasmtime_store_context(function->instance->store),
        &function->function,
        values,
        2,
        &trap
    );
    if (error != NULL) {
        set_error(error_out, error_message(error));
        return 0;
    }
    if (trap != NULL) {
        set_error(error_out, trap_message(trap));
        return 0;
    }
    *result_out = values[0].i32;
    return 1;
}

wasmtime_kmp_func_i32_2_future_t *wasmtime_kmp_func_i32_2_call_async_start(
    wasmtime_kmp_func_i32_2_t *function,
    int32_t first,
    int32_t second,
    char **error_out
) {
    if (error_out != NULL) *error_out = NULL;
    if (function == NULL || !wasmtime_kmp_function_try_retain(function)) {
        set_error(error_out, copy_cstr("Wasmtime function is closed"));
        return NULL;
    }
    wasmtime_kmp_instance_t *instance = function->instance;
    if (!wasmtime_kmp_instance_enter_borrowed(instance, error_out)) {
        wasmtime_kmp_function_release(function);
        return NULL;
    }

    wasmtime_kmp_func_i32_2_future_t *out = calloc(1, sizeof(*out));
    if (out == NULL) {
        wasmtime_kmp_instance_leave_borrowed(instance);
        wasmtime_kmp_function_release(function);
        set_error(error_out, copy_cstr("out of memory"));
        return NULL;
    }
    out->function = function;
    out->instance = instance;
    reset_execution_deadline(instance);
    out->deadline_ns = execution_deadline_ns(instance->max_execution_millis);

    if (!instance->has_async_http) {
        int32_t value = 0;
        if (!wasmtime_kmp_func_i32_2_call_locked(function, first, second, &value, error_out)) {
            wasmtime_kmp_instance_leave_borrowed(instance);
            wasmtime_kmp_function_release(function);
            free(out);
            return NULL;
        }
        out->result.kind = WASMTIME_I32;
        out->result.of.i32 = value;
        out->completed = 1;
        return out;
    }

    out->args[0].kind = WASMTIME_I32;
    out->args[0].of.i32 = first;
    out->args[1].kind = WASMTIME_I32;
    out->args[1].of.i32 = second;
    out->future = wasmtime_func_call_async(
        wasmtime_store_context(instance->store),
        &function->function,
        out->args,
        2,
        &out->result,
        1,
        &out->trap,
        &out->error
    );
    if (out->future == NULL) {
        wasmtime_kmp_instance_leave_borrowed(instance);
        wasmtime_kmp_function_release(function);
        free(out);
        set_error(error_out, copy_cstr("failed to create async Wasmtime call future"));
        return NULL;
    }
    return out;
}

int wasmtime_kmp_func_i32_2_call_async_poll(
    wasmtime_kmp_func_i32_2_future_t *future,
    int32_t *result_out,
    char **error_out
) {
    if (error_out != NULL) *error_out = NULL;
    if (future == NULL || result_out == NULL) {
        set_error(error_out, copy_cstr("invalid async call poll arguments"));
        return -1;
    }

    if (!future->completed) {
        if (execution_deadline_expired(future->deadline_ns)) {
            if (future->future != NULL) {
                wasmtime_call_future_delete(future->future);
                future->future = NULL;
            }
            future->completed = 1;
            set_error(error_out, copy_cstr("Wasmtime execution deadline exceeded"));
            return -1;
        }
        if (future->future == NULL || !wasmtime_call_future_poll(future->future)) {
            return 0;
        }
        future->completed = 1;
        wasmtime_call_future_delete(future->future);
        future->future = NULL;
    }

    if (future->error != NULL) {
        set_error(error_out, error_message(future->error));
        future->error = NULL;
        return -1;
    }
    if (future->trap != NULL) {
        set_error(error_out, trap_message(future->trap));
        future->trap = NULL;
        return -1;
    }
    if (future->result.kind != WASMTIME_I32) {
        set_error(error_out, copy_cstr("function did not return i32"));
        return -1;
    }

    *result_out = future->result.of.i32;
    return 1;
}

void wasmtime_kmp_func_i32_2_call_async_close(
    wasmtime_kmp_func_i32_2_future_t *future
) {
    if (future == NULL) return;
    if (future->future != NULL) {
        wasmtime_call_future_delete(future->future);
        future->future = NULL;
    }
    if (future->error != NULL) wasmtime_error_delete(future->error);
    if (future->trap != NULL) wasm_trap_delete(future->trap);
    if (future->completed && future->result.kind != 0) {
        wasmtime_val_unroot(&future->result);
    }
    if (future->instance != NULL) wasmtime_kmp_instance_leave_borrowed(future->instance);
    if (future->function != NULL) wasmtime_kmp_function_release(future->function);
    free(future);
}

int wasmtime_kmp_func_i32_2_call_managed(
    wasmtime_kmp_func_i32_2_t *function,
    int32_t first,
    int32_t second,
    int32_t *result_out,
    char **error_out
) {
    if (error_out != NULL) *error_out = NULL;
    if (function == NULL || result_out == NULL
        || atomic_load_explicit(&function->closed, memory_order_acquire)) {
        set_error(error_out, copy_cstr("Wasmtime function is closed"));
        return 0;
    }
    wasmtime_kmp_instance_t *instance = function->instance;
    if (!wasmtime_kmp_instance_enter_borrowed(instance, error_out)) return 0;
    int ok = wasmtime_kmp_func_i32_2_call_locked(function, first, second, result_out, error_out);
    wasmtime_kmp_instance_leave_borrowed(instance);
    return ok;
}

int wasmtime_kmp_func_i32_2_call(
    wasmtime_kmp_func_i32_2_t *function,
    int32_t first,
    int32_t second,
    int32_t *result_out,
    char **error_out
) {
    if (error_out != NULL) *error_out = NULL;
    if (function == NULL || result_out == NULL || !wasmtime_kmp_function_try_retain(function)) {
        set_error(error_out, copy_cstr("Wasmtime function is closed"));
        return 0;
    }
    wasmtime_kmp_instance_t *instance = function->instance;

    if (instance->has_async_http) {
        wasmtime_kmp_func_i32_2_future_t *future =
            wasmtime_kmp_func_i32_2_call_async_start(function, first, second, error_out);
        wasmtime_kmp_function_release(function);
        if (future == NULL) return 0;
        int status;
        do {
            status = wasmtime_kmp_func_i32_2_call_async_poll(future, result_out, error_out);
            if (status == 0) sched_yield();
        } while (status == 0);
        wasmtime_kmp_func_i32_2_call_async_close(future);
        return status == 1;
    }

    if (!wasmtime_kmp_instance_enter_borrowed(instance, error_out)) {
        wasmtime_kmp_function_release(function);
        return 0;
    }
    int ok = wasmtime_kmp_func_i32_2_call_locked(function, first, second, result_out, error_out);
    wasmtime_kmp_instance_leave_borrowed(instance);
    wasmtime_kmp_function_release(function);
    return ok;
}

static void wasmtime_kmp_function_release(wasmtime_kmp_func_i32_2_t *function) {
    if (function == NULL) return;
    if (atomic_fetch_sub_explicit(&function->refs, 1, memory_order_acq_rel) != 1) return;
    if (function->instance != NULL) wasmtime_kmp_instance_release(function->instance);
    free(function);
}

void wasmtime_kmp_func_i32_2_close(wasmtime_kmp_func_i32_2_t *function) {
    if (function == NULL) return;
    int expected = 0;
    if (atomic_compare_exchange_strong_explicit(
            &function->closed, &expected, 1,
            memory_order_acq_rel, memory_order_acquire
        )) {
        wasmtime_kmp_function_release(function);
    }
}

static void wasmtime_kmp_instance_destroy(wasmtime_kmp_instance_t *instance) {
    http_request_reset(instance);
    if (instance->http_handler.dispose != NULL) {
        instance->http_handler.dispose(instance->http_handler.user_data);
        instance->http_handler.dispose = NULL;
        instance->http_handler.user_data = NULL;
    }
    if (instance->linker != NULL) wasmtime_linker_delete(instance->linker);
    if (instance->wasi != NULL) wasmtime_kmp_wasi_lite_delete(instance->wasi);
    if (instance->store != NULL) wasmtime_store_delete(instance->store);
    if (instance->module_owner != NULL) {
        if (instance->max_execution_millis > 0
            && instance->module_owner->epoch_interruption_enabled) {
            wasmtime_kmp_module_disable_ticker(instance->module_owner);
        }
        wasmtime_kmp_module_release(instance->module_owner);
    }
    free(instance);
}

static void wasmtime_kmp_instance_release(wasmtime_kmp_instance_t *instance) {
    if (instance == NULL) return;
    if (atomic_fetch_sub_explicit(&instance->refs, 1, memory_order_acq_rel) == 1) {
        wasmtime_kmp_instance_destroy(instance);
    }
}

void wasmtime_kmp_close(wasmtime_kmp_instance_t *instance) {
    if (instance == NULL) return;
    int expected = 0;
    if (atomic_compare_exchange_strong_explicit(
            &instance->closing, &expected, 1,
            memory_order_acq_rel, memory_order_acquire
        )) {
        wasmtime_kmp_instance_release(instance);
    }
}

static void wasmtime_kmp_module_destroy(wasmtime_kmp_module_t *module) {
    if (module->epoch_sync_initialized) {
        pthread_mutex_lock(&module->epoch_mutex);
        module->epoch_stop = 1;
        pthread_cond_broadcast(&module->epoch_cond);
        pthread_mutex_unlock(&module->epoch_mutex);
        if (module->epoch_thread_started) pthread_join(module->epoch_thread, NULL);
        pthread_cond_destroy(&module->epoch_cond);
        pthread_mutex_destroy(&module->epoch_mutex);
    }
    if (module->module != NULL) wasmtime_module_delete(module->module);
    if (module->engine != NULL) wasm_engine_delete(module->engine);
    free(module->cache_dir);
    free(module);
}

static void wasmtime_kmp_module_release(wasmtime_kmp_module_t *module) {
    if (module == NULL) return;
    if (atomic_fetch_sub_explicit(&module->refs, 1, memory_order_acq_rel) == 1) {
        wasmtime_kmp_module_destroy(module);
    }
}

void wasmtime_kmp_module_close(wasmtime_kmp_module_t *module) {
    if (module == NULL) return;
    int expected = 0;
    if (atomic_compare_exchange_strong_explicit(
            &module->closing, &expected, 1,
            memory_order_acq_rel, memory_order_acquire
        )) {
        wasmtime_kmp_module_release(module);
    }
}

void wasmtime_kmp_string_free(char *string) {
    free(string);
}

#if defined(__ANDROID__) || defined(WASMTIME_KMP_JNI)
typedef struct wasmtime_kmp_jni_http_state {
    JavaVM *vm;
    jobject bridge;
    atomic_int refs;
} wasmtime_kmp_jni_http_state_t;

static int jni_get_env(JavaVM *vm, JNIEnv **env_out, int *attached_out) {
    *attached_out = 0;
    if ((*vm)->GetEnv(vm, (void **) env_out, JNI_VERSION_1_6) == JNI_OK) return 1;
#ifdef __ANDROID__
    if ((*vm)->AttachCurrentThread(vm, env_out, NULL) != JNI_OK) return 0;
#else
    if ((*vm)->AttachCurrentThread(vm, (void **) env_out, NULL) != JNI_OK) return 0;
#endif
    *attached_out = 1;
    return 1;
}

typedef struct wasmtime_kmp_jni_http_operation {
    wasmtime_kmp_jni_http_state_t *state;
    uint8_t *request_metadata;
    size_t request_metadata_len;
    uint8_t *request_body;
    size_t request_body_len;
    uint8_t *response_metadata;
    size_t response_metadata_len;
    uint8_t *response_body;
    size_t response_body_len;
    atomic_int status;
    atomic_int cancelled;
    atomic_int refs;
} wasmtime_kmp_jni_http_operation_t;

static void jni_http_state_release(wasmtime_kmp_jni_http_state_t *state);

static void jni_http_operation_release(wasmtime_kmp_jni_http_operation_t *operation) {
    if (operation == NULL) return;
    if (atomic_fetch_sub_explicit(&operation->refs, 1, memory_order_acq_rel) != 1) return;
    free(operation->request_metadata);
    free(operation->request_body);
    free(operation->response_metadata);
    free(operation->response_body);
    free(operation);
}

static int jni_copy_response(
    JNIEnv *env,
    jobject response,
    wasmtime_kmp_jni_http_operation_t *operation
) {
    jclass response_class = NULL;
    jbyteArray metadata_array = NULL;
    jbyteArray body_array = NULL;
    uint8_t *metadata_copy = NULL;
    uint8_t *body_copy = NULL;
    int ok = 0;

    response_class = (*env)->GetObjectClass(env, response);
    if (response_class == NULL) goto done;
    jmethodID get_metadata = (*env)->GetMethodID(env, response_class, "getMetadata", "()[B");
    jmethodID get_body = (*env)->GetMethodID(env, response_class, "getBody", "()[B");
    if (get_metadata == NULL || get_body == NULL) goto done;

    metadata_array = (jbyteArray) (*env)->CallObjectMethod(env, response, get_metadata);
    body_array = (jbyteArray) (*env)->CallObjectMethod(env, response, get_body);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        goto done;
    }
    if (metadata_array == NULL || body_array == NULL) goto done;

    jsize metadata_len = (*env)->GetArrayLength(env, metadata_array);
    jsize body_len = (*env)->GetArrayLength(env, body_array);
    if (metadata_len <= 0) goto done;

    metadata_copy = malloc((size_t) metadata_len);
    if (metadata_copy == NULL) goto done;
    (*env)->GetByteArrayRegion(env, metadata_array, 0, metadata_len, (jbyte *) metadata_copy);
    if (body_len > 0) {
        body_copy = malloc((size_t) body_len);
        if (body_copy == NULL) goto done;
        (*env)->GetByteArrayRegion(env, body_array, 0, body_len, (jbyte *) body_copy);
    }
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        goto done;
    }

    operation->response_metadata = metadata_copy;
    operation->response_metadata_len = (size_t) metadata_len;
    operation->response_body = body_copy;
    operation->response_body_len = (size_t) body_len;
    metadata_copy = NULL;
    body_copy = NULL;
    ok = 1;

done:
    free(metadata_copy);
    free(body_copy);
    if (body_array != NULL) (*env)->DeleteLocalRef(env, body_array);
    if (metadata_array != NULL) (*env)->DeleteLocalRef(env, metadata_array);
    if (response_class != NULL) (*env)->DeleteLocalRef(env, response_class);
    return ok;
}

static void *jni_http_worker(void *data) {
    wasmtime_kmp_jni_http_operation_t *operation = data;
    wasmtime_kmp_jni_http_state_t *state = operation->state;
    JNIEnv *env = NULL;
    int attached = 0;
    jobject java_operation = NULL;
    jobject java_operation_global = NULL;
    jclass bridge_class = NULL;
    jclass operation_class = NULL;
    jbyteArray metadata_array = NULL;
    jbyteArray body_array = NULL;
    int status = -1;

    if (!jni_get_env(state->vm, &env, &attached)) goto done;
    bridge_class = (*env)->GetObjectClass(env, state->bridge);
    if (bridge_class == NULL) goto done;
    jmethodID start = (*env)->GetMethodID(
        env,
        bridge_class,
        "start",
        "([B[B)Ldev/brahmkshatriya/wasmtime/internal/JniHttpOperation;"
    );
    if (start == NULL) goto done;

    metadata_array = (*env)->NewByteArray(env, (jsize) operation->request_metadata_len);
    body_array = (*env)->NewByteArray(env, (jsize) operation->request_body_len);
    if (metadata_array == NULL || body_array == NULL) goto done;
    if (operation->request_metadata_len > 0) {
        (*env)->SetByteArrayRegion(
            env, metadata_array, 0, (jsize) operation->request_metadata_len,
            (const jbyte *) operation->request_metadata
        );
    }
    if (operation->request_body_len > 0) {
        (*env)->SetByteArrayRegion(
            env, body_array, 0, (jsize) operation->request_body_len,
            (const jbyte *) operation->request_body
        );
    }
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        goto done;
    }

    java_operation = (*env)->CallObjectMethod(env, state->bridge, start, metadata_array, body_array);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        goto done;
    }
    if (java_operation == NULL) goto done;
    java_operation_global = (*env)->NewGlobalRef(env, java_operation);
    if (java_operation_global == NULL) goto done;

    operation_class = (*env)->GetObjectClass(env, java_operation_global);
    if (operation_class == NULL) goto done;
    jmethodID is_completed = (*env)->GetMethodID(env, operation_class, "isCompleted", "()Z");
    jmethodID get_response = (*env)->GetMethodID(
        env,
        operation_class,
        "getResponse",
        "()Ldev/brahmkshatriya/wasmtime/internal/JniHttpResponse;"
    );
    jmethodID cancel = (*env)->GetMethodID(env, operation_class, "cancel", "()V");
    if (is_completed == NULL || get_response == NULL || cancel == NULL) goto done;

    for (;;) {
        if (atomic_load_explicit(&operation->cancelled, memory_order_acquire)) {
            (*env)->CallVoidMethod(env, java_operation_global, cancel);
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
            goto done;
        }
        jboolean completed = (*env)->CallBooleanMethod(env, java_operation_global, is_completed);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            goto done;
        }
        if (completed) break;
        poll(NULL, 0, 1);
    }

    jobject response = (*env)->CallObjectMethod(env, java_operation_global, get_response);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        if (response != NULL) (*env)->DeleteLocalRef(env, response);
        goto done;
    }
    if (response != NULL) {
        status = jni_copy_response(env, response, operation) ? 1 : -1;
        (*env)->DeleteLocalRef(env, response);
    }

done:
    if (java_operation_global != NULL) (*env)->DeleteGlobalRef(env, java_operation_global);
    if (java_operation != NULL) (*env)->DeleteLocalRef(env, java_operation);
    if (body_array != NULL) (*env)->DeleteLocalRef(env, body_array);
    if (metadata_array != NULL) (*env)->DeleteLocalRef(env, metadata_array);
    if (operation_class != NULL) (*env)->DeleteLocalRef(env, operation_class);
    if (bridge_class != NULL) (*env)->DeleteLocalRef(env, bridge_class);
    if (env != NULL && attached) (*state->vm)->DetachCurrentThread(state->vm);
    atomic_store_explicit(&operation->status, status, memory_order_release);
    jni_http_state_release(state);
    jni_http_operation_release(operation);
    return NULL;
}

static void *jni_http_execute_start(
    void *user_data,
    const uint8_t *request_metadata,
    size_t request_metadata_len,
    const uint8_t *request_body,
    size_t request_body_len
) {
    wasmtime_kmp_jni_http_state_t *state = user_data;
    if (state == NULL || state->bridge == NULL) return NULL;

    wasmtime_kmp_jni_http_operation_t *operation = calloc(1, sizeof(*operation));
    if (operation == NULL) return NULL;
    operation->state = state;
    atomic_init(&operation->status, 0);
    atomic_init(&operation->cancelled, 0);
    atomic_init(&operation->refs, 2);

    if (request_metadata_len > 0) {
        operation->request_metadata = malloc(request_metadata_len);
        if (operation->request_metadata == NULL) goto fail;
        memcpy(operation->request_metadata, request_metadata, request_metadata_len);
        operation->request_metadata_len = request_metadata_len;
    }
    if (request_body_len > 0) {
        operation->request_body = malloc(request_body_len);
        if (operation->request_body == NULL) goto fail;
        memcpy(operation->request_body, request_body, request_body_len);
        operation->request_body_len = request_body_len;
    }

    atomic_fetch_add_explicit(&state->refs, 1, memory_order_relaxed);
    pthread_t worker;
    if (pthread_create(&worker, NULL, jni_http_worker, operation) != 0) {
        jni_http_state_release(state);
        goto fail;
    }
    pthread_detach(worker);
    return operation;

fail:
    atomic_store_explicit(&operation->status, -1, memory_order_release);
    /* No worker was created, so release its reference before returning. */
    jni_http_operation_release(operation);
    return operation;
}

static int jni_http_execute_poll(
    void *user_data,
    void *operation_ptr,
    uint8_t **response_metadata_out,
    size_t *response_metadata_len_out,
    uint8_t **response_body_out,
    size_t *response_body_len_out
) {
    (void) user_data;
    wasmtime_kmp_jni_http_operation_t *operation = operation_ptr;
    if (operation == NULL) return -1;
    int status = atomic_load_explicit(&operation->status, memory_order_acquire);
    if (status <= 0) return status;

    *response_metadata_out = operation->response_metadata;
    *response_metadata_len_out = operation->response_metadata_len;
    *response_body_out = operation->response_body;
    *response_body_len_out = operation->response_body_len;
    operation->response_metadata = NULL;
    operation->response_metadata_len = 0;
    operation->response_body = NULL;
    operation->response_body_len = 0;
    return 1;
}

static void jni_http_execute_dispose(void *user_data, void *operation_ptr) {
    (void) user_data;
    wasmtime_kmp_jni_http_operation_t *operation = operation_ptr;
    if (operation == NULL) return;
    atomic_store_explicit(&operation->cancelled, 1, memory_order_release);
    jni_http_operation_release(operation);
}

static void jni_http_free_buffer(void *user_data, uint8_t *buffer, size_t buffer_len) {
    (void) user_data;
    (void) buffer_len;
    free(buffer);
}

static void jni_http_state_release(wasmtime_kmp_jni_http_state_t *state) {
    if (state == NULL) return;
    if (atomic_fetch_sub_explicit(&state->refs, 1, memory_order_acq_rel) != 1) return;
    JNIEnv *env = NULL;
    int attached = 0;
    if (jni_get_env(state->vm, &env, &attached)) {
        if (state->bridge != NULL) {
            jclass bridge_class = (*env)->GetObjectClass(env, state->bridge);
            if (bridge_class != NULL) {
                jmethodID close = (*env)->GetMethodID(env, bridge_class, "close", "()V");
                if (close != NULL) {
                    (*env)->CallVoidMethod(env, state->bridge, close);
                    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                }
                (*env)->DeleteLocalRef(env, bridge_class);
            }
            (*env)->DeleteGlobalRef(env, state->bridge);
        }
        if (attached) (*state->vm)->DetachCurrentThread(state->vm);
    }
    free(state);
}

static void jni_http_dispose(void *user_data) {
    jni_http_state_release((wasmtime_kmp_jni_http_state_t *) user_data);
}

static int jni_http_handler_create(
    JNIEnv *env,
    jobject bridge,
    wasmtime_kmp_http_handler_t *handler_out
) {
    memset(handler_out, 0, sizeof(*handler_out));
    if (bridge == NULL) return 1;

    wasmtime_kmp_jni_http_state_t *state = calloc(1, sizeof(*state));
    if (state == NULL) return 0;
    if ((*env)->GetJavaVM(env, &state->vm) != JNI_OK) {
        free(state);
        return 0;
    }
    state->bridge = (*env)->NewGlobalRef(env, bridge);
    atomic_init(&state->refs, 1);
    if (state->bridge == NULL) {
        free(state);
        return 0;
    }

    handler_out->user_data = state;
    handler_out->execute_start = jni_http_execute_start;
    handler_out->execute_poll = jni_http_execute_poll;
    handler_out->execute_dispose = jni_http_execute_dispose;
    handler_out->free_buffer = jni_http_free_buffer;
    handler_out->dispose = jni_http_dispose;
    return 1;
}

static void throw_illegal_state(JNIEnv *env, const char *message) {
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (cls != NULL) {
        (*env)->ThrowNew(env, cls, message == NULL ? "unknown Wasmtime error" : message);
    }
}

typedef struct wasmtime_kmp_jni_runtime_modules {
    jsize count;
    wasmtime_kmp_linked_module_t *modules;
    jstring *names;
    const char **name_chars;
    jbyteArray *wasm_arrays;
    jbyte **wasm_bytes;
} wasmtime_kmp_jni_runtime_modules_t;

static void jni_runtime_modules_dispose(
    JNIEnv *env,
    wasmtime_kmp_jni_runtime_modules_t *runtime
) {
    if (runtime == NULL) return;
    for (jsize index = 0; index < runtime->count; index++) {
        if (runtime->wasm_bytes != NULL && runtime->wasm_bytes[index] != NULL) {
            (*env)->ReleaseByteArrayElements(
                env,
                runtime->wasm_arrays[index],
                runtime->wasm_bytes[index],
                JNI_ABORT
            );
        }
        if (runtime->name_chars != NULL && runtime->name_chars[index] != NULL) {
            (*env)->ReleaseStringUTFChars(
                env,
                runtime->names[index],
                runtime->name_chars[index]
            );
        }
        if (runtime->wasm_arrays != NULL && runtime->wasm_arrays[index] != NULL) {
            (*env)->DeleteLocalRef(env, runtime->wasm_arrays[index]);
        }
        if (runtime->names != NULL && runtime->names[index] != NULL) {
            (*env)->DeleteLocalRef(env, runtime->names[index]);
        }
    }
    free(runtime->modules);
    free(runtime->names);
    free(runtime->name_chars);
    free(runtime->wasm_arrays);
    free(runtime->wasm_bytes);
    memset(runtime, 0, sizeof(*runtime));
}

static int jni_runtime_modules_create(
    JNIEnv *env,
    jobjectArray names,
    jobjectArray wasm_arrays,
    wasmtime_kmp_jni_runtime_modules_t *runtime
) {
    memset(runtime, 0, sizeof(*runtime));
    if (names == NULL || wasm_arrays == NULL) return 0;
    jsize count = (*env)->GetArrayLength(env, names);
    if ((*env)->GetArrayLength(env, wasm_arrays) != count) return 0;
    runtime->count = count;
    if (count == 0) return 1;

    runtime->modules = calloc((size_t) count, sizeof(*runtime->modules));
    runtime->names = calloc((size_t) count, sizeof(*runtime->names));
    runtime->name_chars = calloc((size_t) count, sizeof(*runtime->name_chars));
    runtime->wasm_arrays = calloc((size_t) count, sizeof(*runtime->wasm_arrays));
    runtime->wasm_bytes = calloc((size_t) count, sizeof(*runtime->wasm_bytes));
    if (runtime->modules == NULL || runtime->names == NULL || runtime->name_chars == NULL
        || runtime->wasm_arrays == NULL || runtime->wasm_bytes == NULL) {
        jni_runtime_modules_dispose(env, runtime);
        return 0;
    }

    for (jsize index = 0; index < count; index++) {
        runtime->names[index] = (jstring) (*env)->GetObjectArrayElement(env, names, index);
        runtime->wasm_arrays[index] =
            (jbyteArray) (*env)->GetObjectArrayElement(env, wasm_arrays, index);
        if (runtime->names[index] == NULL || runtime->wasm_arrays[index] == NULL) {
            jni_runtime_modules_dispose(env, runtime);
            return 0;
        }
        runtime->name_chars[index] =
            (*env)->GetStringUTFChars(env, runtime->names[index], NULL);
        runtime->wasm_bytes[index] =
            (*env)->GetByteArrayElements(env, runtime->wasm_arrays[index], NULL);
        if (runtime->name_chars[index] == NULL || runtime->wasm_bytes[index] == NULL) {
            jni_runtime_modules_dispose(env, runtime);
            return 0;
        }
        runtime->modules[index].name = runtime->name_chars[index];
        runtime->modules[index].wasm = (const uint8_t *) runtime->wasm_bytes[index];
        runtime->modules[index].wasm_len =
            (size_t) (*env)->GetArrayLength(env, runtime->wasm_arrays[index]);
    }
    return 1;
}

JNIEXPORT void JNICALL
Java_dev_brahmkshatriya_wasmtime_internal_NativeWasmtime_nativeSetDefaultCacheDirectory(
    JNIEnv *env,
    jclass clazz,
    jstring directory
) {
    (void) clazz;
    if (getenv("WASMTIME_KMP_CACHE_DIR") != NULL) return;
    if (directory == NULL) {
        set_platform_cache_root(NULL);
        return;
    }
    const char *path = (*env)->GetStringUTFChars(env, directory, NULL);
    if (path == NULL) return;
    set_platform_cache_root(path);
    (*env)->ReleaseStringUTFChars(env, directory, path);
}

JNIEXPORT jlong JNICALL
Java_dev_brahmkshatriya_wasmtime_internal_NativeWasmtime_nativeCompile(
    JNIEnv *env,
    jclass clazz,
    jbyteArray wasm
) {
    (void) clazz;
    jsize size = (*env)->GetArrayLength(env, wasm);
    jbyte *bytes = (*env)->GetByteArrayElements(env, wasm, NULL);
    if (bytes == NULL) return 0;

    char *error = NULL;
    wasmtime_kmp_module_t *module = wasmtime_kmp_compile(
        (const uint8_t *) bytes,
        (size_t) size,
        &error
    );
    (*env)->ReleaseByteArrayElements(env, wasm, bytes, JNI_ABORT);

    if (module == NULL) {
        throw_illegal_state(env, error);
        wasmtime_kmp_string_free(error);
        return 0;
    }
    return (jlong) (intptr_t) module;
}

JNIEXPORT jlong JNICALL
Java_dev_brahmkshatriya_wasmtime_internal_NativeWasmtime_nativeInstantiate(
    JNIEnv *env,
    jclass clazz,
    jlong module_handle,
    jlong max_memory_bytes,
    jlong fuel,
    jlong max_execution_millis,
    jlong max_table_elements,
    jint max_host_call_bytes,
    jlong max_output_bytes,
    jint max_http_response_bytes,
    jlong max_wasi_poll_millis,
    jobject http_handler,
    jstring storage_backing_path,
    jstring storage_guest_path,
    jboolean storage_read_only,
    jlong storage_max_bytes,
    jint storage_max_entries,
    jlong storage_max_file_bytes
) {
    (void) clazz;
    char *error = NULL;
    wasmtime_kmp_limits_t limits = {
        .max_memory_bytes = (int64_t) max_memory_bytes,
        .fuel = (uint64_t) fuel,
        .max_execution_millis = (int64_t) max_execution_millis,
        .max_table_elements = (int64_t) max_table_elements,
        .max_host_call_bytes = (int64_t) max_host_call_bytes,
        .max_output_bytes = (int64_t) max_output_bytes,
        .max_http_response_bytes = (int64_t) max_http_response_bytes,
        .max_wasi_poll_millis = (int64_t) max_wasi_poll_millis,
    };
    wasmtime_kmp_http_handler_t native_http_handler = {0};
    if (!jni_http_handler_create(env, http_handler, &native_http_handler)) {
        throw_illegal_state(env, "failed to create HTTP handler bridge");
        return 0;
    }
    const wasmtime_kmp_http_handler_t *http_handler_ptr =
        http_handler == NULL ? NULL : &native_http_handler;

    const char *storage_backing = storage_backing_path == NULL
        ? NULL : (*env)->GetStringUTFChars(env, storage_backing_path, NULL);
    const char *storage_guest = storage_guest_path == NULL
        ? NULL : (*env)->GetStringUTFChars(env, storage_guest_path, NULL);
    wasmtime_kmp_storage_t native_storage = {
        .backing_path = storage_backing,
        .guest_path = storage_guest,
        .read_only = storage_read_only == JNI_TRUE,
        .max_bytes = (int64_t) storage_max_bytes,
        .max_entries = (int64_t) storage_max_entries,
        .max_file_bytes = (int64_t) storage_max_file_bytes,
    };
    const wasmtime_kmp_storage_t *storage_ptr =
        storage_backing_path == NULL ? NULL : &native_storage;

    wasmtime_kmp_instance_t *instance = wasmtime_kmp_instantiate_with_capabilities(
        (wasmtime_kmp_module_t *) (intptr_t) module_handle,
        &limits,
        http_handler_ptr,
        storage_ptr,
        &error
    );
    if (storage_backing != NULL) {
        (*env)->ReleaseStringUTFChars(env, storage_backing_path, storage_backing);
    }
    if (storage_guest != NULL) {
        (*env)->ReleaseStringUTFChars(env, storage_guest_path, storage_guest);
    }
    if (instance == NULL) {
        throw_illegal_state(env, error);
        wasmtime_kmp_string_free(error);
        return 0;
    }
    return (jlong) (intptr_t) instance;
}

JNIEXPORT jlong JNICALL
Java_dev_brahmkshatriya_wasmtime_internal_NativeWasmtime_nativeInstantiateWithRuntime(
    JNIEnv *env,
    jclass clazz,
    jlong module_handle,
    jobjectArray runtime_module_names,
    jobjectArray runtime_modules,
    jlong max_memory_bytes,
    jlong fuel,
    jlong max_execution_millis,
    jlong max_table_elements,
    jint max_host_call_bytes,
    jlong max_output_bytes,
    jint max_http_response_bytes,
    jlong max_wasi_poll_millis,
    jobject http_handler,
    jstring storage_backing_path,
    jstring storage_guest_path,
    jboolean storage_read_only,
    jlong storage_max_bytes,
    jint storage_max_entries,
    jlong storage_max_file_bytes
) {
    (void) clazz;
    wasmtime_kmp_jni_runtime_modules_t native_runtime = {0};
    if (!jni_runtime_modules_create(
            env,
            runtime_module_names,
            runtime_modules,
            &native_runtime
        )) {
        throw_illegal_state(env, "failed to create runtime module bridge");
        return 0;
    }

    char *error = NULL;
    wasmtime_kmp_limits_t limits = {
        .max_memory_bytes = (int64_t) max_memory_bytes,
        .fuel = (uint64_t) fuel,
        .max_execution_millis = (int64_t) max_execution_millis,
        .max_table_elements = (int64_t) max_table_elements,
        .max_host_call_bytes = (int64_t) max_host_call_bytes,
        .max_output_bytes = (int64_t) max_output_bytes,
        .max_http_response_bytes = (int64_t) max_http_response_bytes,
        .max_wasi_poll_millis = (int64_t) max_wasi_poll_millis,
    };
    wasmtime_kmp_http_handler_t native_http_handler = {0};
    if (!jni_http_handler_create(env, http_handler, &native_http_handler)) {
        jni_runtime_modules_dispose(env, &native_runtime);
        throw_illegal_state(env, "failed to create HTTP handler bridge");
        return 0;
    }
    const wasmtime_kmp_http_handler_t *http_handler_ptr =
        http_handler == NULL ? NULL : &native_http_handler;

    const char *storage_backing = storage_backing_path == NULL
        ? NULL : (*env)->GetStringUTFChars(env, storage_backing_path, NULL);
    const char *storage_guest = storage_guest_path == NULL
        ? NULL : (*env)->GetStringUTFChars(env, storage_guest_path, NULL);
    wasmtime_kmp_storage_t native_storage = {
        .backing_path = storage_backing,
        .guest_path = storage_guest,
        .read_only = storage_read_only == JNI_TRUE,
        .max_bytes = (int64_t) storage_max_bytes,
        .max_entries = (int64_t) storage_max_entries,
        .max_file_bytes = (int64_t) storage_max_file_bytes,
    };
    const wasmtime_kmp_storage_t *storage_ptr =
        storage_backing_path == NULL ? NULL : &native_storage;

    wasmtime_kmp_instance_t *instance = wasmtime_kmp_instantiate_with_runtime(
        (wasmtime_kmp_module_t *) (intptr_t) module_handle,
        native_runtime.modules,
        (size_t) native_runtime.count,
        &limits,
        http_handler_ptr,
        storage_ptr,
        &error
    );
    if (storage_backing != NULL) {
        (*env)->ReleaseStringUTFChars(env, storage_backing_path, storage_backing);
    }
    if (storage_guest != NULL) {
        (*env)->ReleaseStringUTFChars(env, storage_guest_path, storage_guest);
    }
    jni_runtime_modules_dispose(env, &native_runtime);

    if (instance == NULL) {
        throw_illegal_state(env, error);
        wasmtime_kmp_string_free(error);
        return 0;
    }
    return (jlong) (intptr_t) instance;
}

JNIEXPORT void JNICALL
Java_dev_brahmkshatriya_wasmtime_internal_NativeWasmtime_nativeModuleClose(
    JNIEnv *env,
    jclass clazz,
    jlong module_handle
) {
    (void) env;
    (void) clazz;
    wasmtime_kmp_module_close((wasmtime_kmp_module_t *) (intptr_t) module_handle);
}

JNIEXPORT jlong JNICALL
Java_dev_brahmkshatriya_wasmtime_internal_NativeWasmtime_nativeLoad(
    JNIEnv *env,
    jclass clazz,
    jbyteArray wasm,
    jlong max_memory_bytes,
    jlong fuel,
    jlong max_execution_millis,
    jlong max_table_elements,
    jint max_host_call_bytes,
    jlong max_output_bytes,
    jint max_http_response_bytes,
    jlong max_wasi_poll_millis,
    jobject http_handler,
    jstring storage_backing_path,
    jstring storage_guest_path,
    jboolean storage_read_only,
    jlong storage_max_bytes,
    jint storage_max_entries,
    jlong storage_max_file_bytes
) {
    (void) clazz;
    jsize size = (*env)->GetArrayLength(env, wasm);
    jbyte *bytes = (*env)->GetByteArrayElements(env, wasm, NULL);
    if (bytes == NULL) return 0;

    char *error = NULL;
    wasmtime_kmp_limits_t limits = {
        .max_memory_bytes = (int64_t) max_memory_bytes,
        .fuel = (uint64_t) fuel,
        .max_execution_millis = (int64_t) max_execution_millis,
        .max_table_elements = (int64_t) max_table_elements,
        .max_host_call_bytes = (int64_t) max_host_call_bytes,
        .max_output_bytes = (int64_t) max_output_bytes,
        .max_http_response_bytes = (int64_t) max_http_response_bytes,
        .max_wasi_poll_millis = (int64_t) max_wasi_poll_millis,
    };
    wasmtime_kmp_http_handler_t native_http_handler = {0};
    if (!jni_http_handler_create(env, http_handler, &native_http_handler)) {
        (*env)->ReleaseByteArrayElements(env, wasm, bytes, JNI_ABORT);
        throw_illegal_state(env, "failed to create HTTP handler bridge");
        return 0;
    }
    const wasmtime_kmp_http_handler_t *http_handler_ptr =
        http_handler == NULL ? NULL : &native_http_handler;

    const char *storage_backing = storage_backing_path == NULL
        ? NULL : (*env)->GetStringUTFChars(env, storage_backing_path, NULL);
    const char *storage_guest = storage_guest_path == NULL
        ? NULL : (*env)->GetStringUTFChars(env, storage_guest_path, NULL);
    wasmtime_kmp_storage_t native_storage = {
        .backing_path = storage_backing,
        .guest_path = storage_guest,
        .read_only = storage_read_only == JNI_TRUE,
        .max_bytes = (int64_t) storage_max_bytes,
        .max_entries = (int64_t) storage_max_entries,
        .max_file_bytes = (int64_t) storage_max_file_bytes,
    };
    const wasmtime_kmp_storage_t *storage_ptr =
        storage_backing_path == NULL ? NULL : &native_storage;

    wasmtime_kmp_instance_t *instance = wasmtime_kmp_load_with_capabilities(
        (const uint8_t *) bytes,
        (size_t) size,
        &limits,
        http_handler_ptr,
        storage_ptr,
        &error
    );
    if (storage_backing != NULL) {
        (*env)->ReleaseStringUTFChars(env, storage_backing_path, storage_backing);
    }
    if (storage_guest != NULL) {
        (*env)->ReleaseStringUTFChars(env, storage_guest_path, storage_guest);
    }
    (*env)->ReleaseByteArrayElements(env, wasm, bytes, JNI_ABORT);

    if (instance == NULL) {
        throw_illegal_state(env, error);
        wasmtime_kmp_string_free(error);
        return 0;
    }
    return (jlong) (intptr_t) instance;
}

JNIEXPORT jlong JNICALL
Java_dev_brahmkshatriya_wasmtime_internal_NativeWasmtime_nativeLoadWithRuntime(
    JNIEnv *env,
    jclass clazz,
    jbyteArray wasm,
    jobjectArray runtime_module_names,
    jobjectArray runtime_modules,
    jlong max_memory_bytes,
    jlong fuel,
    jlong max_execution_millis,
    jlong max_table_elements,
    jint max_host_call_bytes,
    jlong max_output_bytes,
    jint max_http_response_bytes,
    jlong max_wasi_poll_millis,
    jobject http_handler,
    jstring storage_backing_path,
    jstring storage_guest_path,
    jboolean storage_read_only,
    jlong storage_max_bytes,
    jint storage_max_entries,
    jlong storage_max_file_bytes
) {
    (void) clazz;
    jsize size = (*env)->GetArrayLength(env, wasm);
    jbyte *bytes = (*env)->GetByteArrayElements(env, wasm, NULL);
    if (bytes == NULL) return 0;

    wasmtime_kmp_jni_runtime_modules_t native_runtime = {0};
    if (!jni_runtime_modules_create(
            env,
            runtime_module_names,
            runtime_modules,
            &native_runtime
        )) {
        (*env)->ReleaseByteArrayElements(env, wasm, bytes, JNI_ABORT);
        throw_illegal_state(env, "failed to create runtime module bridge");
        return 0;
    }

    char *error = NULL;
    wasmtime_kmp_limits_t limits = {
        .max_memory_bytes = (int64_t) max_memory_bytes,
        .fuel = (uint64_t) fuel,
        .max_execution_millis = (int64_t) max_execution_millis,
        .max_table_elements = (int64_t) max_table_elements,
        .max_host_call_bytes = (int64_t) max_host_call_bytes,
        .max_output_bytes = (int64_t) max_output_bytes,
        .max_http_response_bytes = (int64_t) max_http_response_bytes,
        .max_wasi_poll_millis = (int64_t) max_wasi_poll_millis,
    };
    wasmtime_kmp_http_handler_t native_http_handler = {0};
    if (!jni_http_handler_create(env, http_handler, &native_http_handler)) {
        jni_runtime_modules_dispose(env, &native_runtime);
        (*env)->ReleaseByteArrayElements(env, wasm, bytes, JNI_ABORT);
        throw_illegal_state(env, "failed to create HTTP handler bridge");
        return 0;
    }
    const wasmtime_kmp_http_handler_t *http_handler_ptr =
        http_handler == NULL ? NULL : &native_http_handler;

    const char *storage_backing = storage_backing_path == NULL
        ? NULL : (*env)->GetStringUTFChars(env, storage_backing_path, NULL);
    const char *storage_guest = storage_guest_path == NULL
        ? NULL : (*env)->GetStringUTFChars(env, storage_guest_path, NULL);
    wasmtime_kmp_storage_t native_storage = {
        .backing_path = storage_backing,
        .guest_path = storage_guest,
        .read_only = storage_read_only == JNI_TRUE,
        .max_bytes = (int64_t) storage_max_bytes,
        .max_entries = (int64_t) storage_max_entries,
        .max_file_bytes = (int64_t) storage_max_file_bytes,
    };
    const wasmtime_kmp_storage_t *storage_ptr =
        storage_backing_path == NULL ? NULL : &native_storage;

    wasmtime_kmp_instance_t *instance = wasmtime_kmp_load_with_runtime(
        (const uint8_t *) bytes,
        (size_t) size,
        native_runtime.modules,
        (size_t) native_runtime.count,
        &limits,
        http_handler_ptr,
        storage_ptr,
        &error
    );
    if (storage_backing != NULL) {
        (*env)->ReleaseStringUTFChars(env, storage_backing_path, storage_backing);
    }
    if (storage_guest != NULL) {
        (*env)->ReleaseStringUTFChars(env, storage_guest_path, storage_guest);
    }
    jni_runtime_modules_dispose(env, &native_runtime);
    (*env)->ReleaseByteArrayElements(env, wasm, bytes, JNI_ABORT);

    if (instance == NULL) {
        throw_illegal_state(env, error);
        wasmtime_kmp_string_free(error);
        return 0;
    }
    return (jlong) (intptr_t) instance;
}

JNIEXPORT jint JNICALL
Java_dev_brahmkshatriya_wasmtime_internal_NativeWasmtime_nativeCallI32(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jstring export_name,
    jint first,
    jint second
) {
    (void) clazz;
    const char *name = (*env)->GetStringUTFChars(env, export_name, NULL);
    if (name == NULL) return 0;

    int32_t result = 0;
    char *error = NULL;
    int ok = wasmtime_kmp_call_i32_2(
        (wasmtime_kmp_instance_t *) (intptr_t) handle,
        name,
        first,
        second,
        &result,
        &error
    );
    (*env)->ReleaseStringUTFChars(env, export_name, name);

    if (!ok) {
        throw_illegal_state(env, error);
        wasmtime_kmp_string_free(error);
        return 0;
    }
    return (jint) result;
}

JNIEXPORT jlong JNICALL
Java_dev_brahmkshatriya_wasmtime_internal_NativeWasmtime_nativeResolveI32(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jstring export_name
) {
    (void) clazz;
    const char *name = (*env)->GetStringUTFChars(env, export_name, NULL);
    if (name == NULL) return 0;

    char *error = NULL;
    wasmtime_kmp_func_i32_2_t *function = wasmtime_kmp_resolve_i32_2(
        (wasmtime_kmp_instance_t *) (intptr_t) handle,
        name,
        &error
    );
    (*env)->ReleaseStringUTFChars(env, export_name, name);

    if (function == NULL) {
        throw_illegal_state(env, error);
        wasmtime_kmp_string_free(error);
        return 0;
    }
    return (jlong) (intptr_t) function;
}

JNIEXPORT jint JNICALL
Java_dev_brahmkshatriya_wasmtime_internal_NativeWasmtime_nativeCallResolvedI32(
    JNIEnv *env,
    jclass clazz,
    jlong function_handle,
    jint first,
    jint second
) {
    (void) clazz;
    int32_t result = 0;
    char *error = NULL;
    int ok = wasmtime_kmp_func_i32_2_call_managed(
        (wasmtime_kmp_func_i32_2_t *) (intptr_t) function_handle,
        first,
        second,
        &result,
        &error
    );
    if (!ok) {
        throw_illegal_state(env, error);
        wasmtime_kmp_string_free(error);
        return 0;
    }
    return (jint) result;
}

JNIEXPORT jlong JNICALL
Java_dev_brahmkshatriya_wasmtime_internal_NativeWasmtime_nativeCallResolvedI32AsyncStart(
    JNIEnv *env,
    jclass clazz,
    jlong function_handle,
    jint first,
    jint second
) {
    (void) clazz;
    char *error = NULL;
    wasmtime_kmp_func_i32_2_future_t *future =
        wasmtime_kmp_func_i32_2_call_async_start(
            (wasmtime_kmp_func_i32_2_t *) (intptr_t) function_handle,
            first,
            second,
            &error
        );
    if (future == NULL) {
        throw_illegal_state(env, error);
        wasmtime_kmp_string_free(error);
        return 0;
    }
    return (jlong) (intptr_t) future;
}

JNIEXPORT jlong JNICALL
Java_dev_brahmkshatriya_wasmtime_internal_NativeWasmtime_nativeCallResolvedI32AsyncPoll(
    JNIEnv *env,
    jclass clazz,
    jlong future_handle
) {
    (void) clazz;
    int32_t result = 0;
    char *error = NULL;
    int status = wasmtime_kmp_func_i32_2_call_async_poll(
        (wasmtime_kmp_func_i32_2_future_t *) (intptr_t) future_handle,
        &result,
        &error
    );
    if (status < 0) {
        throw_illegal_state(env, error);
        wasmtime_kmp_string_free(error);
        return 0;
    }
    if (status == 0) return INT64_MIN;
    return (jlong) result;
}

JNIEXPORT void JNICALL
Java_dev_brahmkshatriya_wasmtime_internal_NativeWasmtime_nativeCallResolvedI32AsyncClose(
    JNIEnv *env,
    jclass clazz,
    jlong future_handle
) {
    (void) env;
    (void) clazz;
    wasmtime_kmp_func_i32_2_call_async_close(
        (wasmtime_kmp_func_i32_2_future_t *) (intptr_t) future_handle
    );
}

JNIEXPORT void JNICALL
Java_dev_brahmkshatriya_wasmtime_internal_NativeWasmtime_nativeFunctionClose(
    JNIEnv *env,
    jclass clazz,
    jlong function_handle
) {
    (void) env;
    (void) clazz;
    wasmtime_kmp_func_i32_2_close(
        (wasmtime_kmp_func_i32_2_t *) (intptr_t) function_handle
    );
}

JNIEXPORT void JNICALL
Java_dev_brahmkshatriya_wasmtime_internal_NativeWasmtime_nativeClose(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void) env;
    (void) clazz;
    wasmtime_kmp_close((wasmtime_kmp_instance_t *) (intptr_t) handle);
}
#endif
