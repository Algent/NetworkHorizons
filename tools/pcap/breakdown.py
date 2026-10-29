"""Traffic breakdown per packet class / mod channel for each play session of an offline-mode capture."""
import argparse
import collections

from mcframes import C2S, S2C, S35_ID, key, load_sessions


def report(title, frames, d, top, seconds, tile_entity_ids=False):
    agg = collections.defaultdict(lambda: [0, 0, 0])
    for f in frames:
        if f.dir == d:
            a = agg[key(f, tile_entity_ids)]
            a[0] += 1
            a[1] += f.size
            a[2] = max(a[2], f.size)
    total = sum(a[1] for a in agg.values()) or 1
    count = sum(a[0] for a in agg.values())
    seconds = max(seconds, 1e-9)
    print(f"\n== {title} {d}: {count} packets ({count / seconds / 20:.1f}/tick), "
          f"{total / 1024:.1f} KiB ({total / 1024 / seconds:.1f} KiB/s) over {seconds:.0f}s")
    print(f"   {'key':44s} {'count':>7s} {'/tick':>6s} {'KiB':>9s} {'KiB/s':>7s} {'share':>6s} {'avg B':>7s} {'max B':>8s}")
    for k, (c, b, m) in sorted(agg.items(), key=lambda kv: -kv[1][1])[:top]:
        print(f"   {k[:44]:44s} {c:7d} {c / seconds / 20:6.2f} {b / 1024:9.1f} {b / 1024 / seconds:7.2f} "
              f"{b / total * 100:5.1f}% {b / c:7.0f} {m:8d}")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("pcap")
    p.add_argument("--login-seconds", type=float, default=15, help="time after connect counted as login (default 15)")
    p.add_argument("--top", type=int, default=30)
    a = p.parse_args()

    for n, s in enumerate(load_sessions(a.pcap), 1):
        t0 = s.start
        print(f"\n######## Session {n} (TCP stream {s.stream}): {s.end - t0:.0f}s, "
              f"{s.segments[S2C]} S2C segments for {sum(f.dir == S2C for f in s.frames)} S2C frames")
        per5 = collections.defaultdict(int)
        for f in s.frames:
            if f.dir == S2C:
                per5[int((f.time - t0) // 5) * 5] += f.size
        print("S2C KiB per 5s:", " ".join(f"{t}:{b / 1024:.0f}" for t, b in sorted(per5.items())))

        login = [f for f in s.frames if f.time - t0 < a.login_seconds]
        steady = [f for f in s.frames if f.time - t0 >= a.login_seconds]
        steady_s = s.end - t0 - a.login_seconds
        report("LOGIN", login, S2C, a.top, min(a.login_seconds, s.end - t0))
        report("STEADY", steady, S2C, a.top, steady_s)
        report("STEADY tile entities", [f for f in steady if f.pid == S35_ID], S2C, a.top, steady_s, True)
        report("STEADY", steady, C2S, a.top, steady_s)


if __name__ == "__main__":
    main()
