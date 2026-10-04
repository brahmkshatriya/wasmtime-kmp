#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import xml.etree.ElementTree as ElementTree
import zipfile
from pathlib import Path

BASE_GROUP = "dev.brahmkshatriya.wasmtime"
LIB_TARGETS = (
    "android", "jvm", "wasm-js", "linuxx64", "linuxarm64", "mingwx64",
    "macosx64", "macosarm64", "iosarm64",
)
COORDINATES = {
    BASE_GROUP: {
        "lib",
        *(f"lib-{target}" for target in LIB_TARGETS),
        "guest-runtime",
        "guest-runtime-wasm-wasi",
        "wasmtime-kmp-gradle-plugin",
    },
    f"{BASE_GROUP}.host": {"dev.brahmkshatriya.wasmtime.host.gradle.plugin"},
    f"{BASE_GROUP}.extension": {"dev.brahmkshatriya.wasmtime.extension.gradle.plugin"},
}
POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def fail(message: str) -> None:
    raise SystemExit(message)


def coordinate_dir(repository: Path, group: str, artifact: str, version: str) -> Path:
    return repository.joinpath(*group.split("."), artifact, version)


def xml_text(root: ElementTree.Element, path: str) -> str:
    node = root.find(path, POM_NS)
    return node.text.strip() if node is not None and node.text else ""


def validate_pom(path: Path, group: str, artifact: str, version: str) -> None:
    try:
        root = ElementTree.parse(path).getroot()
    except ElementTree.ParseError as error:
        fail(f"Invalid POM {path}: {error}")
    if (
        xml_text(root, "m:groupId") != group
        or xml_text(root, "m:artifactId") != artifact
        or xml_text(root, "m:version") != version
    ):
        fail(f"Wrong POM coordinates in {path}")
    required = (
        "m:name", "m:description", "m:url", "m:licenses/m:license/m:name",
        "m:licenses/m:license/m:url", "m:developers/m:developer/m:name",
        "m:scm/m:url", "m:scm/m:connection",
    )
    missing = [query for query in required if not xml_text(root, query)]
    if missing:
        fail(f"{path} is missing Maven Central metadata: {missing}")
    text = path.read_text(encoding="utf-8")
    if "SNAPSHOT" in text.upper():
        fail(f"SNAPSHOT reference leaked into {path}")


def validate_module(path: Path, version: str) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        fail(f"Invalid Gradle module metadata {path}: {error}")
    if "SNAPSHOT" in path.read_text(encoding="utf-8").upper():
        fail(f"SNAPSHOT reference leaked into {path}")
    component = data.get("component", {})
    if component.get("version") != version:
        fail(f"Wrong module metadata version in {path}: {component.get('version')}")
    variants = data.get("variants")
    if not isinstance(variants, list) or not variants:
        fail(f"No variants in {path}")
    return data


def available_at_modules(data: dict) -> set[tuple[str, str, str]]:
    result: set[tuple[str, str, str]] = set()
    for variant in data.get("variants", []):
        if not isinstance(variant, dict):
            continue
        available = variant.get("available-at")
        if isinstance(available, dict):
            result.add((str(available.get("group", "")), str(available.get("module", "")), str(available.get("version", ""))))
    return result


def require_zip_entries(path: Path, entries: set[str]) -> None:
    try:
        with zipfile.ZipFile(path) as archive:
            names = set(archive.namelist())
    except zipfile.BadZipFile as error:
        fail(f"Invalid ZIP/JAR/AAR {path}: {error}")
    missing = entries - names
    if missing:
        fail(f"{path} is missing embedded payloads: {sorted(missing)}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Verify the merged Wasmtime KMP Maven repository.")
    parser.add_argument("repository", type=Path)
    parser.add_argument("version")
    parser.add_argument("--allow-missing-apple", action="store_true")
    args = parser.parse_args()

    repository = args.repository.expanduser().resolve()
    version = args.version
    if not repository.is_dir():
        fail(f"Repository does not exist: {repository}")
    if "SNAPSHOT" in version.upper():
        fail(f"Release repository version must not be a SNAPSHOT: {version}")

    expected = {(group, artifact) for group, artifacts in COORDINATES.items() for artifact in artifacts}
    if args.allow_missing_apple:
        expected -= {(BASE_GROUP, f"lib-{target}") for target in ("macosx64", "macosarm64", "iosarm64")}

    actual: set[tuple[str, str]] = set()
    base = repository / "dev" / "brahmkshatriya"
    if not base.is_dir():
        fail(f"Missing dev.brahmkshatriya repository root: {base}")

    all_poms = sorted(base.rglob("*.pom"))
    stale_poms = [pom for pom in all_poms if pom.parent.name != version]
    if stale_poms:
        fail(
            "Repository contains POMs outside the requested version "
            f"{version}: {[str(path.relative_to(repository)) for path in stale_poms[:20]]}"
        )

    for pom in base.rglob(f"*/{version}/*.pom"):
        version_dir = pom.parent
        artifact_dir = version_dir.parent
        group_path = artifact_dir.parent.relative_to(repository)
        group = ".".join(group_path.parts)
        actual.add((group, artifact_dir.name))

    if actual != expected:
        fail(f"Published coordinate set mismatch. Missing={sorted(expected - actual)}, extra={sorted(actual - expected)}")

    checked_files = 0
    modules: dict[tuple[str, str], dict] = {}
    for group, artifact in sorted(expected):
        version_dir = coordinate_dir(repository, group, artifact, version)
        if not version_dir.is_dir():
            fail(f"Missing version directory: {version_dir}")
        stem = f"{artifact}-{version}"
        pom = version_dir / f"{stem}.pom"
        if not pom.is_file():
            fail(f"Missing POM: {pom}")
        validate_pom(pom, group, artifact, version)

        is_marker = artifact.endswith(".gradle.plugin")
        required = {f"{stem}.pom"}
        if not is_marker:
            required |= {f"{stem}-sources.jar", f"{stem}-javadoc.jar"}
        if artifact == "lib-android":
            required.add(f"{stem}.aar")
        elif artifact in {"lib-wasm-js", "lib-linuxx64", "lib-linuxarm64", "lib-mingwx64", "lib-macosx64", "lib-macosarm64", "lib-iosarm64", "guest-runtime-wasm-wasi"}:
            required.add(f"{stem}.klib")
        elif not is_marker:
            required.add(f"{stem}.jar")

        present = {path.name for path in version_dir.iterdir() if path.is_file()}
        missing = required - present
        if missing:
            fail(f"Missing required artifacts for {group}:{artifact}: {sorted(missing)}")

        module_path = version_dir / f"{stem}.module"
        if module_path.is_file():
            modules[(group, artifact)] = validate_module(module_path, version)
        elif not is_marker:
            fail(f"Missing Gradle module metadata: {module_path}")

        for path in version_dir.iterdir():
            if not path.is_file():
                continue
            checked_files += 1
            if path.stat().st_size <= 0:
                fail(f"Empty artifact: {path}")
            if "SNAPSHOT" in path.name.upper():
                fail(f"SNAPSHOT artifact leaked into repository: {path}")

    lib_root = modules[(BASE_GROUP, "lib")]
    # Even a non-Apple shard must publish the complete root topology. The physical Apple
    # leaf artifacts arrive from the macOS shard, but lib.module must already reference them.
    expected_lib_targets = {
        (BASE_GROUP, f"lib-{target}", version)
        for target in LIB_TARGETS
    }
    root_targets = available_at_modules(lib_root)
    missing_targets = expected_lib_targets - root_targets
    if missing_targets:
        fail(f"lib root metadata is missing available-at targets: {sorted(missing_targets)}")

    guest_root = modules[(BASE_GROUP, "guest-runtime")]
    if (BASE_GROUP, "guest-runtime-wasm-wasi", version) not in available_at_modules(guest_root):
        fail("guest-runtime root metadata does not point to guest-runtime-wasm-wasi")

    wasi = modules[(BASE_GROUP, "guest-runtime-wasm-wasi")]
    if not any(
        isinstance(variant, dict)
        and isinstance(variant.get("attributes"), dict)
        and variant["attributes"].get("org.jetbrains.kotlin.platform.type") == "wasm"
        and variant["attributes"].get("org.jetbrains.kotlin.wasm.target") == "wasi"
        for variant in wasi.get("variants", [])
    ):
        fail("guest-runtime-wasm-wasi does not expose org.jetbrains.kotlin.wasm.target=wasi")

    android_dir = coordinate_dir(repository, BASE_GROUP, "lib-android", version)
    require_zip_entries(
        android_dir / f"lib-android-{version}.aar",
        {"jni/arm64-v8a/libwasmtime_kmp.so", "jni/x86_64/libwasmtime_kmp.so"},
    )

    jvm_dir = coordinate_dir(repository, BASE_GROUP, "lib-jvm", version)
    require_zip_entries(
        jvm_dir / f"lib-jvm-{version}-linux-x64.jar",
        {"native/linux-x64/libwasmtime_kmp.so"},
    )
    require_zip_entries(
        jvm_dir / f"lib-jvm-{version}-linux-arm64.jar",
        {"native/linux-arm64/libwasmtime_kmp.so"},
    )

    plugin_pom = coordinate_dir(repository, BASE_GROUP, "wasmtime-kmp-gradle-plugin", version) / f"wasmtime-kmp-gradle-plugin-{version}.pom"
    plugin_root = ElementTree.parse(plugin_pom).getroot()
    if xml_text(plugin_root, "m:version") != version:
        fail("Gradle plugin implementation version is not aligned with the SDK version")

    print(f"Verified {len(expected)} Wasmtime publications ({checked_files} files) at version {version}")


if __name__ == "__main__":
    main()
