# pcap tools

Offline analysis of Wireshark captures of 1.7.10 sessions (phase 0 of [PLAN.md](../../PLAN.md)).
Never shipped in the mod jar.

Needs Python 3.10+ (3.14+ for the zstd estimates) and `tshark` (Wireshark). Set `TSHARK` if it is not found.

## Capturing

- Capture with a filter on the server port, e.g. `tcp port 25565`. For a local server, use the loopback adapter (Npcap).
- `breakdown.py` and `compress.py` need an **offline-mode** server (`online-mode=false`): 1.7.10 then skips
  encryption. Online-mode captures only work with `wirestats.py`.
- Captures contain chat, positions, everything the server sends. Do not share them casually.

## Scripts

```bash
python tools/pcap/breakdown.py capture.pcapng --login-seconds 15
```

Per play session: S2C bytes per 5 s, then the top packet keys for login, steady state, tile entities and C2S.
Keys are the packet class, `[channel]#first_payload_byte` for custom payloads, or `S35 <tile entity id>`.

```bash
python tools/pcap/compress.py capture.pcapng --per-key
```

S2C size under several strategies (1.8 style per packet, stream deflate / zstd flushed per packet or per tick),
for login and steady state. `--per-key` shows the stream-deflate ratio per packet key.

```bash
python tools/pcap/wirestats.py capture.pcapng --login-seconds 2
```

Wire level only (works on encrypted captures): payload vs wire bytes, segments per second, segment sizes,
per-tick bursts, for the biggest TCP connection.

## Notes

- Handles the Hodgepodge wire changes: 5-byte frame length, extended `S3FPacketCustomPayload` length,
  `S35PacketUpdateTileEntity` byte array form. NBT is inflated raw, since Hodgepodge's pooled gzip writes a wrong trailer.
- "Login" is time based (`--login-seconds`), not protocol based.
- On loopback, segment sizes are not representative of a real network (the MSS is about 64 KiB).
