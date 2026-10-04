from pathlib import Path
import struct, sys
out=Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)

def u(n):
    b=[]
    while True:
        x=n&0x7f; n >>= 7
        if n: b.append(x|0x80)
        else: b.append(x); return bytes(b)

def s(n,bits=64):
    outb=[]
    while True:
        byte=n&0x7f
        n2=n>>7
        sign=byte&0x40
        done=(n2==0 and not sign) or (n2==-1 and sign)
        outb.append(byte if done else byte|0x80)
        n=n2
        if done:return bytes(outb)

def vec(items): return u(len(items))+b''.join(items)
def name(x): x=x.encode(); return u(len(x))+x
def sec(i,p): return bytes([i])+u(len(p))+p
def ftype(params, results=[0x7f]): return bytes([0x60])+vec([bytes([x]) for x in params])+vec([bytes([x]) for x in results])
def imp(mod,field,t): return name(mod)+name(field)+b'\x00'+u(t)
def exp(n,k,i): return name(n)+bytes([k])+u(i)
def body(code):
    b=b'\x00'+code+b'\x0b'
    return u(len(b))+b
def module(*sections): return b'\0asm\x01\0\0\0'+b''.join(sections)
def iconst(n): return b'\x41'+s(n,32)
def lconst(n): return b'\x42'+s(n,64)
def call(i): return b'\x10'+u(i)
def mem(minpages): return sec(5, vec([b'\x00'+u(minpages)]))
def data_at(offset,data): return sec(11, vec([b'\x00'+iconst(offset)+b'\x0b'+u(len(data))+data]))
def standard_exports(funcidx): return sec(7, vec([exp('memory',2,0), exp('run',0,funcidx)]))

def path_open_module(path, include_trailing_nul=False, create=False):
    types=sec(1,vec([
        ftype([0x7f,0x7f,0x7f,0x7f,0x7f,0x7e,0x7e,0x7f,0x7f]),
        ftype([0x7f,0x7f]),
    ]))
    imports=sec(2,vec([imp('wasi_snapshot_preview1','path_open',0)]))
    funcs=sec(3,vec([u(1)]))
    encoded=path.encode() + (b'\0' if include_trailing_nul else b'')
    code=(iconst(3)+iconst(0)+iconst(0)+iconst(len(encoded))+iconst(1 if create else 0)+lconst(2)+lconst(0)+iconst(0)+iconst(64)+call(0))
    return module(types,imports,funcs,mem(1),standard_exports(1),sec(10,vec([body(code)])),data_at(0,encoded))

(out/'path_traversal.wasm').write_bytes(path_open_module('../escape'))
(out/'path_symlink.wasm').write_bytes(path_open_module('link'))
(out/'path_trailing_nul.wasm').write_bytes(path_open_module('nul-ok', include_trailing_nul=True, create=True))

# poll_oneoff with a 2 second relative timer.
types=sec(1,vec([ftype([0x7f]*4), ftype([0x7f,0x7f])]))
imports=sec(2,vec([imp('wasi_snapshot_preview1','poll_oneoff',0)]))
funcs=sec(3,vec([u(1)]))
sub=bytearray(48); sub[0:8]=(123).to_bytes(8,'little'); sub[8]=0; sub[16:20]=(1).to_bytes(4,'little'); sub[24:32]=(2_000_000_000).to_bytes(8,'little')
code=iconst(0)+iconst(64)+iconst(1)+iconst(96)+call(0)
(out/'poll_too_long.wasm').write_bytes(module(types,imports,funcs,mem(1),standard_exports(1),sec(10,vec([body(code)])),data_at(0,bytes(sub))))

# random_get asks for 2 MiB in one host call.
types=sec(1,vec([ftype([0x7f,0x7f]), ftype([0x7f,0x7f])]))
imports=sec(2,vec([imp('wasi_snapshot_preview1','random_get',0)]))
funcs=sec(3,vec([u(1)]))
code=iconst(0)+iconst(2*1024*1024)+call(0)
(out/'host_call_too_large.wasm').write_bytes(module(types,imports,funcs,mem(40),standard_exports(1),sec(10,vec([body(code)]))))

# Create x, then attempt to write 2048 bytes; storage quota test sets maxBytes=1024.
types=sec(1,vec([
    ftype([0x7f,0x7f,0x7f,0x7f,0x7f,0x7e,0x7e,0x7f,0x7f]),
    ftype([0x7f]*4),
    ftype([0x7f,0x7f]),
]))
imports=sec(2,vec([imp('wasi_snapshot_preview1','path_open',0),imp('wasi_snapshot_preview1','fd_write',1)]))
funcs=sec(3,vec([u(2)]))
store=lambda addr,val: iconst(addr)+iconst(val)+b'\x36\x02\x00'
load=lambda addr: iconst(addr)+b'\x28\x02\x00'
code=(iconst(3)+iconst(0)+iconst(0)+iconst(1)+iconst(1)+lconst(64)+lconst(0)+iconst(0)+iconst(64)+call(0)+b'\x1a'
      +store(80,128)+store(84,2048)+load(64)+iconst(80)+iconst(1)+iconst(88)+call(1))
(out/'storage_write_quota.wasm').write_bytes(module(types,imports,funcs,mem(1),standard_exports(2),sec(10,vec([body(code)])),data_at(0,b'x')))

# Import-only module forces lightweight WASI construction during instantiation.
types=sec(1,vec([ftype([0x7f,0x7f])]))
imports=sec(2,vec([imp('wasi_snapshot_preview1','random_get',0)]))
(out/'import_only.wasm').write_bytes(module(types,imports))

# Initial table of 100 entries; test limit is 10.
table=sec(4,vec([b'\x70\x00'+u(100)]))
(out/'table_100.wasm').write_bytes(module(table))
# Initial memory of 2 pages; test limit is one page.
(out/'memory_2_pages.wasm').write_bytes(module(mem(2)))

# (i32,i32)->i32 add for lifetime/cache tests.
types=sec(1,vec([ftype([0x7f,0x7f])]))
funcs=sec(3,vec([u(0)]))
exports=sec(7,vec([exp('add',0,0)]))
code=b'\x20\x00\x20\x01\x6a'
(out/'add.wasm').write_bytes(module(types,funcs,exports,sec(10,vec([body(code)]))))
