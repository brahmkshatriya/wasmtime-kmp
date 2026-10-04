#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repository="${1:?Usage: verify-wasmtime-consumer.sh REPOSITORY VERSION}"
version="${2:?Usage: verify-wasmtime-consumer.sh REPOSITORY VERSION}"
repository="$(realpath "$repository")"
work="$(mktemp -d "${TMPDIR:-/tmp}/wasmtime-consumer.XXXXXX")"
trap 'rm -rf "$work"' EXIT

cat > "$work/settings.gradle.kts" <<EOF
pluginManagement {
    repositories {
        maven { url = uri("$repository") }
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri("$repository") } }
            filter {
                includeGroup("dev.brahmkshatriya.wasmtime")
                includeGroup("dev.brahmkshatriya.wasmtime.host")
                includeGroup("dev.brahmkshatriya.wasmtime.extension")
            }
        }
        mavenCentral()
    }
}
rootProject.name = "wasmtime-host-consumer"
EOF
cat > "$work/build.gradle.kts" <<EOF
plugins {
    kotlin("multiplatform") version "2.4.20"
}

kotlin {
    linuxX64 {
        binaries.executable()
    }
    sourceSets.commonMain.dependencies {
        implementation("dev.brahmkshatriya.wasmtime:lib:$version")
    }
}
EOF
mkdir -p "$work/src/linuxX64Main/kotlin"
cat > "$work/src/linuxX64Main/kotlin/Main.kt" <<'EOF'
import dev.brahmkshatriya.wasmtime.WasmtimeLimits

fun main() {
    check(WasmtimeLimits().maxMemoryBytes > 0)
}
EOF

"$root/gradlew" -p "$work" --no-daemon --stacktrace linkReleaseExecutableLinuxX64

echo 'plugins { id("dev.brahmkshatriya.wasmtime.extension") version "'"$version"'" }' > "$work/build.gradle.kts"
"$root/gradlew" -p "$work" --no-daemon --stacktrace dependencies --configuration wasmWasiMainCompileOnly > "$work/plugin-dependencies.txt"
grep -F "dev.brahmkshatriya.wasmtime:guest-runtime:$version" "$work/plugin-dependencies.txt" >/dev/null
if grep -F "dev.brahmkshatriya.wasmtime:guest-runtime:$version FAILED" "$work/plugin-dependencies.txt" >/dev/null; then
    echo "Published Gradle plugin could not resolve guest-runtime:$version" >&2
    exit 1
fi

echo "Verified external Linux consumer link and Gradle plugin guest-runtime resolution for $version"
