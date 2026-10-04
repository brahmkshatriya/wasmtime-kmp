#include <stdio.h>
#include <stdlib.h>
#include "wasmtime_kmp.h"

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    FILE *file = fopen(argv[1], "rb");
    if (file == NULL) return 2;
    if (fseek(file, 0, SEEK_END) != 0) return 2;
    long size = ftell(file);
    if (size <= 0 || fseek(file, 0, SEEK_SET) != 0) return 2;
    uint8_t *bytes = malloc((size_t) size);
    if (bytes == NULL || fread(bytes, 1, (size_t) size, file) != (size_t) size) return 2;
    fclose(file);

    char *error = NULL;
    wasmtime_kmp_module_t *module = wasmtime_kmp_compile(bytes, (size_t) size, &error);
    free(bytes);
    if (module == NULL) {
        fprintf(stderr, "%s\n", error != NULL ? error : "compile failed");
        wasmtime_kmp_string_free(error);
        return 1;
    }
    wasmtime_kmp_module_close(module);
    return 0;
}
