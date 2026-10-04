#!/usr/bin/env bash
set -euo pipefail

KEXE="${1:?usage: $0 test.kexe}"

find_dependency_dir() {
    local pattern="$1"
    local candidate
    for candidate in "$HOME"/.konan/dependencies/$pattern; do
        if [[ -d "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return
        fi
    done
    echo "Kotlin/Native dependency not found: $pattern" >&2
    exit 1
}

TOOLCHAIN="$(find_dependency_dir 'aarch64-unknown-linux-gnu-gcc-*')"
QEMU_DIR="$(find_dependency_dir 'qemu-aarch64-static-*')"
QEMU="$QEMU_DIR/qemu-aarch64"
SYSROOT="$TOOLCHAIN/aarch64-unknown-linux-gnu/sysroot"

[[ -x "$QEMU" ]] || { echo "QEMU AArch64 executable not found: $QEMU" >&2; exit 1; }
[[ -d "$SYSROOT" ]] || { echo "Linux ARM64 sysroot not found: $SYSROOT" >&2; exit 1; }
[[ -x "$KEXE" ]] || { echo "Linux ARM64 test executable not found: $KEXE" >&2; exit 1; }

WASMTIME_KMP_CACHE_DIR=off exec "$QEMU" -L "$SYSROOT" "$KEXE"
