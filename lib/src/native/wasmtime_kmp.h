#ifndef WASMTIME_KMP_H
#define WASMTIME_KMP_H

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct wasmtime_kmp_instance wasmtime_kmp_instance_t;
typedef struct wasmtime_kmp_module wasmtime_kmp_module_t;
typedef struct wasmtime_kmp_func_i32_2 wasmtime_kmp_func_i32_2_t;
typedef struct wasmtime_kmp_func_i32_2_future wasmtime_kmp_func_i32_2_future_t;

typedef struct wasmtime_kmp_limits {
    int64_t max_memory_bytes;
    uint64_t fuel;
    int64_t max_execution_millis;
    int64_t max_table_elements;
    int64_t max_host_call_bytes;
    int64_t max_output_bytes;
    int64_t max_http_response_bytes;
    int64_t max_wasi_poll_millis;
} wasmtime_kmp_limits_t;

typedef struct wasmtime_kmp_storage {
    const char *backing_path;
    const char *guest_path;
    bool read_only;
    int64_t max_bytes;
    int64_t max_entries;
    int64_t max_file_bytes;
} wasmtime_kmp_storage_t;

typedef struct wasmtime_kmp_linked_module {
    const char *name;
    const uint8_t *wasm;
    size_t wasm_len;
} wasmtime_kmp_linked_module_t;

typedef void *(*wasmtime_kmp_http_execute_start_fn)(
    void *user_data,
    const uint8_t *request_metadata,
    size_t request_metadata_len,
    const uint8_t *request_body,
    size_t request_body_len
);

/* Returns 0 while pending, 1 on success, and -1 on failure. */
typedef int (*wasmtime_kmp_http_execute_poll_fn)(
    void *user_data,
    void *operation,
    uint8_t **response_metadata_out,
    size_t *response_metadata_len_out,
    uint8_t **response_body_out,
    size_t *response_body_len_out
);

typedef void (*wasmtime_kmp_http_execute_dispose_fn)(
    void *user_data,
    void *operation
);

typedef void (*wasmtime_kmp_http_free_buffer_fn)(
    void *user_data,
    uint8_t *buffer,
    size_t buffer_len
);

typedef void (*wasmtime_kmp_http_dispose_fn)(void *user_data);

typedef struct wasmtime_kmp_http_handler {
    void *user_data;
    wasmtime_kmp_http_execute_start_fn execute_start;
    wasmtime_kmp_http_execute_poll_fn execute_poll;
    wasmtime_kmp_http_execute_dispose_fn execute_dispose;
    wasmtime_kmp_http_free_buffer_fn free_buffer;
    wasmtime_kmp_http_dispose_fn dispose;
} wasmtime_kmp_http_handler_t;

wasmtime_kmp_module_t *wasmtime_kmp_compile(
    const uint8_t *wasm,
    size_t wasm_len,
    char **error_out
);

wasmtime_kmp_instance_t *wasmtime_kmp_instantiate(
    wasmtime_kmp_module_t *module,
    const wasmtime_kmp_limits_t *limits,
    char **error_out
);

wasmtime_kmp_instance_t *wasmtime_kmp_instantiate_with_capabilities(
    wasmtime_kmp_module_t *module,
    const wasmtime_kmp_limits_t *limits,
    const wasmtime_kmp_http_handler_t *http_handler,
    const wasmtime_kmp_storage_t *storage,
    char **error_out
);

wasmtime_kmp_instance_t *wasmtime_kmp_instantiate_with_runtime(
    wasmtime_kmp_module_t *module,
    const wasmtime_kmp_linked_module_t *runtime_modules,
    size_t runtime_module_count,
    const wasmtime_kmp_limits_t *limits,
    const wasmtime_kmp_http_handler_t *http_handler,
    const wasmtime_kmp_storage_t *storage,
    char **error_out
);

wasmtime_kmp_instance_t *wasmtime_kmp_instantiate_with_http(
    wasmtime_kmp_module_t *module,
    const wasmtime_kmp_limits_t *limits,
    const wasmtime_kmp_http_handler_t *http_handler,
    char **error_out
);

wasmtime_kmp_instance_t *wasmtime_kmp_load(
    const uint8_t *wasm,
    size_t wasm_len,
    const wasmtime_kmp_limits_t *limits,
    char **error_out
);

wasmtime_kmp_instance_t *wasmtime_kmp_load_with_capabilities(
    const uint8_t *wasm,
    size_t wasm_len,
    const wasmtime_kmp_limits_t *limits,
    const wasmtime_kmp_http_handler_t *http_handler,
    const wasmtime_kmp_storage_t *storage,
    char **error_out
);

wasmtime_kmp_instance_t *wasmtime_kmp_load_with_runtime(
    const uint8_t *wasm,
    size_t wasm_len,
    const wasmtime_kmp_linked_module_t *runtime_modules,
    size_t runtime_module_count,
    const wasmtime_kmp_limits_t *limits,
    const wasmtime_kmp_http_handler_t *http_handler,
    const wasmtime_kmp_storage_t *storage,
    char **error_out
);

wasmtime_kmp_instance_t *wasmtime_kmp_load_with_http(
    const uint8_t *wasm,
    size_t wasm_len,
    const wasmtime_kmp_limits_t *limits,
    const wasmtime_kmp_http_handler_t *http_handler,
    char **error_out
);

int wasmtime_kmp_call_i32_2(
    wasmtime_kmp_instance_t *instance,
    const char *export_name,
    int32_t first,
    int32_t second,
    int32_t *result_out,
    char **error_out
);

wasmtime_kmp_func_i32_2_t *wasmtime_kmp_resolve_i32_2(
    wasmtime_kmp_instance_t *instance,
    const char *export_name,
    char **error_out
);

int wasmtime_kmp_func_i32_2_call(
    wasmtime_kmp_func_i32_2_t *function,
    int32_t first,
    int32_t second,
    int32_t *result_out,
    char **error_out
);

/*
 * Fast call for bindings that externally synchronize function/instance lifetime.
 * Kotlin platform bindings use this only while their managed lifecycle guards are held.
 */
int wasmtime_kmp_func_i32_2_call_managed(
    wasmtime_kmp_func_i32_2_t *function,
    int32_t first,
    int32_t second,
    int32_t *result_out,
    char **error_out
);

wasmtime_kmp_func_i32_2_future_t *wasmtime_kmp_func_i32_2_call_async_start(
    wasmtime_kmp_func_i32_2_t *function,
    int32_t first,
    int32_t second,
    char **error_out
);

/* Returns 0 while pending, 1 on success, and -1 on failure. */
int wasmtime_kmp_func_i32_2_call_async_poll(
    wasmtime_kmp_func_i32_2_future_t *future,
    int32_t *result_out,
    char **error_out
);

void wasmtime_kmp_func_i32_2_call_async_close(
    wasmtime_kmp_func_i32_2_future_t *future
);

void wasmtime_kmp_func_i32_2_close(wasmtime_kmp_func_i32_2_t *function);

void wasmtime_kmp_close(wasmtime_kmp_instance_t *instance);
void wasmtime_kmp_module_close(wasmtime_kmp_module_t *module);
void wasmtime_kmp_string_free(char *string);

#ifdef __cplusplus
}
#endif

#endif
