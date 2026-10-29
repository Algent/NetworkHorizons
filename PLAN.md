# NetworkHorizons plan

Working document. Meant to be refined, not followed blindly.

## Goal

Reduce and understand network traffic for heavily modded Minecraft 1.7.10 (GTNH first),
without hacks that break other mods.

In order of priority:

1. **Measure.** Know where the bytes go (per packet class, per mod channel, per player).
2. **Estimate.** Know what compression would gain on real traffic before building it.
3. **Optimize.** Only then change the wire, in small steps the server can switch off.

## Principles

- **Sides policy follows the phase.** See "Sides and versions" below.
- **Server decides.** Whether a feature is on for a connection is the server's call, sent to the client.
  A kill switch must never need a client update.
- **One jar, removable.** "Remove NetworkHorizons and retry" must always be a valid debugging step.
- **Measure before and after.** Every optimization phase ships with the numbers that justify it.
- **No data leaves the machine.** Stats and captures are local files and in-game commands only.
- **Stock Netty 4.0.10.** Nothing before phase 6 may depend on a newer Netty.
- **Java 8 compatible output.** Modern syntax is fine (jvmDowngrader), but runtime must work on Java 8 and 17+.

## Sides and versions

Target is GTNH, where the client and server run the same pack version. So:

- **Phases 1 to 3 (stats, estimator, flush coalescing):** no wire change, so the server alone is enough.
  Keep `acceptableRemoteVersions = "*"`, so admins can try it on a live server before it is in the pack.
- **From phase 4 (wire changes):** drop `acceptableRemoteVersions` and use FML's default check.
  That default (`NetworkModHolder.DefaultNetworkChecker`) already means exactly:
  - a client without the mod is rejected by a server that has it;
  - when both have it, versions must match exactly;
  - a client with the mod can still join a server without it.
- **Consequence:** no cross-version compatibility and no version negotiation. A protocol version constant
  is still sent in the hello message, as a sanity check that fails loudly.
- **Still needed:** the server-side kill switch and per-connection on/off, and the skip for local connections.

## Dependencies (to keep in mind)

- **GTNHLib.** Likely needed from phase 1. Useful parts:
  - **fastutil** (through GTNHExtLib): primitive maps for per-key counters, without boxing.
  - **Brigadier commands** for `/neth stats|dump|reset|capture`.
  - **`@Config` annotations** replace the hand-written `Config` class (the template's `Configuration` code goes away).
  - **`@Sync` config fields** are pushed to clients at `PlayerLoggedInEvent` and restored on disconnect.
    Good for showing the server's settings on the client. Not for the compression switch point,
    which must stay our own in-band hello, timed exactly in the pipeline.
  - **`@EventBusSubscriber`** auto-registers handlers per side.
  - **`network/base/NetworkChannel` / `IPacket`** for our hello and control messages.
  - **FNV-1a 64 hashing** is fast enough for the phase 5 chunk cache keys, but too collision-prone to be
    the only check that a cached chunk matches. Pair it with the length, or use a stronger hash there.
  - **`bytebuf` `MemoryUtil` / `MemoryStack`:** off-heap helpers. Probably not needed; Netty buffers cover it.
- **Hodgepodge.** Not needed as a compile dependency so far. It is in GTNH anyway, and we must stay compatible
  with its network mixins (see background). It may become `compileOnly` if we need to read its config
  (for example the custom payload size cap).

## Background: the 1.7.10 pipeline

Netty pipeline built in `NetworkSystem` (server) and `NetworkManager.provideLanClient` (client; despite
its name, `GuiConnecting` uses it for every remote server):

```
inbound : [decrypt] -> splitter (MessageDeserializer2) -> decoder (MessageDeserializer) -> packet_handler (NetworkManager)
outbound: packet_handler -> encoder (MessageSerializer) -> prepender (MessageSerializer2) -> [encrypt]
```

Facts that shape the design (checked in the 1.7.10 / Forge 10.13.4.1614 sources):

- **No protocol compression.** Only chunk data (deflate) and NBT in packets such as
  `S35PacketUpdateTileEntity` (gzip) are compressed. With Hodgepodge, some S35 NBT is sent raw (see below).
- **Encryption is AES/CFB8.** It adds no bytes, but makes the stream incompressible downstream (VPN, WAN optimizers).
  Any compression must sit before encryption.
- **`TCP_NODELAY` is false in vanilla, but true in GTNH.** Hodgepodge `tcpNoDelay` (default true) turns
  Nagle off on both sides, so with one flush per packet, every packet becomes its own TCP segment (see phase 0).
- **One flush per packet.** `NetworkManager.dispatchPacket` does `writeAndFlush` for every packet.
  `NetworkManager.processReceivedPackets` also calls `channel.flush()` once per connection per tick,
  from `NetworkSystem.networkTick`, called at the end of `MinecraftServer.updateTimeLightAndEntities`.
- **Mod packets.** Mod traffic is `S3FPacketCustomPayload` / `C17PacketCustomPayload` (channel name + byte[]).
  `FMLProxyPacket.toS3FPacket()` sends `payload.array()`, the whole backing array
  (being fixed in Hodgepodge PR 1032).
- **First payload byte.** Channels built on `FMLIndexedMessageToMessageCodec` (`SimpleNetworkWrapper`)
  use the first payload byte as the message discriminator. Many hand-rolled channels (GregTech) do the same.
- **Local connections skip serialization.** Singleplayer / LAN host connections use a local channel
  without the encoder/decoder. Nothing to measure or compress there. Detect with `NetworkManager.isLocalChannel()`.
- **Payload limits.** `S3FPacketCustomPayload` is capped at 2097050 bytes, `C17PacketCustomPayload` at 32767.
  FML 10.13.4.1614 has no multipart splitting, so one mod message is always one packet.
- **Mod detection.** FML does not keep the remote mod list on `NetworkDispatcher`. It is only checked
  during the handshake (`FMLHandshakeServerState`, `FMLNetworkHandler.checkModList`).
  The clean way to learn whether the other side has our mod is the `REGISTER` channel list,
  exposed as `FMLNetworkEvent.CustomPacketRegistrationEvent` (it carries the `NetworkManager`).

### What Hodgepodge already changes

GTNH ships Hodgepodge on both sides, and it already changes parts of the wire format
(checked in Hodgepodge 2.7.216):

- **Frame length.** `MessageSerializer2` / `MessageDeserializer2` allow a 5-byte varint length prefix
  instead of 3, for larger packets. Do not assume 3 bytes.
- **NBT length.** `PacketBuffer` NBT read/write switches to an int length when the gzipped NBT
  reaches 32767 bytes, and uses pooled gzip streams.
- **S35 byte arrays.** `S35PacketUpdateTileEntity` sends an NBT compound holding a single byte array
  (GregTech's case) as a marker + varint length + raw bytes, with no gzip.
- **Custom payloads.** The `S3FPacketCustomPayload` size cap (2097050) is configurable, and the payload
  length field is written in an extended short-or-int format. Stats must use the encoded size,
  never recompute it from the payload.
- **TCP_NODELAY.** `SpeedupsConfig.tcpNoDelay` (default true, `MixinTcpNoDelay`) sets it on both sides.
- **Other changes.** Channel name validation, a time budget on
  `NetworkManager.processReceivedPackets`, and null-safety around `NetworkDispatcher` / `FMLProxyPacket`.
- **Padding fix.** The `FMLProxyPacket` padding fix (Hodgepodge PR 1032) is in progress.

Other mods in the local Gradle cache only mixin into `NetHandlerPlayServer` (Angelica, Backhand,
ServerUtilities, Horizon-QA) or `PacketBuffer` (ModularUI), so none of them touch the pipeline itself.
Mods missing from that cache are not checked yet.

### Prior art

- **Vanilla `/netstat`** (dedicated servers only, console/RCON only: it refuses players).
  Subcommands: none, `read-count`, `write-count`, `most-read`, `most-write`, `hottest-read`, `hottest-write`,
  `packet-read <id>`, `packet-write <id>`. `MessageSerializer.encode` and `MessageDeserializer` feed
  `NetworkManager.field_152462_h` (`NetworkStatistics`). Global (not per player), by vanilla packet ID,
  with all mod traffic under the one custom payload ID.
- **Hodgepodge `/packetstats`** (client, opt-in config `trackIncomingPackets`). Counts incoming packets
  per class and per FML channel, plus the receive queue size. Counts only, no bytes, no server side.

## Phase 0: baseline with zero code

Purpose: a first rough split of traffic before writing anything.

- [ ] On a dedicated GTNH test server, run the vanilla `/netstat` command (server console) after a play session.
- [ ] Note the share of custom payload (all mods) vs chunk data vs entity movement vs tile entity updates.
- [x] Wireshark capture of a real session (community server, online mode, so encrypted: wire level only).
- [x] Offline-mode local server + capture. Offline mode skips encryption, so the capture can be parsed per
  packet and per channel, and fed to the phase 2 estimator. Better than `/netstat`.

### Results: community server, early base, idle (2026-10-29)

Capture `Basic_Capture_Elios_Server_PDIM.pcapng`, 96 s on the game connection. Hodgepodge `/packetstats`
taken on the same server a few minutes before.

| | Server to client | Client to server |
|---|---|---|
| Login (first 2 s) | 2.2 MiB payload, mostly full 1460 B segments | 17 KiB (one 7 KiB burst: mod list) |
| Steady payload | 18.8 KiB/s | 0.9 KiB/s |
| Steady wire (Ethernet+IP+TCP) | 39.8 KiB/s, 392 segments/s | 5.8 KiB/s, 94 frames/s (mostly ACKs) |
| Header overhead | 53% of wire bytes | 85% |
| Segment payload size | median 41 B, 97% under 100 B | median 35 B |

- **One packet per segment.** 392 segments/s matches the 19.8 packets/tick from `/packetstats`.
  Segments come in one burst per tick (gaps of 40 to 55 ms between bursts, under 5 ms inside a burst).
- **GalacticraftCore dominates.** `/packetstats`: 17.9 of 19.8 packets/tick (90%) are `FMLProxyPacket[GalacticraftCore]`.
  On the wire, 45% of segments are exactly 41 B and 44% exactly 45 B: two message kinds, about 9/tick each.
  Which GC messages, and why every tick for an idle player, is still to find (likely a mod-side fix, see "Out of scope").
- **Flush coalescing looks like the cheapest big win.** Writing one segment per tick instead of ~20 would cut
  server-to-client wire bytes by about half and segments by about 90%, plus most client ACKs, with no wire change.
- **Compression:** not measurable here (encrypted). Repetitive 40 B mod packets with the channel name in each
  suggest stream deflate would do well. Needs the offline-mode capture.

### Results: local offline-mode server, endgame world, single player (2026-10-29)

Capture `Basic_Capture_Boubou_Endgame_World_Offline_Server_PDIM.pcapng` (loopback, plaintext). Two sessions:
session 1 idle (97 s after login), session 2 moving around the base (109 s after login, chunk loading).
Parsed with throwaway Python scripts (stream reassembly, frame split, key = class or channel + first payload byte).
Server to client only below; client to server is under 1 KiB/s.

**Login (first 15 s), ~4 MB, same in both sessions:**

| Source | Size | Note |
|---|---|---|
| `S26PacketMapChunkBulk` | 1858 KiB | already deflated |
| `BQ_NET_CHAN` (BetterQuesting) | 1330 KiB | 76 × 18 KB, already compressed |
| `FML\|HS` #3 (registry ID sync) | 489 KiB | one packet, compresses 2.8× |
| `FORGE` #2 (fluid IDs) | 76 KiB | compresses 3.2× |

**Steady state:**

| | Session 1 (idle) | Session 2 (moving) |
|---|---|---|
| Rate | 8.6 KiB/s, 11.3 packets/tick | 99.7 KiB/s, 52 packets/tick |
| Top source | GalacticraftCore #0: 84% | MapChunkBulk 36%, S35 23%, MultiBlockChange 18% |
| GalacticraftCore #0 | 9.0/tick, 7.2 KiB/s | 8.4/tick, 6.7 KiB/s (7%) |
| GregTech | none | #30 coil status 5.3% (24 KB avg, up to 32 KB), #2 block event 3.2%, #24 BW meta block 2.2% |
| S35 | none | 38.6/tick; 98% are GT's Hodgepodge byte array form (27 B avg) |

- **One TCP segment per packet,** confirmed: session 2 has 117159 server segments for 117156 frames.
- **GalacticraftCore #0 is `C_UPDATE_GEAR_SLOT`,** 9 per tick, forever, even when idle. `GCPlayerHandler.checkGear`
  calls `checkRenderGear` every tick, which sends all 9 show/hide flags with `sendToAllAround` (50 blocks),
  unconditionally (since GC commit b46dbe763, PR #103). With N players together, each receives 9 × N packets per tick.
  Fix in GC: send only on change or on `forceSend`.

**Compression estimate (Python zlib/zstd, server to client, sizes include the framing):**

| Strategy | S1 login | S1 steady | S2 login | S2 steady |
|---|---|---|---|---|
| Per-packet deflate ≥ 256 B (1.8 style) | 11% | −2% | 11% | 23% |
| Stream deflate L1, flush per packet | 9% | 74% | 10% | 42% |
| Stream deflate L6, flush per packet | 13% | 79% | 13% | 46% |
| Stream deflate L3, flush per tick | 13% | 93% | 14% | 51% |
| Stream zstd L3, flush per packet | 13% | 59% | 13% | 41% |
| Stream zstd L3, flush per tick | 14% | 94% | 14% | 55% |

- **Login barely compresses:** 80% of it is chunks and BetterQuesting data, both already compressed.
- **Steady state compresses well,** mostly thanks to the context across packets (per-packet 1.8 style does almost nothing).
  Best ratios per source in session 2: BW meta block 15×, MultiBlockChange 14×, GC gear 6×, GT coil 4×, S35 2.6×;
  map chunks 1.06×.
- **Flush per tick beats flush per packet,** clearly so for zstd (its per-flush overhead hurts tiny packets).
  That favors compressing the per-tick batch from phase 3 over compressing each packet.
- CPU numbers from Python are not meaningful for Java; measure in the mod (phase 2).
- Caveats: one player, loopback (no real segment sizes), short sessions. Repeat with several players before deciding.

## Phase 1: traffic stats (server-only, client optional)

Purpose: answer "who sends what, to whom, how much" on live servers. No protocol change, so the
server can install it alone.

### Hooks

- **Outbound:** mixin at the end of `MessageSerializer.encode(ChannelHandlerContext, Packet, ByteBuf)`.
  It has the `Packet` object and the encoded size (`ByteBuf.readableBytes()`).
- **Inbound:** mixin in `MessageDeserializer.decode`. Bytes consumed per decoded packet, plus its class.
- **Custom payloads:** for `S3FPacketCustomPayload` / `C17PacketCustomPayload`, key by channel name
  plus first payload byte. The S3F getters (`func_149169_c()`, `func_149168_d()`) are `@SideOnly(CLIENT)`
  and stripped on dedicated servers, so S3F is read through an `@Accessor` mixin on its fields.
- **Framing:** add the varint length prefix size (1 to 5 bytes with Hodgepodge) to get framed bytes.
  It is the same on the wire, since CFB8 adds nothing.
- **No conflict with Hodgepodge.** It mixes into `MessageSerializer2`, not `MessageSerializer`.

### Data model

- **Per-connection stats** in a channel `AttributeKey`, created lazily on the first packet.
  Encoding runs on that channel's event loop thread, so each bucket has a single writer.
  Commands read from the server thread and tolerate slightly stale values.
- **Player identity:** the `NetworkManager` is the `packet_handler` in the pipeline; once in play state its
  net handler is a `NetHandlerPlayServer` with the player. Before that, label the connection by address.
- **Key:** `(direction, connectionState, packetClass, channel?, subId?)`.
  The connection state separates login and FML handshake traffic (`FML|HS`, including the registry
  ID sync, likely large in GTNH) from play traffic.
- **Values:** count, bytes, max size, and a ring buffer of per-second buckets
  (last 60 s, last 10 min) for rates.
- **Global totals** = sum over connections, plus a bucket for disconnected players so totals stay correct.

### Output

- `/neth stats [60s|10m|total] [top N] [player]` (or `/neth stats <player>`): top sources by bytes/s,
  with share of total, per direction.
- `/neth dump`: CSV (one row per key per connection, plus the disconnected bucket) into `networkhorizons/`
  in the instance folder, for offline analysis.
- `/neth reset`.
- Client side: the same command as `/nethc` (client command, no permission needed), for the client's own connection.

### Implementation notes

- GTNHLib 0.11.54 is a required dependency. jvmDowngrader jars need it anyway: its `MixinJarDiscoverer`
  lets FML skip the `META-INF/versions` classes, and it provides the API stubs (`jvmDowngraderStubsProvider = gtnhlib`).
- Config: GTNHLib `@Config` (`StatsConfig`: `enabled`, `defaultTop`), with the generated config GUI.
- Server command `/neth` through GTNHLib's Brigadier (registered at server about to start, after `BrigadierApi.init()`).
  Client command `/nethc` is a `GTNHClientCommand`, since that Brigadier dispatcher is server side only.
- No fastutil needed so far: `ConcurrentHashMap` lookups on known keys do not allocate.
- Mixins at `MessageSerializer.encode` TAIL and `MessageDeserializer.decode` HEAD/TAIL, `remap = false`
  (Netty method names, class names are deobfuscated at runtime).
- Key: class, or channel + first payload byte. The connection state is stored with the key when first seen.
- Rates: per key, a 60 × 1 s ring and a 60 × 10 s ring (about 1.5 KB per key).

### Sides

- Server alone is enough (`acceptableRemoteVersions = "*"` is already set).
- Works client-side too (same classes), so a player can measure what a public server sends them.

### Done when

- [x] Running it on a dedicated server (`runServer`) gives a stable per-channel / per-packet breakdown.
  Checked with a scripted vanilla client: totals match vanilla `/netstat` exactly once the length prefixes are removed.
  The integrated server shows nothing.
- [x] Same on a GTNH test server (daily 804, local server + client, see below).
- [ ] Overhead is negligible (counter updates only, no allocation per packet). Not measured yet.

### Results: GTNH daily 804, local server and client (2026-10-29)

One player, two logins, about 2 minutes in a developed base. `/neth dump` on the server and `/nethc dump` on the client.

- **Server and client agree byte for byte** on every key both dumps cover (`FML|HS` #3 500319 B, `FORGE` #2 78201 B,
  `REGISTER` 2541 B, the client's `FML|HS` #2 7274 B). Ongoing keys differ only by the 31 s between the two dumps.
- **Login: about 3.5 MB per join.** BetterQuesting 1.37 MB, chunks about 0.85 MB, registry sync 500 KB,
  then `FORGE` 78 KB, `ForgeMicroblock` 15 KB, `mobsinfo` 11 KB, `enhancedlootbags` 8.5 KB.
- **Steady, standing in the base: about 29 KiB/s and 24 packets/tick to the client.** S35 34% (12 to 18/tick),
  GalacticraftCore #0 (gear slot) 25% at 9/tick, MultiBlockChange 13%.
- **GregTech:** #2 (block event) reaches 64 to 128 KB per packet, #30 (coil status) 64 KB per packet.
- **Server list ping:** about 25 KB per `S00PacketServerInfo` (mod list). The multiplayer screen pinged 24 times
  before joining: 582 KB.
- **Client to server:** under 0.6 KiB/s; OpenComputers #0 is the largest at about 3/s.
- **Side-only pitfall seen twice:** `S3FPacketCustomPayload` getters and `EnumChatFormatting.getTextWithoutFormattingCodes`
  are `@SideOnly(CLIENT)`. Anything that runs on the server must avoid client-only vanilla methods.

### Testing

- Unit tests that drive the real vanilla encoder/decoder in a Netty `EmbeddedChannel`
  (available in 4.0.10), so counting is checked without starting a server.

## Phase 2: compression estimator and capture

Purpose: answer "would compression help, and which kind" with real traffic, still with no protocol change.

### Shadow estimator (in the mod)

- Opt-in, one player or a sample of players at a time.
- Copies each outbound **frame body** (the encoder output, the exact bytes phase 4 would compress)
  into a bounded queue, processed by a low-priority thread, never on the Netty thread.
- JDK deflate only, so the mod gains no dependency. Compares, on the same data:
  - raw;
  - per-packet deflate with a 256-byte threshold (what 1.8 does);
  - stream deflate, flushed per packet, context kept between packets (the phase 4 design);
  - stream deflate, flushed per tick. This one needs several packets per frame, which the phase 4
    design does not do; it is measured to know what that would leave on the table.
- Reports ratio and CPU ns/byte per strategy.
- A full queue drops frames and counts the drops. Drops slightly bias the stream estimates; report it.

### Raw capture

- Opt-in, admin-only, local file, per player, time-limited, both directions.
- Format: `[long nanos][byte direction][varint length][frame body]`, before encryption.
  Framed sizes can be recomputed from it.
- **Privacy:** contains chat, positions, everything. Document it, never enable it by default.

### Offline replay tool

- Separate source set or test main, never shipped in the mod jar.
- Runs any strategy over a capture, including the ones the mod does not ship: zstd (zstd-jni) at several
  levels, preset dictionaries trained on captures, multi-packet frames.

### Done when

- [ ] A capture from a real GTNH session gives a ratio table for each strategy.
- [ ] Decision recorded here: build phase 4 or not, with which algorithm and flush policy.

## Phase 3: flush coalescing (server-only)

Purpose: fewer syscalls and fewer TCP segments, no wire format change. Independent from phase 4:
per-message compression does not depend on when Netty flushes, so this can ship (or be dropped) on its own numbers.

- Mixin in `NetworkManager.dispatchPacket` replaces `writeAndFlush` with `write`, and relies on the existing
  per-tick `channel.flush()` in `processReceivedPackets` (end of server tick, so little added latency).
- Check that the flush still runs with Hodgepodge's time budget on `processReceivedPackets`.
- **Keep the immediate flush when:**
  - the packet has future listeners (disconnect / kick paths wait on them);
  - the connection is not in play state;
  - the send does not come from the server thread;
  - possibly: keep-alive, chat. Decide after measuring.
- GTNH already runs with `TCP_NODELAY = true` (Hodgepodge), so batching is the only thing grouping packets.
  Phase 0 shows ~20 segments per tick that this would merge into one or two.

### Measure

- Flushes per second, syscalls, TCP segments (pcap), and ping before and after.

## Phase 4: stream compression (both sides, negotiated)

Purpose: the actual bandwidth win, only if phase 2 says it is worth it.

### Design sketch

Per-message compression with context takeover, like WebSocket permessage-deflate (RFC 7692):

- Compress the **body of each frame**, between the encoder and the prepender (outbound), and between the
  splitter and the decoder (inbound). The length prefix stays uncompressed.
- **One deflate context per connection and direction**, kept for the whole session, so repeated structures
  across small packets compress well.
- **Flush and trim.** `SYNC_FLUSH` after each message, then strip the trailing `00 00 FF FF`;
  the receiver appends it back before inflating.
- **One flag byte per frame:** raw or compressed. Already compressed packets (chunk data) go raw and
  do not enter the dictionary.
- **Switch point.** The server sends an "enable" message on the NetworkHorizons channel. Its own encoder hook
  sees that packet go out (raw) and compresses every frame after it; same thread, so ordering is exact.
  On the client, our hook right after the decoder recognizes the message by channel name on the Netty thread
  and flips the decoder state synchronously. Frames are delivered one at a time through the pipeline,
  so the next frame already sees the new state.
  Do not rely on the FML channel handler for this: when it processes the message is not ours to control.
- **Stats see both sides.** Phase 1 counters keep counting uncompressed sizes, and a second counter records
  the compressed wire size, so `/neth stats` shows the actual saving per source.

### Negotiation

- FML's default version check guarantees the client has the mod with the same version
  (see "Sides and versions"), so there is nothing to negotiate.
- The server sends a hello message with the protocol version and whether compression is on
  for this connection. A protocol mismatch disconnects with a clear message.
- Compression is enabled only if the server config allows it, the client config does not opt out,
  and the connection is not local.
- **Kill switches:** global config on the server (authoritative), opt-out on the client, and per player on the server.
- **Fallback if we ever go back to optional:** `CustomPacketRegistrationEvent` tells the server whether
  the client registered our channel, with no mixin.

### Costs

- **Memory:** about 256 KB per connection for a zlib deflater (default window and memLevel), about 40 KB
  for an inflater. About 30 MB for 100 players, both directions. Lower memLevel trades ratio for memory.
- **CPU:** runs on Netty IO threads. Use a configurable level, 1 to 3 to start.

### Risks

- **Desync means disconnect.** Fail loudly with a clear message, never continue on a corrupted stream.
- **Bounded inflate.** Cap each inflated frame at the maximum packet size (vanilla or Hodgepodge's cap),
  so a bad or malicious peer cannot make the other side allocate without limit.
- **Proxies.** Anything that parses packets between client and server (BungeeCord-like setups) breaks.
  Detect if possible, otherwise provide a config switch and document it.
- **Mods touching the pipeline.** Hodgepodge is known (see background). Finish the inventory over the
  full GTNH mod list before starting this phase.

### Later options

- zstd (streaming with flush per message) if phase 2 shows a clear win over deflate,
  with natives bundled or optional.
- Shared preset dictionary built from captured GTNH traffic.

### Testing

- Round-trip unit tests on captured streams, with random frame boundaries and random read chunk sizes.
- Fuzz the inflater side: it must fail cleanly on garbage.
- Long soak test on a GTNH server with several players, plus reconnect and dimension change loops.

## Phase 5: chunk reuse (later, maybe)

Purpose: avoid resending chunks a client already has (revisits, reconnects).

- Hash-verified client cache, with `full` / `ref` / `patch` paths, as BandwidthOptimizer does on modern versions.
- Largest potential win and largest complexity: memory and disk budget, invalidation, and tile entity
  description packets that follow the chunk.
- Design to be written once phases 1 to 4 are stable.

## Phase 6: Netty modernization (separate module, much later)

Purpose: about 12 years of Netty fixes and performance (epoll/io_uring transports, allocators).
No bandwidth gain by itself.

Known breakages with Netty 4.1+:

- **`AttributeKey` constructor.** `new AttributeKey(String)` is used by vanilla (`NetworkManager`) and FML
  (`NetworkRegistry`, `NetworkDispatcher`, `FMLOutboundHandler`, `HandshakeMessageHandler`,
  `FMLIndexedMessageToMessageCodec`). The constructor is gone, so it needs a bytecode rewrite to `AttributeKey.valueOf`.
- **`PacketBuffer extends ByteBuf`** and delegates every method. New abstract `ByteBuf` methods lead to
  `AbstractMethodError`. The same applies to any mod class extending `ByteBuf`.
- **`EmbeddedChannel` behaviour changed.** FML's mod channel system is built on it (`FMLEmbeddedChannel`).
- **Pooled allocator by default.** Mods that never release buffers would leak. Probably force
  `-Dio.netty.allocator.type=unpooled`.
- **Classpath.** The newer Netty must be on the classpath before Minecraft, on clients and dedicated servers,
  for both Java 8 and Java 17+ launch paths.

Must stay a separate, opt-in module, so a Netty problem never disables phases 1 to 4.

## Out of scope (belongs in the mods themselves)

Things found while looking at GregTech, to fix there, not here:

- `GTBlockEventTracker` and `GTCoilTracker` broadcast to the whole dimension (`sendToWorld`)
  instead of to players watching the chunk.
- Compact encodings for GT block event and coil packets (chunk-relative coordinates, deltas, varints).
  Coil status packets reach 24 to 32 KB each (phase 0).

Found in Galacticraft and Hodgepodge (phase 0):

- **GC:** `checkRenderGear` sends 9 `C_UPDATE_GEAR_SLOT` packets per player per tick to everyone within 50 blocks.
- **Hodgepodge:** `PooledGzipOutputStream` writes a wrong gzip trailer (CRC and size). `DeflaterOutputStream.write(int)`
  calls the overridden `write(byte[], int, int)`, so single-byte writes are counted twice. Harmless today because
  `PooledGzipInputStream` does not check the trailer, but any standard gzip reader rejects that NBT.

## Open questions

- The full GTNH mod list, beyond the local Gradle cache: which mods add Netty handlers or touch the network classes?
- Is a byte-level 1.7.10 packet profiler already floating around in the GTNH community?
- Deflate only, or zstd too? Decided by phase 2 numbers.
- Do we also compress client to server? Probably not worth it; phase 1 will tell.
- Are proxies between client and server used at all in the GTNH community? Decides how much phase 4
  needs to care.
- How big is the login cost (FML handshake and registry ID sync) for a GTNH client? Phase 0: about 4 MB,
  mostly chunks and BetterQuesting data (both already compressed); the registry sync is 489 KiB.
- Hodgepodge findings were checked against 2.7.216; re-check against the current version before phase 4.
