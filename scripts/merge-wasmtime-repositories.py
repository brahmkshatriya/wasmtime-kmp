#!/usr/bin/env python3
from __future__ import annotations

import argparse
import filecmp
import shutil
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description="Merge Wasmtime Maven repository shards without hiding conflicts.")
    parser.add_argument("output", type=Path)
    parser.add_argument("inputs", nargs="+", type=Path)
    args = parser.parse_args()

    output = args.output.expanduser().resolve()
    inputs = [path.expanduser().resolve() for path in args.inputs]
    for source in inputs:
        if not source.is_dir():
            raise SystemExit(f"Repository shard does not exist: {source}")
        if source == output or source in output.parents or output in source.parents:
            raise SystemExit(f"Repository shard and output must be separate: {source} vs {output}")

    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)

    copied = 0
    duplicates = 0
    for source in inputs:
        for path in sorted(source.rglob("*")):
            if not path.is_file():
                continue
            relative = path.relative_to(source)
            destination = output / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            if destination.exists():
                if not filecmp.cmp(path, destination, shallow=False):
                    raise SystemExit(f"Conflicting repository files: {relative}")
                duplicates += 1
                continue
            shutil.copy2(path, destination)
            copied += 1

    print(f"Merged {len(inputs)} repositories into {output}: {copied} files, {duplicates} identical duplicates")


if __name__ == "__main__":
    main()
