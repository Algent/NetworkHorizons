"""Compression estimates over server-to-client frames of an offline-mode capture.

Sizes include the outer varint length prefix. Per-key mode attributes stream-deflate output to each packet key.
CPU numbers are Python's, only useful to compare strategies with each other.
"""
import argparse
import collections
import time
import zlib

from mcframes import CHUNK_IDS, S2C, key, load

try:
    from compression import zstd  # Python 3.14+
except ImportError:
    zstd = None


def vlen(n):
    c = 1
    while n >= 0x80:
        n >>= 7
        c += 1
    return c


def framed(n):
    return vlen(n) + n


class DeflateStream:
    def __init__(self, level):
        self.c = zlib.compressobj(level, zlib.DEFLATED, -15)

    def push(self, data):
        return len(self.c.compress(data) + self.c.flush(zlib.Z_SYNC_FLUSH)) - 4  # trailing 00 00 FF FF trimmed


class ZstdStream:
    def __init__(self, level, zdict=None):
        self.c = zstd.ZstdCompressor(level=level, zstd_dict=zdict)

    def push(self, data):
        return len(self.c.compress(data, zstd.ZstdCompressor.FLUSH_BLOCK))


def per_packet(bodies, level=6, threshold=256):
    """1.8 style: each packet alone, uncompressed length varint (0 when under the threshold)."""
    out = 0
    for b in bodies:
        if len(b) >= threshold:
            out += framed(vlen(len(b)) + len(zlib.compress(b, level)))
        else:
            out += framed(1 + len(b))
    return out


def stream(bodies, groups, make, bypass_chunks=True):
    """One context for the session, one flush per group, one flag byte per compressed frame."""
    s = make()
    out = 0
    for g in groups:
        pending = bytearray()
        for i in g:
            b = bodies[i]
            if bypass_chunks and b[0] in CHUNK_IDS:
                out += framed(1 + len(b))
            else:
                pending += b
        if pending:
            out += framed(1 + s.push(bytes(pending)))
    return out


def tick_groups(frames, gap):
    """Server ticks when the capture has them, else bursts of frames closer than gap seconds."""
    ticks = all(f.tick >= 0 for f in frames)
    groups, cur, last = [], [], None
    for i, f in enumerate(frames):
        if last is not None and (f.tick != last.tick if ticks else f.time - last.time > gap):
            groups.append(cur)
            cur = []
        cur.append(i)
        last = f
    if cur:
        groups.append(cur)
    return groups


def strategies(bodies, per_frame, per_tick, zdict=None):
    yield "per-packet deflate L6, >=256 B (1.8 style)", lambda: per_packet(bodies)
    for level in (1, 3, 6):
        yield f"stream deflate L{level}, flush/packet", lambda level=level: stream(bodies, per_frame, lambda: DeflateStream(level))
    yield "stream deflate L6, flush/packet, chunks in", lambda: stream(bodies, per_frame, lambda: DeflateStream(6), False)
    yield "stream deflate L3, flush/tick", lambda: stream(bodies, per_tick, lambda: DeflateStream(3))
    if zstd:
        for level in (1, 3):
            yield f"stream zstd L{level}, flush/packet", lambda level=level: stream(bodies, per_frame, lambda: ZstdStream(level))
        yield "stream zstd L3, flush/tick", lambda: stream(bodies, per_tick, lambda: ZstdStream(3))
        if zdict is not None:
            yield "stream zstd L3 + dict, flush/packet", lambda: stream(bodies, per_frame, lambda: ZstdStream(3, zdict))
            yield "stream zstd L3 + dict, flush/tick", lambda: stream(bodies, per_tick, lambda: ZstdStream(3, zdict))


def run(title, frames, gap, zdict=None):
    if not frames:
        return
    bodies = [f.body for f in frames]
    raw = sum(f.size for f in frames)
    payload = sum(len(b) for b in bodies)
    per_frame = [[i] for i in range(len(frames))]
    per_tick = tick_groups(frames, gap)
    print(f"\n== {title}: {len(frames)} frames, {raw / 1024:.0f} KiB framed, {len(per_tick)} tick groups")
    print(f"   {'strategy':42s} {'KiB':>8s} {'ratio':>6s} {'saved':>6s} {'ns/B':>6s}")
    print(f"   {'raw':42s} {raw / 1024:8.0f} {1:6.2f} {0:5.0f}%")
    for name, fn in strategies(bodies, per_frame, per_tick, zdict):
        t = time.perf_counter_ns()
        size = fn()
        dt = time.perf_counter_ns() - t
        print(f"   {name:42s} {size / 1024:8.0f} {raw / size:6.2f} {(1 - size / raw) * 100:5.0f}% {dt / payload:6.1f}")
    if zstd is None:
        print("   (zstd skipped: needs Python 3.14+)")


def run_per_key(title, frames, top):
    c = zlib.compressobj(6, zlib.DEFLATED, -15)
    agg = collections.defaultdict(lambda: [0, 0])
    for f in frames:
        n = len(c.compress(f.body) + c.flush(zlib.Z_SYNC_FLUSH)) - 4 + 1
        k = key(f)
        agg[k][0] += len(f.body)
        agg[k][1] += n
    total = sum(a[0] for a in agg.values()) or 1
    print(f"\n== {title}: stream deflate L6 per packet, per key (top by raw)")
    for k, (r, z) in sorted(agg.items(), key=lambda kv: -kv[1][0])[:top]:
        print(f"   {k[:36]:36s} raw {r / 1024:8.0f} KiB -> {z / 1024:7.0f} KiB  ratio {r / z:5.2f}  ({r / total * 100:4.1f}% of raw)")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("pcap", help="pcap, or NetworkHorizons capture (.nhcap.gz)")
    p.add_argument("--login-seconds", type=float, default=15)
    p.add_argument("--tick-gap", type=float, default=0.010, help="gap in seconds that starts a new tick group")
    p.add_argument("--per-key", action="store_true", help="also attribute compressed size to packet keys")
    p.add_argument("--top", type=int, default=10)
    p.add_argument("--exclude", action="append", default=[], metavar="KEY",
                   help="leave out a packet key, e.g. '[GalacticraftCore]#0' (repeatable)")
    p.add_argument("--dict", type=int, default=0, metavar="BYTES",
                   help="also try zstd with a dictionary of this size, trained on the first 20%% of each phase "
                        "and evaluated (with every other strategy) on the remaining 80%%")
    a = p.parse_args()
    if a.dict and zstd is None:
        raise SystemExit("--dict needs Python 3.14+ (compression.zstd)")

    for n, s in enumerate(load(a.pcap), 1):
        t0 = s.start
        s2c = [f for f in s.frames if f.dir == S2C and key(f) not in a.exclude]
        for phase, frames in (("LOGIN", [f for f in s2c if f.time - t0 < a.login_seconds]),
                              ("STEADY", [f for f in s2c if f.time - t0 >= a.login_seconds])):
            zdict = None
            if a.dict and len(frames) >= 100:
                cut = len(frames) // 5
                samples = [f.body for f in frames[:cut] if f.body[0] not in CHUNK_IDS]
                zdict = zstd.train_dict(samples, a.dict)
                frames = frames[cut:]
                phase += f" (last 80%, dictionary {a.dict} B from the first 20%)"
            run(f"Session {n} {phase}", frames, a.tick_gap, zdict)
            if a.per_key:
                run_per_key(f"Session {n} {phase}", frames, a.top)


if __name__ == "__main__":
    main()
