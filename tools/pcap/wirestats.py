"""Wire-level stats (bytes, segments, bursts) for the biggest TCP connection of a capture.

Works on encrypted (online-mode) captures too, since it never looks at the payload.
"""
import argparse
import collections
import statistics

from mcframes import tshark_fields


def load(pcap):
    rows = tshark_fields(pcap, "tcp", ["tcp.stream", "frame.time_relative", "tcp.srcport", "tcp.dstport", "frame.len",
                                       "tcp.len", "tcp.analysis.retransmission", "tcp.flags.syn", "tcp.flags.ack"])
    by_stream = collections.defaultdict(list)
    server_port = {}
    for st, t, sport, dport, flen, tlen, retx, syn, ack in rows:
        if syn in ("1", "True") and ack in ("0", "False"):
            server_port[st] = dport
        by_stream[st].append((float(t), sport, int(flen), int(tlen or 0), retx != ""))
    st = max(by_stream, key=lambda k: sum(r[2] for r in by_stream[k]))
    port = server_port.get(st)
    return [(t, "S2C" if sport == port else "C2S", flen, tlen, retx) for t, sport, flen, tlen, retx in by_stream[st]]


def summary(d, rows, dur):
    data = [r for r in rows if r[1] == d and r[3] > 0]
    allf = [r for r in rows if r[1] == d]
    if not data:
        return
    pay = sum(r[3] for r in data)
    wire = sum(r[2] for r in allf)
    sizes = [r[3] for r in data]
    print(f"  {d}: payload {pay / 1024:9.1f} KiB ({pay / 1024 / dur:6.1f} KiB/s)  wire {wire / 1024:9.1f} KiB "
          f"({wire / 1024 / dur:6.1f} KiB/s)  frames {len(allf) / dur:5.0f}/s (data {len(data) / dur:5.0f}/s)  "
          f"retx {sum(r[4] for r in data)}  header overhead {(wire - pay) / wire * 100:4.1f}%")
    q = statistics.quantiles(sizes, n=20) if len(sizes) > 1 else [sizes[0]] * 19
    print(f"       segment payload: p50 {statistics.median(sizes):.0f} p95 {q[18]:.0f} max {max(sizes)}  "
          f"<100 B: {sum(s < 100 for s in sizes) / len(sizes) * 100:4.1f}%")
    top = collections.Counter(sizes).most_common(5)
    print("       most common sizes: " + ", ".join(f"{s} B ({c / len(sizes) * 100:.0f}%)" for s, c in top))


def bursts(rows, gap):
    out, cur, last = [], 0, None
    for t, d, _, tlen, _ in rows:
        if d != "S2C" or not tlen:
            continue
        if last is not None and t - last > gap:
            out.append(cur)
            cur = 0
        cur += tlen
        last = t
    if cur:
        out.append(cur)
    return out


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("pcap")
    p.add_argument("--login-seconds", type=float, default=2)
    a = p.parse_args()

    rows = load(a.pcap)
    t0 = next(r[0] for r in rows if r[3] > 0)
    for name, rs in (("LOGIN", [r for r in rows if r[0] - t0 < a.login_seconds]),
                     ("STEADY", [r for r in rows if r[0] - t0 >= a.login_seconds])):
        if not rs:
            continue
        dur = max(rs[-1][0] - rs[0][0], 1e-9)
        print(f"\n[{name}] {dur:.1f}s")
        summary("S2C", rs, dur)
        summary("C2S", rs, dur)
        b = bursts(rs, 0.010)
        if b:
            print(f"   S2C bursts (gap > 10 ms): {len(b) / dur:.1f}/s, median {statistics.median(b):.0f} B, max {max(b)} B")


if __name__ == "__main__":
    main()
