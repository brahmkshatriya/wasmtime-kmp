#!/usr/bin/env bash
set -euo pipefail

upper_ascii() {
    printf '%s' "$1" | tr '[:lower:]' '[:upper:]'
}

NATIVE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION="${WASMTIME_VERSION:-49.0.1}"
RUST_VERSION="${WASMTIME_RUST_VERSION:-1.96.0}"
COMPILER_OPT_LEVEL="${WASMTIME_COMPILER_OPT_LEVEL:-s}"
DEPS="$NATIVE_DIR/.deps"
SOURCE="$DEPS/wasmtime-src-v${VERSION}"
SOURCE_ARCHIVE="$DEPS/wasmtime-v${VERSION}-src.tar.gz"
SLIM="$DEPS/slim"
PRIVATE_RUSTUP="$DEPS/rustup"
PRIVATE_CARGO="$DEPS/cargo"
RUSTUP_INIT="$DEPS/rustup-init"
PROFILE_MARKER_BEGIN="# BEGIN wasmtime-kmp compact compiler profile"
PROFILE_MARKER_END="# END wasmtime-kmp compact compiler profile"

host_jobs() {
    if command -v nproc >/dev/null 2>&1; then nproc; return; fi
    if command -v sysctl >/dev/null 2>&1; then sysctl -n hw.ncpu 2>/dev/null && return; fi
    printf '4\n'
}

file_sha256() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'; return; fi
    shasum -a 256 "$1" | awk '{print $1}'
}

file_size() {
    if stat -c %s "$1" >/dev/null 2>&1; then stat -c %s "$1"; return; fi
    stat -f %z "$1"
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
    local override_var="WASMTIME_WINDOWS_$(upper_ascii "$tool")"
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

create_windows_linker() {
    if is_windows_host; then
        find_windows_native_tool gcc
        return
    fi
    local mingw="$(find_konan_dependency 'msys2-mingw-w64-x86_64-*')"
    local llvm="$(find_konan_dependency 'llvm-*-x86_64-linux-essentials-*')"
    local clang="$llvm/bin/clang"
    [[ -x "$clang" ]] || { echo "Kotlin/Native clang not found: $clang" >&2; exit 1; }
    local wrapper="$DEPS/toolchains/x86_64-windows-gnu-clang"
    mkdir -p "$(dirname "$wrapper")"
    cat > "$wrapper" <<EOF
#!/usr/bin/env bash
exec "$clang" --target=x86_64-w64-windows-gnu --sysroot="$mingw" \
  -isystem "$mingw/x86_64-w64-mingw32/include" \
  -L"$mingw/x86_64-w64-mingw32/lib" -L"$mingw/lib" "\$@"
EOF
    chmod +x "$wrapper"
    local dlltool="$DEPS/toolchains/x86_64-w64-mingw32-dlltool"
    local llvm_dlltool="$(command -v llvm-dlltool || true)"
    [[ -n "$llvm_dlltool" ]] || { echo "llvm-dlltool is required for Windows cross builds" >&2; exit 1; }
    cat > "$dlltool" <<EOF
#!/usr/bin/env bash
exec "$llvm_dlltool" "\$@"
EOF
    chmod +x "$dlltool"
    printf '%s\n' "$wrapper"
}

create_apple_linker() {
    local sdk="$1"
    local arch="$2"
    [[ "$(uname -s)" == "Darwin" ]] || { echo "$sdk builds require macOS/Xcode" >&2; exit 1; }
    local wrapper="$DEPS/toolchains/${sdk}-${arch}-clang"
    mkdir -p "$(dirname "$wrapper")"
    cat > "$wrapper" <<EOF
#!/usr/bin/env bash
exec xcrun --sdk "$sdk" clang -arch "$arch" "\$@"
EOF
    chmod +x "$wrapper"
    printf '%s\n' "$wrapper"
}

require_tool() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "Required tool not found: $1" >&2
        exit 1
    fi
}

ensure_rust_toolchain() {
    if is_windows_host; then
        require_tool cargo
        require_tool rustup
        return
    fi

    mkdir -p "$DEPS"
    if [[ ! -x "$PRIVATE_CARGO/bin/cargo" || ! -x "$PRIVATE_CARGO/bin/rustup" ]]; then
        require_tool curl
        echo "Bootstrapping private Rust ${RUST_VERSION} toolchain for compact Wasmtime..." >&2
        curl -fsSL https://sh.rustup.rs -o "$RUSTUP_INIT"
        chmod +x "$RUSTUP_INIT"
        RUSTUP_HOME="$PRIVATE_RUSTUP" CARGO_HOME="$PRIVATE_CARGO" \
            "$RUSTUP_INIT" -y --profile minimal --default-toolchain "$RUST_VERSION" --no-modify-path
    fi

    export RUSTUP_HOME="$PRIVATE_RUSTUP"
    export CARGO_HOME="$PRIVATE_CARGO"
    export PATH="$PRIVATE_CARGO/bin:$PATH"
}

prepare_source() {
    case "$COMPILER_OPT_LEVEL" in
        3|s|z) ;;
        *)
            echo "WASMTIME_COMPILER_OPT_LEVEL must be one of: 3, s, z" >&2
            exit 2
            ;;
    esac

    ensure_rust_toolchain
    require_tool cargo
    require_tool rustc
    require_tool rustup
    require_tool cmake
    require_tool curl
    require_tool python3
    require_tool tar

    # Pin the compiler used for generated archives. This also makes a machine
    # with another global default toolchain deterministic.
    rustup toolchain install "$RUST_VERSION" --profile minimal >/dev/null
    export RUSTUP_TOOLCHAIN="$RUST_VERSION"

    if [[ ! -f "$SOURCE/Cargo.toml" ]]; then
        mkdir -p "$DEPS"
        if [[ ! -f "$SOURCE_ARCHIVE" ]]; then
            curl -fL --retry 3 --retry-delay 1 \
                -o "$SOURCE_ARCHIVE" \
                "https://github.com/bytecodealliance/wasmtime/archive/refs/tags/v${VERSION}.tar.gz"
        fi
        rm -rf "$SOURCE"
        mkdir -p "$SOURCE"
        tar -xzf "$SOURCE_ARCHIVE" -C "$SOURCE" --strip-components=1
    fi

    # Cranelift is needed only while translating Wasm into native code. Keep
    # Wasmtime's execution/runtime crates at O3, but size-optimize the compiler
    # implementation itself. `s` is the default compromise: it preserves most
    # of the size reduction without the much larger cold-compile penalty seen
    # with `z`. This does not change the optimization level of generated guest
    # machine code.
    python3 - "$SOURCE/Cargo.toml" "$PROFILE_MARKER_BEGIN" "$PROFILE_MARKER_END" "$COMPILER_OPT_LEVEL" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
begin = sys.argv[2]
end = sys.argv[3]
opt_level = sys.argv[4]
text = path.read_text()
if begin in text:
    prefix, rest = text.split(begin, 1)
    _, suffix = rest.split(end, 1)
    text = prefix.rstrip() + "\n" + suffix.lstrip("\n")

block = f'''\n{begin}
[profile.release.package.cranelift-codegen]
opt-level = "{opt_level}"
[profile.release.package.cranelift-frontend]
opt-level = "{opt_level}"
[profile.release.package.cranelift-native]
opt-level = "{opt_level}"
[profile.release.package.cranelift-assembler-x64]
opt-level = "{opt_level}"
[profile.release.package.wasmtime-internal-cranelift]
opt-level = "{opt_level}"
[profile.release.package.regalloc2]
opt-level = "{opt_level}"
{end}
'''
path.write_text(text.rstrip() + "\n" + block)
PY
}

ensure_rust_target() {
    local target="$1"
    if ! rustup target list --installed --toolchain "$RUST_VERSION" | grep -qx "$target"; then
        rustup target add "$target" --toolchain "$RUST_VERSION"
    fi
}

find_linux_arm64_tool() {
    local tool="$1"
    local override_var="WASMTIME_LINUX_ARM64_$(upper_ascii "$tool")"
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

build_target() {
    local triplet="$1"
    local rust_target="$2"
    local target_cc="${3:-}"
    local target_ar="${4:-}"
    local build="$DEPS/slim-build/$triplet"
    local cargo_target="$DEPS/slim-target/$triplet"
    local out="$SLIM/$triplet/lib"
    local archive="$out/libwasmtime.a"
    local stamp="$out/build.stamp"
    local script_hash
    script_hash="$(file_sha256 "$0")"
    local fingerprint="wasmtime=${VERSION};rust=${RUST_VERSION};compiler_opt=${COMPILER_OPT_LEVEL};target=${rust_target};script=${script_hash}"

    if [[ "${WASMTIME_REBUILD_SLIM:-0}" != "1" && -f "$archive" && -f "$stamp" ]] \
        && [[ "$(cat "$stamp")" == "$fingerprint" ]]; then
        echo "compact Wasmtime $triplet is current: $(file_size "$archive") bytes"
        return
    fi

    ensure_rust_target "$rust_target"
    rm -rf "$build"
    mkdir -p "$build" "$out"

    export CARGO_TARGET_DIR="$cargo_target"
    export PATH="$DEPS/toolchains:$PATH"
    # These settings optimize the host runtime while retaining Cranelift's
    # normal optimized guest-code generation. LTO only affects Wasmtime itself.
    export CARGO_PROFILE_RELEASE_LTO=fat
    export CARGO_PROFILE_RELEASE_CODEGEN_UNITS=1
    export CARGO_PROFILE_RELEASE_OPT_LEVEL=3

    if [[ -n "$target_cc" ]]; then
        if [[ ! -x "$target_cc" ]]; then
            echo "Target compiler not found: $target_cc" >&2
            exit 1
        fi
        local env_suffix="${rust_target//-/_}"
        local env_suffix_upper
        env_suffix_upper="$(upper_ascii "$env_suffix")"
        export "CARGO_TARGET_${env_suffix_upper}_LINKER=$target_cc"
        export "CC_${env_suffix}=$target_cc"
        if [[ -n "$target_ar" ]]; then
            [[ -x "$target_ar" ]] || { echo "Target archiver not found: $target_ar" >&2; exit 1; }
            export "AR_${env_suffix}=$target_ar"
        fi
    fi

    local cmake_toolchain_args=()
    if [[ -n "$target_cc" ]]; then cmake_toolchain_args+=("-DCMAKE_C_COMPILER=$target_cc"); fi
    if [[ -n "$target_ar" ]]; then cmake_toolchain_args+=("-DCMAKE_AR=$target_ar"); fi

    cmake -S "$SOURCE/crates/c-api" -B "$build" \
        "${cmake_toolchain_args[@]}" \
        -DCMAKE_BUILD_TYPE=Release \
        -DWASMTIME_TARGET="$rust_target" \
        -DWASMTIME_DISABLE_ALL_FEATURES=ON \
        -DWASMTIME_FEATURE_CRANELIFT="${WASMTIME_TARGET_CRANELIFT:-ON}" \
        -DWASMTIME_FEATURE_PULLEY="${WASMTIME_TARGET_PULLEY:-OFF}" \
        -DWASMTIME_FEATURE_ASYNC=ON \
        -DWASMTIME_FEATURE_COMPONENT_MODEL=OFF \
        -DWASMTIME_FEATURE_COMPONENT_MODEL_ASYNC=OFF \
        -DWASMTIME_FEATURE_GC=ON \
        -DWASMTIME_FEATURE_GC_DRC=ON \
        -DWASMTIME_FEATURE_THREADS=OFF \
        -DWASMTIME_FEATURE_PARALLEL_COMPILATION=ON \
        -DWASMTIME_FEATURE_DISABLE_LOGGING=ON \
        ${WASMTIME_TARGET_CMAKE_ARGS:-}

    cmake --build "$build" -j "${WASMTIME_BUILD_JOBS:-$(host_jobs)}"
    cp "$cargo_target/$rust_target/release/libwasmtime.a" "$archive"
    printf '%s' "$fingerprint" > "$stamp"
    echo "compact Wasmtime $triplet: $(file_size "$archive") bytes"
}

prepare_source

case "${1:-all}" in
    linux-x64)
        build_target x86_64-linux x86_64-unknown-linux-gnu
        ;;
    linux-arm64)
        build_target aarch64-linux aarch64-unknown-linux-gnu \
            "$(find_linux_arm64_tool gcc)" "$(find_linux_arm64_tool ar)"
        ;;
    windows-x64)
        linker="$(create_windows_linker)"
        if is_windows_host; then
            archiver="$(find_windows_native_tool ar)"
        else
            llvm="$(find_konan_dependency 'llvm-*-x86_64-linux-essentials-*')"
            archiver="$llvm/bin/llvm-ar"
        fi
        WASMTIME_TARGET_CMAKE_ARGS="-DCMAKE_SYSTEM_NAME=Windows" \
            build_target x86_64-windows-gnu x86_64-pc-windows-gnu \
            "$linker" "$archiver"
        ;;
    macos-x64)
        linker="$(create_apple_linker macosx x86_64)"
        WASMTIME_TARGET_CMAKE_ARGS="-DCMAKE_SYSTEM_NAME=Darwin -DCMAKE_OSX_SYSROOT=macosx -DCMAKE_OSX_ARCHITECTURES=x86_64" \
            build_target x86_64-macos x86_64-apple-darwin "$linker"
        ;;
    macos-arm64)
        linker="$(create_apple_linker macosx arm64)"
        WASMTIME_TARGET_CMAKE_ARGS="-DCMAKE_SYSTEM_NAME=Darwin -DCMAKE_OSX_SYSROOT=macosx -DCMAKE_OSX_ARCHITECTURES=arm64" \
            build_target aarch64-macos aarch64-apple-darwin "$linker"
        ;;
    ios-arm64)
        linker="$(create_apple_linker iphoneos arm64)"
        # Pulley execution still needs the Cranelift feature to translate Wasm
        # into Pulley bytecode. The C bridge forces target=pulley64, so generated
        # guest text is interpreter bytecode rather than executable native code.
        WASMTIME_TARGET_CRANELIFT=ON WASMTIME_TARGET_PULLEY=ON \
        WASMTIME_TARGET_CMAKE_ARGS="-DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT=iphoneos -DCMAKE_OSX_ARCHITECTURES=arm64" \
            build_target aarch64-ios aarch64-apple-ios "$linker"
        ;;
    android-arm64)
        ndk="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/29.0.14206865}"
        toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
        build_target aarch64-android aarch64-linux-android \
            "$toolchain/aarch64-linux-android24-clang" "$toolchain/llvm-ar"
        ;;
    android-x86_64)
        ndk="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/29.0.14206865}"
        toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
        build_target x86_64-android x86_64-linux-android \
            "$toolchain/x86_64-linux-android24-clang" "$toolchain/llvm-ar"
        ;;
    android-all)
        "$0" android-arm64
        "$0" android-x86_64
        ;;
    apple-all)
        "$0" macos-x64
        "$0" macos-arm64
        "$0" ios-arm64
        ;;
    desktop-all)
        "$0" linux-x64
        "$0" linux-arm64
        "$0" windows-x64
        if [[ "$(uname -s)" == "Darwin" ]]; then "$0" macos-x64; "$0" macos-arm64; fi
        ;;
    all)
        "$0" linux-x64
        "$0" linux-arm64
        "$0" windows-x64
        "$0" android-all
        if [[ "$(uname -s)" == "Darwin" ]]; then "$0" apple-all; fi
        ;;
    *)
        echo "usage: $0 {linux-x64|linux-arm64|windows-x64|macos-x64|macos-arm64|ios-arm64|android-arm64|android-x86_64|android-all|apple-all|desktop-all|all}" >&2
        exit 2
        ;;
esac
