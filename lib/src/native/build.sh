#!/usr/bin/env bash
set -euo pipefail

NATIVE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$NATIVE_DIR/../../.." && pwd)"
VERSION="${WASMTIME_VERSION:-49.0.1}"
DEPS="$NATIVE_DIR/.deps"

download_wasmtime() {
    local triplet="$1"
    local target="$DEPS/$triplet"
    local archive="$DEPS/wasmtime-v${VERSION}-${triplet}-c-api.tar.xz"
    local url="https://github.com/bytecodealliance/wasmtime/releases/download/v${VERSION}/wasmtime-v${VERSION}-${triplet}-c-api.tar.xz"

    if [[ ! -f "$target/include/wasmtime.h" || ! -f "$target/lib/libwasmtime.a" ]]; then
        mkdir -p "$DEPS"
        if [[ ! -f "$archive" ]]; then
            curl -fL --retry 3 --retry-delay 1 -o "$archive" "$url"
        fi
        rm -rf "$target"
        mkdir -p "$target"
        tar -xJf "$archive" -C "$target" --strip-components=1
    fi
    printf '%s\n' "$target"
}

select_wasmtime_archive() {
    local triplet="$1"
    local dep="$2"
    local slim="$DEPS/slim/$triplet/lib/libwasmtime.a"
    if [[ "${WASMTIME_USE_FULL:-0}" != "1" && -f "$slim" ]]; then
        echo "Using slim Wasmtime archive: $slim" >&2
        printf '%s\n' "$slim"
    else
        echo "Using official full Wasmtime archive: $dep/lib/libwasmtime.a" >&2
        printf '%s\n' "$dep/lib/libwasmtime.a"
    fi
}

find_linux_arm64_tool() {
    local tool="$1"
    local override_var="WASMTIME_LINUX_ARM64_${tool^^}"
    local override="${!override_var:-}"
    if [[ -n "$override" ]]; then
        [[ -x "$override" ]] || { echo "$override_var is not executable: $override" >&2; exit 1; }
        printf '%s\n' "$override"
        return
    fi
    case "$(uname -m)" in
        aarch64|arm64)
            command -v "$tool" >/dev/null 2>&1 || { echo "Native ARM64 tool not found: $tool" >&2; exit 1; }
            command -v "$tool"
            return
            ;;
    esac
    local path_tool="aarch64-unknown-linux-gnu-$tool"
    if command -v "$path_tool" >/dev/null 2>&1; then
        command -v "$path_tool"
        return
    fi
    local candidate
    for candidate in "$HOME"/.konan/dependencies/aarch64-unknown-linux-gnu-gcc-*/bin/"$path_tool"; do
        if [[ -x "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return
        fi
    done
    echo "Linux ARM64 cross tool not found: $path_tool (set $override_var)" >&2
    exit 1
}

find_konan_dependency() {
    local pattern="$1"
    local candidate
    for candidate in "$HOME"/.konan/dependencies/$pattern; do
        if [[ -d "$candidate" ]]; then printf '%s\n' "$candidate"; return; fi
    done
    echo "Kotlin/Native dependency not found: $pattern" >&2
    exit 1
}

is_windows_host() {
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) return 0 ;;
        *) return 1 ;;
    esac
}

find_windows_native_tool() {
    local tool="$1"
    local override_var="WASMTIME_WINDOWS_${tool^^}"
    local override="${!override_var:-}"
    if [[ -n "$override" ]]; then
        [[ -x "$override" ]] || { echo "$override_var is not executable: $override" >&2; exit 1; }
        printf '%s\n' "$override"
        return
    fi
    local bin="${WASMTIME_WINDOWS_TOOLCHAIN_BIN:-C:/msys64/mingw64/bin}"
    local candidate="$bin/$tool.exe"
    if [[ -x "$candidate" ]]; then
        printf '%s\n' "$candidate"
        return
    fi
    command -v "$tool" >/dev/null 2>&1 || { echo "Windows native tool not found: $tool" >&2; exit 1; }
    command -v "$tool"
}


build_linux() {
    local triplet="$1"
    local konan_dir="$2"
    local cc="$3"
    local ar="$4"
    local dep
    dep="$(download_wasmtime "$triplet")"
    local archive
    archive="$(select_wasmtime_archive "$triplet" "$dep")"
    local out="$ROOT/lib/build/native/$konan_dir"
    mkdir -p "$out"

    "$cc" -std=c11 -O2 -fPIC \
        -I"$dep/include" -I"$NATIVE_DIR" \
        -c "$NATIVE_DIR/wasmtime_kmp.c" \
        -o "$out/wasmtime_kmp.o"
    "$cc" -std=c11 -O2 -fPIC \
        -I"$dep/include" -I"$NATIVE_DIR" \
        -c "$NATIVE_DIR/wasi_lite.c" \
        -o "$out/wasi_lite.o"
    "$ar" rcs "$out/libwasmtime_kmp_bridge.a" "$out/wasmtime_kmp.o" "$out/wasi_lite.o"
    cp "$archive" "$out/libwasmtime.a"
}

build_linux_x64() {
    build_linux x86_64-linux linuxX64 "$(command -v cc)" "$(command -v ar)"
}

build_linux_arm64() {
    build_linux aarch64-linux linuxArm64 \
        "$(find_linux_arm64_tool gcc)" "$(find_linux_arm64_tool ar)"
}

build_windows_x64() {
    local headers
    headers="$(download_wasmtime x86_64-linux)"
    local archive="$DEPS/slim/x86_64-windows-gnu/lib/libwasmtime.a"
    [[ -f "$archive" ]] || { echo "Windows compact Wasmtime archive is missing; run build-slim-wasmtime.sh windows-x64" >&2; exit 1; }
    local out="$ROOT/lib/build/native/mingwX64"
    mkdir -p "$out"

    local cc ar
    local flags=(-std=c11 -O2 -D_WIN32_WINNT=0x0601 -I"$headers/include" -I"$NATIVE_DIR")
    if is_windows_host; then
        cc="$(find_windows_native_tool gcc)"
        ar="$(find_windows_native_tool ar)"
    else
        local mingw="$(find_konan_dependency 'msys2-mingw-w64-x86_64-*')"
        local llvm="$(find_konan_dependency 'llvm-*-x86_64-linux-essentials-*')"
        cc="$llvm/bin/clang"
        ar="$llvm/bin/llvm-ar"
        flags=(
            --target=x86_64-w64-windows-gnu --sysroot="$mingw"
            -isystem "$mingw/x86_64-w64-mingw32/include"
            "${flags[@]}"
        )
    fi

    "$cc" "${flags[@]}" -c "$NATIVE_DIR/wasmtime_kmp.c" -o "$out/wasmtime_kmp.o"
    "$cc" "${flags[@]}" -c "$NATIVE_DIR/wasi_lite.c" -o "$out/wasi_lite.o"
    "$cc" "${flags[@]}" -c "$NATIVE_DIR/windows_compat.c" -o "$out/windows_compat.o"
    "$ar" rcs "$out/libwasmtime_kmp_bridge.a" \
        "$out/wasmtime_kmp.o" "$out/wasi_lite.o" "$out/windows_compat.o"
    cp "$archive" "$out/libwasmtime.a"
}

build_apple() {
    local archive_triplet="$1"
    local konan_dir="$2"
    local sdk="$3"
    local arch="$4"
    local force_pulley="${5:-0}"
    [[ "$(uname -s)" == "Darwin" ]] || { echo "$konan_dir native bridge requires macOS/Xcode" >&2; exit 1; }
    local headers_triplet="$archive_triplet"
    [[ "$archive_triplet" == "aarch64-ios" ]] && headers_triplet="aarch64-macos"
    local dep
    dep="$(download_wasmtime "$headers_triplet")"
    local archive="$DEPS/slim/$archive_triplet/lib/libwasmtime.a"
    if [[ "${WASMTIME_USE_FULL:-0}" == "1" && "$archive_triplet" != "aarch64-ios" ]]; then
        archive="$dep/lib/libwasmtime.a"
    fi
    [[ -f "$archive" ]] || { echo "Wasmtime archive missing for $archive_triplet" >&2; exit 1; }
    local out="$ROOT/lib/build/native/$konan_dir"
    mkdir -p "$out"
    local sdk_path="$(xcrun --sdk "$sdk" --show-sdk-path)"
    local cc="$(xcrun --sdk "$sdk" -f clang)"
    local ar="$(xcrun -f ar)"
    local flags=(-std=c11 -O2 -fPIC -arch "$arch" -isysroot "$sdk_path" -I"$dep/include" -I"$NATIVE_DIR")
    [[ "$force_pulley" == "1" ]] && flags+=(-DWASMTIME_KMP_FORCE_PULLEY)
    "$cc" "${flags[@]}" -c "$NATIVE_DIR/wasmtime_kmp.c" -o "$out/wasmtime_kmp.o"
    "$cc" "${flags[@]}" -c "$NATIVE_DIR/wasi_lite.c" -o "$out/wasi_lite.o"
    "$ar" rcs "$out/libwasmtime_kmp_bridge.a" "$out/wasmtime_kmp.o" "$out/wasi_lite.o"
    cp "$archive" "$out/libwasmtime.a"
}

build_macos_x64() { build_apple x86_64-macos macosX64 macosx x86_64 0; }
build_macos_arm64() { build_apple aarch64-macos macosArm64 macosx arm64 0; }
build_ios_arm64() { build_apple aarch64-ios iosArm64 iphoneos arm64 1; }

build_android_abi() {
    local triplet="$1"
    local abi="$2"
    local compiler="$3"
    local dep
    dep="$(download_wasmtime "$triplet")"
    local archive
    archive="$(select_wasmtime_archive "$triplet" "$dep")"
    local ndk="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/29.0.14206865}"
    local toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
    local cc="$toolchain/$compiler"
    local out="$ROOT/lib/build/generated/jniLibs/$abi"

    if [[ ! -x "$cc" ]]; then
        echo "Android NDK compiler not found: $cc" >&2
        exit 1
    fi

    mkdir -p "$out"
    "$cc" -std=c11 -O2 -fPIC -shared \
        -I"$dep/include" -I"$NATIVE_DIR" \
        "$NATIVE_DIR/wasmtime_kmp.c" "$NATIVE_DIR/wasi_lite.c" "$archive" \
        -Wl,--gc-sections -Wl,--icf=all -Wl,-z,max-page-size=16384 \
        -ldl -lm -llog \
        -o "$out/libwasmtime_kmp.so"
}

build_jvm_linux() {
    local triplet="$1"
    local out_name="$2"
    local cc="$3"
    local strip_tool="$4"
    shift 4
    local linker_flags=("$@")
    local dep
    dep="$(download_wasmtime "$triplet")"
    local archive
    archive="$(select_wasmtime_archive "$triplet" "$dep")"
    local out="$ROOT/lib/build/native/$out_name"
    local java_home="${JAVA_HOME:-}"

    if [[ -z "$java_home" ]]; then
        java_home="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
    fi
    if [[ ! -f "$java_home/include/jni.h" ]]; then
        echo "JNI headers not found under JAVA_HOME=$java_home" >&2
        exit 1
    fi

    mkdir -p "$out"
    "$cc" -std=c11 -O2 -fPIC -shared -DWASMTIME_KMP_JNI \
        -I"$dep/include" -I"$NATIVE_DIR" \
        -I"$java_home/include" -I"$java_home/include/linux" \
        "$NATIVE_DIR/wasmtime_kmp.c" "$NATIVE_DIR/wasi_lite.c" "$archive" \
        "${linker_flags[@]}" \
        -lpthread -ldl -lm \
        -o "$out/libwasmtime_kmp.so"

    if [[ "${WASMTIME_KEEP_SYMBOLS:-0}" != "1" && -x "$strip_tool" ]]; then
        "$strip_tool" --strip-unneeded "$out/libwasmtime_kmp.so"
    fi
}

build_jvm_linux_x64() {
    local cc="$(command -v cc)"
    local strip_tool="$(command -v strip || true)"
    local linker_flags=("-Wl,--gc-sections")
    if command -v clang >/dev/null 2>&1 && command -v ld.lld >/dev/null 2>&1; then
        cc="$(command -v clang)"
        linker_flags+=("-fuse-ld=lld" "-Wl,--icf=all")
    fi
    build_jvm_linux x86_64-linux jvmLinuxX64 "$cc" "$strip_tool" "${linker_flags[@]}"
}

build_jvm_linux_arm64() {
    build_jvm_linux aarch64-linux jvmLinuxArm64 \
        "$(find_linux_arm64_tool gcc)" "$(find_linux_arm64_tool strip)" \
        "-Wl,--gc-sections"
}

case "${1:-}" in
    linux-x64)
        build_linux_x64
        ;;
    linux-arm64)
        build_linux_arm64
        ;;
    windows-x64)
        build_windows_x64
        ;;
    macos-x64)
        build_macos_x64
        ;;
    macos-arm64)
        build_macos_arm64
        ;;
    ios-arm64)
        build_ios_arm64
        ;;
    android-arm64)
        build_android_abi aarch64-android arm64-v8a aarch64-linux-android24-clang
        ;;
    android-x86_64)
        build_android_abi x86_64-android x86_64 x86_64-linux-android24-clang
        ;;
    android-all)
        "$0" android-arm64
        "$0" android-x86_64
        ;;
    jvm-linux-x64)
        build_jvm_linux_x64
        ;;
    jvm-linux-arm64)
        build_jvm_linux_arm64
        ;;
    jvm-linux-all)
        build_jvm_linux_x64
        build_jvm_linux_arm64
        ;;
    *)
        echo "usage: $0 {linux-x64|linux-arm64|windows-x64|macos-x64|macos-arm64|ios-arm64|android-arm64|android-x86_64|android-all|jvm-linux-x64|jvm-linux-arm64|jvm-linux-all}" >&2
        exit 2
        ;;
esac
