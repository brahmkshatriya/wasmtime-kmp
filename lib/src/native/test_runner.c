#include "wasmtime_kmp.h"

#include <stdio.h>
#include <stdlib.h>

static unsigned char *read_file(const char *path, size_t *size_out) {
    FILE *file = fopen(path, "rb");
    if (file == NULL) return NULL;
    fseek(file, 0, SEEK_END);
    long size = ftell(file);
    rewind(file);
    if (size < 0) {
        fclose(file);
        return NULL;
    }
    unsigned char *data = malloc((size_t) size);
    if (data == NULL) {
        fclose(file);
        return NULL;
    }
    if (fread(data, 1, (size_t) size, file) != (size_t) size) {
        free(data);
        fclose(file);
        return NULL;
    }
    fclose(file);
    *size_out = (size_t) size;
    return data;
}

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: %s plugin.wasm\n", argv[0]);
        return 2;
    }

    size_t wasm_size = 0;
    unsigned char *wasm = read_file(argv[1], &wasm_size);
    if (wasm == NULL) {
        fprintf(stderr, "failed to read %s\n", argv[1]);
        return 2;
    }

    char *error = NULL;
    wasmtime_kmp_limits_t limits = {
        .max_memory_bytes = 64 * 1024 * 1024,
        .fuel = 100000000,
    };
    wasmtime_kmp_instance_t *instance = wasmtime_kmp_load(wasm, wasm_size, &limits, &error);
    free(wasm);
    if (instance == NULL) {
        fprintf(stderr, "load failed: %s\n", error);
        wasmtime_kmp_string_free(error);
        return 1;
    }

    int32_t result = 0;
    if (!wasmtime_kmp_call_i32_2(instance, "add", 20, 22, &result, &error)) {
        fprintf(stderr, "call failed: %s\n", error);
        wasmtime_kmp_string_free(error);
        wasmtime_kmp_close(instance);
        return 1;
    }

    printf("add(20, 22) = %d\n", result);
    wasmtime_kmp_close(instance);
    return result == 42 ? 0 : 1;
}
