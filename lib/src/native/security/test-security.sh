#!/usr/bin/env bash
set -euo pipefail

SECURITY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NATIVE_DIR="$(cd "$SECURITY_DIR/.." && pwd)"
BRIDGE_DIR="${1:?usage: test-security.sh <linux-native-bridge-dir>}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/wasmtime-kmp-security.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

python3 "$SECURITY_DIR/generate_wasm.py" "$WORK/wasm"

COMMON_LINK=(
    "$BRIDGE_DIR/libwasmtime_kmp_bridge.a"
    "$BRIDGE_DIR/libwasmtime.a"
    -lpthread -ldl -lm
)

cc -O2 -std=c11 -I"$NATIVE_DIR" \
    "$SECURITY_DIR/security_test.c" "${COMMON_LINK[@]}" \
    -o "$WORK/security_test"
WASMTIME_KMP_CACHE_DIR=off "$WORK/security_test" "$WORK/wasm"

python3 "$SECURITY_DIR/generate_adversarial_wasm.py" "$WORK/adversarial-wasm"
cc -O2 -std=c11 -Wall -Wextra -Werror -I"$NATIVE_DIR" \
    "$SECURITY_DIR/adversarial_test.c" "${COMMON_LINK[@]}" \
    -o "$WORK/adversarial_test"
WASMTIME_KMP_CACHE_DIR=off "$WORK/adversarial_test" "$WORK/adversarial-wasm"

cc -O2 -std=c11 -I"$NATIVE_DIR" \
    "$SECURITY_DIR/cache_probe.c" "${COMMON_LINK[@]}" \
    -o "$WORK/cache_probe"

CACHE="$WORK/cache"
mkdir -m 700 "$CACHE"
WASMTIME_KMP_CACHE_DIR="$CACHE" "$WORK/cache_probe" "$WORK/wasm/add.wasm"
CACHE_FILE="$(find "$CACHE/modules-v1" -type f -name '*.cwasm' -print -quit)"
test -n "$CACHE_FILE"
test "$(stat -c %a "$CACHE_FILE")" = 600
ORIGINAL_SIZE="$(stat -c %s "$CACHE_FILE")"

printf 'corrupt' > "$CACHE_FILE"
WASMTIME_KMP_CACHE_DIR="$CACHE" "$WORK/cache_probe" "$WORK/wasm/add.wasm"
test "$(stat -c %s "$CACHE_FILE")" = "$ORIGINAL_SIZE"
echo 'PASS|private corrupt cache self-heals'

printf 'corrupt-again' > "$CACHE_FILE"
chmod 0777 "$CACHE/modules-v1"
WASMTIME_KMP_CACHE_DIR="$CACHE" "$WORK/cache_probe" "$WORK/wasm/add.wasm"
test "$(stat -c %s "$CACHE_FILE")" = 13
echo 'PASS|untrusted writable cache is ignored'
