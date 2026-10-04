#!/usr/bin/env python3
from pathlib import Path
import struct
import sys

OUT = Path(sys.argv[1])
OUT.mkdir(parents=True, exist_ok=True)

I32 = 0x7F
I64 = 0x7E
FUNCREF = 0x70


def uleb(value: int) -> bytes:
    assert value >= 0
    out = bytearray()
    while True:
        b = value & 0x7F
        value >>= 7
        if value:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def sleb32(value: int) -> bytes:
    out = bytearray()
    more = True
    while more:
        b = value & 0x7F
        value >>= 7
        sign = b & 0x40
        more = not ((value == 0 and not sign) or (value == -1 and sign))
        out.append(b | (0x80 if more else 0))
    return bytes(out)


def name(value: str) -> bytes:
    raw = value.encode()
    return uleb(len(raw)) + raw


def vec(items) -> bytes:
    items = list(items)
    return uleb(len(items)) + b''.join(items)


def section(section_id: int, payload: bytes) -> bytes:
    return bytes([section_id]) + uleb(len(payload)) + payload


def func_type(params, results=(I32,)) -> bytes:
    return b'\x60' + vec(bytes([x]) for x in params) + vec(bytes([x]) for x in results)


def module(*sections: bytes) -> bytes:
    return b'\x00asm\x01\x00\x00\x00' + b''.join(sections)


def type_section(types) -> bytes:
    return section(1, vec(types))


def import_func(module_name: str, field: str, type_index: int) -> bytes:
    return name(module_name) + name(field) + b'\x00' + uleb(type_index)


def import_section(imports) -> bytes:
    return section(2, vec(imports))


def function_section(type_indices) -> bytes:
    return section(3, vec(uleb(i) for i in type_indices))


def table_section(minimum: int = 0, maximum=None) -> bytes:
    if maximum is None:
        limits = b'\x00' + uleb(minimum)
    else:
        limits = b'\x01' + uleb(minimum) + uleb(maximum)
    return section(4, uleb(1) + bytes([FUNCREF]) + limits)


def memory_section(minimum: int = 1) -> bytes:
    return section(5, uleb(1) + b'\x00' + uleb(minimum))


def export(name_: str, kind: int, index: int) -> bytes:
    return name(name_) + bytes([kind]) + uleb(index)


def export_section(exports) -> bytes:
    return section(7, vec(exports))


def code_section(bodies) -> bytes:
    encoded = []
    for instructions in bodies:
        body = b'\x00' + instructions  # zero local groups
        encoded.append(uleb(len(body)) + body)
    return section(10, vec(encoded))


def data_section(data: bytes, offset: int = 0) -> bytes:
    expr = b'\x41' + sleb32(offset) + b'\x0b'
    segment = b'\x00' + expr + uleb(len(data)) + data
    return section(11, uleb(1) + segment)


def i32_const(v: int) -> bytes:
    return b'\x41' + sleb32(v)


def i64_const(v: int) -> bytes:
    # all test values are non-negative and fit signed 32 bits; same LEB routine works.
    return b'\x42' + sleb32(v)


def local_get(i: int) -> bytes:
    return b'\x20' + uleb(i)


def call(i: int) -> bytes:
    return b'\x10' + uleb(i)


# (i32, i32) -> i32, grows table by first argument.
table_grow = module(
    type_section([func_type([I32, I32])]),
    function_section([0]),
    table_section(),
    export_section([export('run', 0, 0)]),
    code_section([b'\xd0\x70' + local_get(0) + b'\xfc\x0f\x00\x0b']),
)
(OUT / 'table-grow.wasm').write_bytes(table_grow)

# Wasm-GC i32 array allocation. The store memory limiter also governs the GC heap.
gc_array = module(
    type_section([
        b'\x5e' + bytes([I32, 1]),  # mutable (array i32) type index 0
        func_type([I32, I32]),        # run type index 1
    ]),
    function_section([1]),
    export_section([export('run', 0, 0)]),
    code_section([local_get(0) + b'\xfb\x07\x00' + b'\xfb\x0f' + b'\x0b']),
)
(OUT / 'gc-array.wasm').write_bytes(gc_array)


# Infinite CPU loop; execution deadline must interrupt it even with fuel disabled.
infinite = module(
    type_section([func_type([I32, I32])]),
    function_section([0]),
    export_section([export('run', 0, 0)]),
    code_section([b'\x03\x40\x0c\x00\x0b\x00\x0b']),
)
(OUT / 'infinite.wasm').write_bytes(infinite)

# fd_write wrapper with a 16-byte payload and one iovec at address 0.
payload = b'hello\x1b[31mworld!'
assert len(payload) == 16
mem = bytearray(64)
mem[0:4] = struct.pack('<I', 16)
mem[4:8] = struct.pack('<I', len(payload))
mem[16:32] = payload
fd_write = module(
    type_section([
        func_type([I32, I32, I32, I32]),
        func_type([I32, I32]),
    ]),
    import_section([import_func('wasi_snapshot_preview1', 'fd_write', 0)]),
    function_section([1]),
    memory_section(),
    export_section([export('memory', 2, 0), export('run', 0, 1)]),
    code_section([i32_const(1) + i32_const(0) + i32_const(1) + i32_const(8) + call(0) + b'\x0b']),
    data_section(bytes(mem)),
)
(OUT / 'fd-write.wasm').write_bytes(fd_write)

# poll_oneoff with one relative 1-second clock subscription.
sub = bytearray(128)
sub[8] = 0  # clock event
sub[16:20] = struct.pack('<I', 1)  # monotonic
sub[24:32] = struct.pack('<Q', 1_000_000_000)
poll = module(
    type_section([
        func_type([I32, I32, I32, I32]),
        func_type([I32, I32]),
    ]),
    import_section([import_func('wasi_snapshot_preview1', 'poll_oneoff', 0)]),
    function_section([1]),
    memory_section(),
    export_section([export('memory', 2, 0), export('run', 0, 1)]),
    code_section([i32_const(0) + i32_const(64) + i32_const(1) + i32_const(112) + call(0) + b'\x0b']),
    data_section(bytes(sub)),
)
(OUT / 'poll.wasm').write_bytes(poll)


def path_open_module(path: bytes) -> bytes:
    return module(
        type_section([
            func_type([I32, I32, I32, I32, I32, I64, I64, I32, I32]),
            func_type([I32, I32]),
        ]),
        import_section([import_func('wasi_snapshot_preview1', 'path_open', 0)]),
        function_section([1]),
        memory_section(),
        export_section([export('memory', 2, 0), export('run', 0, 1)]),
        code_section([
            i32_const(3) + i32_const(0) + i32_const(0) + i32_const(len(path)) +
            i32_const(1) + i64_const(64) + i64_const(0) + i32_const(0) + i32_const(48) +
            call(0) + b'\x0b'
        ]),
        data_section(path),
    )

(OUT / 'path-traversal.wasm').write_bytes(path_open_module(b'../pwn'))
(OUT / 'path-symlink.wasm').write_bytes(path_open_module(b'link/pwn'))

# HTTP request_create asks for a 2 MiB request body.
http_create = module(
    type_section([
        func_type([I32, I32]),
        func_type([I32, I32]),
    ]),
    import_section([import_func('ktor_wasi', 'request_create', 0)]),
    function_section([1]),
    export_section([export('run', 0, 1)]),
    code_section([i32_const(1) + i32_const(2 * 1024 * 1024) + call(0) + b'\x0b']),
)
(OUT / 'http-request-create.wasm').write_bytes(http_create)

# HTTP request_execute with an immediate fake host response; host-side response cap should reject it.
http_execute = module(
    type_section([
        func_type([I32, I32]),       # request_create
        func_type([I32]),            # request_execute
        func_type([I32, I32]),       # run
    ]),
    import_section([
        import_func('ktor_wasi', 'request_create', 0),
        import_func('ktor_wasi', 'request_execute', 1),
    ]),
    function_section([2]),
    export_section([export('run', 0, 2)]),
    code_section([
        i32_const(1) + i32_const(0) + call(0) + b'\x1a' +
        i32_const(1) + call(1) + b'\x0b'
    ]),
)
(OUT / 'http-response.wasm').write_bytes(http_execute)
