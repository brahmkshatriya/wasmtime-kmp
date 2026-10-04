#ifndef WASMTIME_KMP_WASI_LITE_H
#define WASMTIME_KMP_WASI_LITE_H

#include "wasmtime_kmp.h"
#include <wasmtime.h>

typedef struct wasmtime_kmp_wasi_lite wasmtime_kmp_wasi_lite_t;

wasmtime_kmp_wasi_lite_t *wasmtime_kmp_wasi_lite_new(
    const wasmtime_kmp_storage_t *storage,
    const wasmtime_kmp_limits_t *limits,
    char **error_out
);

wasmtime_error_t *wasmtime_kmp_wasi_lite_define(
    wasmtime_linker_t *linker,
    wasmtime_kmp_wasi_lite_t *state
);

void wasmtime_kmp_wasi_lite_delete(wasmtime_kmp_wasi_lite_t *state);

#endif
