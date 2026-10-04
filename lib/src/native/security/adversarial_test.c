#define _XOPEN_SOURCE 700
#include "wasmtime_kmp.h"

#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define WASI_E2BIG 1
#define WASI_ENOTCAPABLE 76

typedef struct fake_http_op { int done; } fake_http_op_t;

static uint8_t *read_file(const char *path, size_t *size_out) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    rewind(f);
    if (n <= 0) { fclose(f); return NULL; }
    uint8_t *data = malloc((size_t) n);
    if (!data) { fclose(f); return NULL; }
    if (fread(data, 1, (size_t) n, f) != (size_t) n) { free(data); fclose(f); return NULL; }
    fclose(f);
    *size_out = (size_t) n;
    return data;
}

static uint64_t monotonic_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t) ts.tv_sec * 1000 + (uint64_t) ts.tv_nsec / 1000000;
}

static void sleep_millis(long millis) {
    struct timespec ts = { .tv_sec = millis / 1000, .tv_nsec = (millis % 1000) * 1000000L };
    while (nanosleep(&ts, &ts) != 0 && errno == EINTR) {}
}

static wasmtime_kmp_limits_t limits_default(void) {
    return (wasmtime_kmp_limits_t) {
        .max_memory_bytes = 64LL * 1024 * 1024,
        .fuel = 100000000,
        .max_execution_millis = 1000,
        .max_table_elements = 1000000,
        .max_host_call_bytes = 1024 * 1024,
        .max_output_bytes = 1024 * 1024,
        .max_http_response_bytes = 16 * 1024 * 1024,
        .max_wasi_poll_millis = 1000,
    };
}

static wasmtime_kmp_storage_t storage_default(const char *path) {
    return (wasmtime_kmp_storage_t) {
        .backing_path = path,
        .guest_path = "/data",
        .read_only = false,
        .max_bytes = 64LL * 1024 * 1024,
        .max_entries = 4096,
        .max_file_bytes = 16LL * 1024 * 1024,
    };
}

static wasmtime_kmp_instance_t *load_fixture(
    const char *dir,
    const char *name,
    const wasmtime_kmp_limits_t *limits,
    const wasmtime_kmp_http_handler_t *http,
    const wasmtime_kmp_storage_t *storage,
    char **error_out
) {
    char path[4096];
    snprintf(path, sizeof(path), "%s/%s", dir, name);
    size_t size = 0;
    uint8_t *bytes = read_file(path, &size);
    assert(bytes != NULL);
    wasmtime_kmp_instance_t *instance = wasmtime_kmp_load_with_capabilities(
        bytes, size, limits, http, storage, error_out
    );
    free(bytes);
    return instance;
}

static int call_run(wasmtime_kmp_instance_t *instance, int first, int second, char **error_out) {
    int32_t result = 0;
    int ok = wasmtime_kmp_call_i32_2(instance, "run", first, second, &result, error_out);
    return ok ? result : INT32_MIN;
}

static void *fake_http_start(
    void *user_data, const uint8_t *meta, size_t meta_len, const uint8_t *body, size_t body_len
) {
    (void) user_data; (void) meta; (void) meta_len; (void) body; (void) body_len;
    return calloc(1, sizeof(fake_http_op_t));
}

static int fake_http_poll(
    void *user_data, void *operation,
    uint8_t **meta_out, size_t *meta_len_out,
    uint8_t **body_out, size_t *body_len_out
) {
    (void) operation;
    size_t body_size = *(size_t *) user_data;
    *meta_out = malloc(2);
    *body_out = malloc(body_size);
    assert(*meta_out != NULL && (body_size == 0 || *body_out != NULL));
    (*meta_out)[0] = 'x'; (*meta_out)[1] = 0;
    if (body_size) memset(*body_out, 0x5a, body_size);
    *meta_len_out = 2;
    *body_len_out = body_size;
    return 1;
}

static void fake_http_dispose(void *user_data, void *operation) {
    (void) user_data;
    free(operation);
}

static void fake_http_free(void *user_data, uint8_t *buffer, size_t len) {
    (void) user_data; (void) len;
    free(buffer);
}

static void fake_http_handler_dispose(void *user_data) { (void) user_data; }

static wasmtime_kmp_http_handler_t fake_http(size_t *response_size) {
    return (wasmtime_kmp_http_handler_t) {
        .user_data = response_size,
        .execute_start = fake_http_start,
        .execute_poll = fake_http_poll,
        .execute_dispose = fake_http_dispose,
        .free_buffer = fake_http_free,
        .dispose = fake_http_handler_dispose,
    };
}

typedef struct call_thread_arg {
    wasmtime_kmp_func_i32_2_t *function;
    int ok;
} call_thread_arg_t;

static void *call_thread(void *data) {
    call_thread_arg_t *arg = data;
    int32_t result = 0;
    char *error = NULL;
    arg->ok = wasmtime_kmp_func_i32_2_call(arg->function, 0, 0, &result, &error);
    wasmtime_kmp_string_free(error);
    return NULL;
}

int main(int argc, char **argv) {
    assert(argc == 2);
    const char *fixtures = argv[1];
    char *error = NULL;

    // Table growth is rejected by the store limiter.
    wasmtime_kmp_limits_t limits = limits_default();
    limits.max_table_elements = 10;
    wasmtime_kmp_instance_t *instance = load_fixture(fixtures, "table-grow.wasm", &limits, NULL, NULL, &error);
    assert(instance != NULL);
    assert(call_run(instance, 20, 0, &error) == -1);
    wasmtime_kmp_close(instance);
    puts("SECURITY|table_limit=ok");

    // Wasm-GC heap allocation goes through the same store memory limiter.
    limits = limits_default();
    limits.max_memory_bytes = 64 * 1024;
    instance = load_fixture(fixtures, "gc-array.wasm", &limits, NULL, NULL, &error);
    assert(instance != NULL);
    assert(call_run(instance, 1024 * 1024, 0, &error) == INT32_MIN);
    assert(error != NULL);
    wasmtime_kmp_string_free(error); error = NULL;
    wasmtime_kmp_close(instance);
    puts("SECURITY|gc_heap_limit=ok");

    // Host-call byte and output quotas fail before writing attacker-controlled output.
    limits = limits_default();
    limits.max_host_call_bytes = 8;
    instance = load_fixture(fixtures, "fd-write.wasm", &limits, NULL, NULL, &error);
    assert(instance != NULL);
    assert(call_run(instance, 0, 0, &error) == WASI_E2BIG);
    wasmtime_kmp_close(instance);
    limits = limits_default();
    limits.max_output_bytes = 8;
    instance = load_fixture(fixtures, "fd-write.wasm", &limits, NULL, NULL, &error);
    assert(instance != NULL);
    assert(call_run(instance, 0, 0, &error) == WASI_ENOTCAPABLE);
    wasmtime_kmp_close(instance);
    puts("SECURITY|host_call_output_limits=ok");

    // poll_oneoff cannot pin a host thread beyond the configured single-call duration.
    limits = limits_default();
    limits.max_wasi_poll_millis = 10;
    instance = load_fixture(fixtures, "poll.wasm", &limits, NULL, NULL, &error);
    assert(instance != NULL);
    uint64_t poll_start = monotonic_ms();
    assert(call_run(instance, 0, 0, &error) == WASI_ENOTCAPABLE);
    assert(monotonic_ms() - poll_start < 250);
    wasmtime_kmp_close(instance);
    puts("SECURITY|poll_limit=ok");

    char root_template[] = "/tmp/wasmtime-kmp-security-root-XXXXXX";
    char outside_template[] = "/tmp/wasmtime-kmp-security-outside-XXXXXX";
    char *root = mkdtemp(root_template);
    char *outside = mkdtemp(outside_template);
    assert(root && outside);
    wasmtime_kmp_storage_t storage = storage_default(root);

    // '..' is denied.
    limits = limits_default();
    instance = load_fixture(fixtures, "path-traversal.wasm", &limits, NULL, &storage, &error);
    assert(instance != NULL);
    assert(call_run(instance, 0, 0, &error) == WASI_ENOTCAPABLE);
    wasmtime_kmp_close(instance);

    // Intermediate symlinks cannot escape the preopened directory.
    char link_path[4096], outside_file[4096];
    snprintf(link_path, sizeof(link_path), "%s/link", root);
    snprintf(outside_file, sizeof(outside_file), "%s/pwn", outside);
    assert(symlink(outside, link_path) == 0);
    instance = load_fixture(fixtures, "path-symlink.wasm", &limits, NULL, &storage, &error);
    assert(instance != NULL);
    assert(call_run(instance, 0, 0, &error) != 0);
    assert(access(outside_file, F_OK) != 0);
    wasmtime_kmp_close(instance);
    unlink(link_path);
    puts("SECURITY|filesystem_escape=ok");

    // Pre-seeded hard links cannot turn a regular sandbox path into an outside-file capability.
    FILE *outside_fp = fopen(outside_file, "wb");
    assert(outside_fp); fputs("outside", outside_fp); fclose(outside_fp);
    char hardlink_path[4096];
    snprintf(hardlink_path, sizeof(hardlink_path), "%s/hardlink", root);
    assert(link(outside_file, hardlink_path) == 0);
    storage = storage_default(root);
    instance = load_fixture(fixtures, "fd-write.wasm", &limits, NULL, &storage, &error);
    assert(instance == NULL);
    assert(error != NULL);
    wasmtime_kmp_string_free(error); error = NULL;
    unlink(hardlink_path);
    unlink(outside_file);
    puts("SECURITY|hardlink_escape=ok");

    // Existing storage is scanned and rejected if it already exceeds quota.
    char big_file[4096];
    snprintf(big_file, sizeof(big_file), "%s/big", root);
    FILE *bf = fopen(big_file, "wb");
    assert(bf); fputs("12345678", bf); fclose(bf);
    storage.max_bytes = 4;
    instance = load_fixture(fixtures, "fd-write.wasm", &limits, NULL, &storage, &error);
    assert(instance == NULL);
    assert(error != NULL);
    wasmtime_kmp_string_free(error); error = NULL;
    unlink(big_file);
    puts("SECURITY|storage_quota=ok");

    // Quota accounting is serialized across concurrent writable instances.
    storage = storage_default(root);
    limits = limits_default();
    wasmtime_kmp_instance_t *storage_owner = load_fixture(
        fixtures, "fd-write.wasm", &limits, NULL, &storage, &error
    );
    assert(storage_owner != NULL);
    wasmtime_kmp_instance_t *storage_contender = load_fixture(
        fixtures, "fd-write.wasm", &limits, NULL, &storage, &error
    );
    assert(storage_contender == NULL);
    assert(error != NULL && strstr(error, "already in use") != NULL);
    wasmtime_kmp_string_free(error); error = NULL;
    wasmtime_kmp_close(storage_owner);
    storage_contender = load_fixture(fixtures, "fd-write.wasm", &limits, NULL, &storage, &error);
    assert(storage_contender != NULL);
    wasmtime_kmp_close(storage_contender);
    puts("SECURITY|storage_lock=ok");

    // Request allocation is tied to max_host_call_bytes.
    size_t response_size = 0;
    wasmtime_kmp_http_handler_t http = fake_http(&response_size);
    limits = limits_default();
    instance = load_fixture(fixtures, "http-request-create.wasm", &limits, &http, NULL, &error);
    assert(instance != NULL);
    assert(call_run(instance, 0, 0, &error) == -1);
    wasmtime_kmp_close(instance);
    puts("SECURITY|http_request_limit=ok");

    // Oversized host responses are discarded before becoming guest-visible.
    response_size = 2048;
    http = fake_http(&response_size);
    limits = limits_default();
    limits.max_http_response_bytes = 1024;
    instance = load_fixture(fixtures, "http-response.wasm", &limits, &http, NULL, &error);
    assert(instance != NULL);
    assert(call_run(instance, 0, 0, &error) == -1);
    wasmtime_kmp_close(instance);
    puts("SECURITY|http_response_limit=ok");

    // A pure CPU infinite loop is interrupted by the shared epoch ticker even with unlimited fuel.
    limits = limits_default();
    limits.fuel = 0;
    limits.max_execution_millis = 50;
    instance = load_fixture(fixtures, "infinite.wasm", &limits, NULL, NULL, &error);
    assert(instance != NULL);
    uint64_t deadline_start = monotonic_ms();
    assert(call_run(instance, 0, 0, &error) == INT32_MIN);
    assert(monotonic_ms() - deadline_start < 500);
    wasmtime_kmp_string_free(error); error = NULL;
    wasmtime_kmp_close(instance);
    puts("SECURITY|execution_deadline=ok");

    // Closing function/instance/module while a call is active must not free live native state.
    char infinite_path[4096];
    snprintf(infinite_path, sizeof(infinite_path), "%s/infinite.wasm", fixtures);
    size_t infinite_size = 0;
    uint8_t *infinite_bytes = read_file(infinite_path, &infinite_size);
    wasmtime_kmp_module_t *module = wasmtime_kmp_compile(infinite_bytes, infinite_size, &error);
    free(infinite_bytes);
    assert(module != NULL);
    limits = limits_default(); limits.fuel = 0; limits.max_execution_millis = 100;
    instance = wasmtime_kmp_instantiate(module, &limits, &error);
    assert(instance != NULL);
    wasmtime_kmp_func_i32_2_t *function = wasmtime_kmp_resolve_i32_2(instance, "run", &error);
    assert(function != NULL);
    call_thread_arg_t arg = { .function = function, .ok = 1 };
    pthread_t thread;
    assert(pthread_create(&thread, NULL, call_thread, &arg) == 0);
    sleep_millis(20);
    wasmtime_kmp_func_i32_2_close(function);
    wasmtime_kmp_close(instance);
    wasmtime_kmp_module_close(module);
    pthread_join(thread, NULL);
    assert(arg.ok == 0);
    puts("SECURITY|concurrent_close=ok");

    // Cache and guest storage may never overlap.
    char cache_template[] = "/tmp/wasmtime-kmp-security-cache-XXXXXX";
    char *cache = mkdtemp(cache_template);
    assert(cache);
    assert(setenv("WASMTIME_KMP_CACHE_DIR", cache, 1) == 0);
    storage = storage_default(cache);
    limits = limits_default();
    instance = load_fixture(fixtures, "fd-write.wasm", &limits, NULL, &storage, &error);
    assert(instance == NULL);
    wasmtime_kmp_string_free(error); error = NULL;
    puts("SECURITY|cache_storage_separation=ok");

    // Corrupt serialized cache data must be treated as a cache miss, not trusted native code.
    char cache_modules[4096];
    snprintf(cache_modules, sizeof(cache_modules), "%s/modules-v1", cache);
    DIR *cleanup_dir = opendir(cache_modules);
    assert(cleanup_dir != NULL);
    struct dirent *cleanup_entry;
    while ((cleanup_entry = readdir(cleanup_dir)) != NULL) {
        if (strstr(cleanup_entry->d_name, ".cwasm")) {
            char old_cache_file[4096];
            size_t base_len = strlen(cache_modules);
            size_t name_len = strlen(cleanup_entry->d_name);
            assert(base_len + 1 + name_len + 1 <= sizeof(old_cache_file));
            memcpy(old_cache_file, cache_modules, base_len);
            old_cache_file[base_len] = '/';
            memcpy(old_cache_file + base_len + 1, cleanup_entry->d_name, name_len + 1);
            unlink(old_cache_file);
        }
    }
    closedir(cleanup_dir);
    char table_path[4096];
    snprintf(table_path, sizeof(table_path), "%s/table-grow.wasm", fixtures);
    size_t table_size = 0;
    uint8_t *table_bytes = read_file(table_path, &table_size);
    module = wasmtime_kmp_compile(table_bytes, table_size, &error);
    assert(module != NULL);
    wasmtime_kmp_module_close(module);
    DIR *dir = opendir(cache_modules);
    assert(dir != NULL);
    struct dirent *entry;
    char cache_file[4096] = {0};
    while ((entry = readdir(dir)) != NULL) {
        if (strstr(entry->d_name, ".cwasm")) {
            size_t base_len = strlen(cache_modules);
            size_t name_len = strlen(entry->d_name);
            assert(base_len + 1 + name_len + 1 <= sizeof(cache_file));
            memcpy(cache_file, cache_modules, base_len);
            cache_file[base_len] = '/';
            memcpy(cache_file + base_len + 1, entry->d_name, name_len + 1);
            break;
        }
    }
    closedir(dir);
    assert(cache_file[0]);
    FILE *cf = fopen(cache_file, "wb"); assert(cf); fputs("junk", cf); fclose(cf);
    module = wasmtime_kmp_compile(table_bytes, table_size, &error);
    free(table_bytes);
    assert(module != NULL);
    wasmtime_kmp_module_close(module);
    struct stat cache_stat; assert(stat(cache_file, &cache_stat) == 0 && cache_stat.st_size > 4);
    puts("SECURITY|cache_corruption_recovery=ok");

    DIR *final_cache_dir = opendir(cache_modules);
    if (final_cache_dir != NULL) {
        struct dirent *final_entry;
        while ((final_entry = readdir(final_cache_dir)) != NULL) {
            if (strstr(final_entry->d_name, ".cwasm")) {
                char final_path[4096];
                size_t base_len = strlen(cache_modules), name_len = strlen(final_entry->d_name);
                if (base_len + 1 + name_len + 1 <= sizeof(final_path)) {
                    memcpy(final_path, cache_modules, base_len); final_path[base_len] = '/';
                    memcpy(final_path + base_len + 1, final_entry->d_name, name_len + 1);
                    unlink(final_path);
                }
            }
        }
        closedir(final_cache_dir);
    }
    unsetenv("WASMTIME_KMP_CACHE_DIR");
    rmdir(cache_modules); rmdir(cache);
    rmdir(root); rmdir(outside);
    puts("SECURITY|all=ok");
    return 0;
}
