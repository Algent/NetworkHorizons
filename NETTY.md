# Netty 4.0.10 vs 4.0.56 vs 4.2.21

What changed in Netty that matters for 1.7.10 and GTNH: bugs in 4.0.10 on code paths that vanilla, FML and GTNH
mods actually use, and what moving to 4.0.56 or 4.2.21 would bring.

Checked on 2026-11-26 against:

- the Netty git history between the tags `netty-4.0.10.Final`, `netty-4.0.56.Final` and `netty-4.2.21.Final`;
- the 1.7.10 / Forge 10.13.4.1614 sources;
- the GTNH mod repositories (237 repos; the 12 closed-source mods are not covered).

Every 4.0.10 bug below was confirmed by reading the 4.0.10 source, not only the commit message.
Commit ids are from the Netty repository.

## Summary

- 1.7.10 ships `netty-all-4.0.10.Final` (October 2013). The 4.0 line ended at 4.0.56 (February 2018), 2,291 commits
  later. 4.2.21 is 9,150 commits after that.
- **Most likely to explain real incidents:**
  1. **Selector rebuild** leaves every existing channel holding a dead `SelectionKey` (fixed in 4.0.21). After a
     `Selector.select() returned prematurely 512 times in a row; rebuilding selector.` warning, players on that IO
     thread get kicked with `Internal Exception: java.nio.channels.CancelledKeyException` the next time their socket
     send buffer fills. An interrupted Netty IO thread triggers that rebuild in an endless loop at 100% CPU
     (fixed in 4.0.19).
  2. **`ReadTimeoutHandler` uses the wall clock** (fixed in 4.0.18). If the clock steps forward by more than 30 s on
     the server (20 s on the client), every connection times out at once.
  3. **`autoRead` races during login** (fixed in 4.0.19 and 4.0.24). ArchaicFix already ships a workaround for the
     singleplayer login hang that comes out of this area.
- **Not a Netty bug, present on every version:** FML shares one `EmbeddedChannel` per mod channel. A failed outbound
  send is rethrown while the next inbound packet on that channel is processed, whoever sent it, and FML kicks that
  player.
- **Java 8 and Java 17+ run Netty 4.0.10 in different modes** (measured): direct buffers on Java 8, heap buffers
  on 17+.
- **4.0.56 is close to a drop-in replacement.** `ByteBuf` has the same 143 abstract methods, and the public
  `AttributeKey(String)` constructor is still there. A scan of the whole pack finds two link breaks, both relinked
  by the override prototype, and it needs one JVM flag (Part 2). It has all the fixes above, plus epoll and an
  opt-in pooled allocator. 4.1+ and 4.2 break `PacketBuffer` (181 abstract methods) and `AttributeKey`.

## The surface we use

Vanilla and FML:

| Area | What | Notes |
|---|---|---|
| Transport | `NioEventLoopGroup`, `NioSocketChannel`, `NioServerSocketChannel`, `LocalChannel`, `LocalServerChannel` | The local transport serves singleplayer and the LAN host player. |
| Pipeline | `timeout` (`ReadTimeoutHandler`: 30 s on the server via `fml.readTimeout`, 20 s on the client), `legacy_query`, `splitter` and `decoder` (`ByteToMessageDecoder`), `prepender` and `encoder` (`MessageToByteEncoder`), `decrypt` (`MessageToMessageDecoder`), `encrypt`, `fml:packet_handler`, `packet_handler` | `decrypt`, `encrypt` and `fml:packet_handler` are added from the server thread. |
| Threads | `writeAndFlush` on the event loop or through `execute()`, `channel.flush()` from the server thread every tick, `setAutoRead` from both the server thread and the IO threads | |
| FML | One `EmbeddedChannel` per mod channel and side, shared by all players; one handshake `EmbeddedChannel` per connection; the `AttributeKey` constructor; `ctx.attr()` for handshake state | |
| Buffers | The default allocator (unpooled), `Unpooled.*`, `PacketBuffer extends ByteBuf` | |

GTNH mods: 1,352 `ByteBuf` imports, and little beyond buffers and FML channels. The deeper uses:

| Mod | What | Why it matters |
|---|---|---|
| BugTorch | `@Overwrite` of `NioSocketChannel.doWrite` (`fixNettyConnectionFailureResourceLeak`, default on) | A copy of the 4.0.10 gathering write loop that swallows `IOException` and calls `close()`. It becomes the hot write path once several packets go out per flush (phase 3). Pinned to 4.0.10 internals. |
| ArchaicFix | `fixLoginRaceCondition` (default on): keeps `autoRead` off during the client login, and emulates `autoRead` on `EmbeddedChannel` | Evidence for the `autoRead` item below. |
| Hodgepodge | `fixNettyNPE` (client, default on) injects into `Bootstrap.checkAddress` | That method does not exist in 4.0.10, nor in any 4.0 or 4.1 release checked, so this fix probably never applies. Worth a look in the mixin log. |
| GT5-Unofficial | `PooledByteBufAllocator.DEFAULT.directBuffer()` in `getDescriptionPacket` | Released correctly. The pooled allocator races fixed in 4.0.x were all introduced after 4.0.10. |
| ServerUtilities | Aurora web server on the 4.0.10 HTTP codec, bound to all interfaces | Off by default. A 2013 HTTP parser if enabled on a public server. |
| BetterQuesting, FalsePatternLib | `io.netty.util.internal.ConcurrentSet` | Internal class, still present in 4.2.21. |

## Part 1: 4.0.10 bugs on our code paths

Ordered by how likely they are to explain things seen in the wild.

### 1. Selector rebuild: dead SelectionKeys, then CancelledKeyException kicks

Fixed in 4.0.19 (`bf5c928796`, interrupt), 4.0.21 (`ac348956e3`, #2615) and 4.0.22 (`016bdfbf66`).

- `NioEventLoop.select()` counts `select()` calls that return early with no keys. At 512 in a row it logs
  `Selector.select() returned prematurely 512 times in a row; rebuilding selector.` and moves every channel to a
  new `Selector`.
- In 4.0.10, `rebuildSelector()` registers the channels with the new selector but never updates
  `AbstractNioChannel.selectionKey`. Every channel on that event loop keeps a cancelled key.
- Afterwards, for those channels:
  - **Writes.** `setOpWrite()` and `clearOpWrite()` call `interestOps()` on the cancelled key and throw
    `CancelledKeyException`. `setOpWrite()` runs whenever a write is incomplete because the socket send buffer is
    full (chunk bursts, login data, a slow client). `AbstractUnsafe.flush0` then fails all flushed writes. Vanilla
    adds `FIRE_EXCEPTION_ON_FAILURE` to every packet, so FML logs `NetworkDispatcher exception` and
    `NetworkManager.exceptionCaught` closes the connection with
    `Internal Exception: java.nio.channels.CancelledKeyException`. The client has received a truncated packet.
  - **Reads.** A read while `autoRead` is off hits the same exception in `NioByteUnsafe.read()`, and
    `processSelectedKey` closes the channel.
  - **autoRead.** `doBeginRead()` checks `isValid()` and silently returns, so turning `autoRead` back on does nothing.
- Connections accepted after the rebuild are fine. The pattern is a rebuild warning, then existing players dropping
  one by one over the following minutes or hours.
- **Interrupt loop.** If a Netty IO thread gets its interrupt flag set (code running on it calls `interrupt()`, or
  re-interrupts after catching `InterruptedException`), every `select()` returns at once. 4.0.10 never clears the
  flag: 100% CPU on that thread, and a rebuild (with the bug above) every 512 spins, for as long as the flag stays set.
- The other classic trigger is the Linux epoll empty-wakeup JDK bug.
- **Look for:** `rebuilding selector`, `CancelledKeyException`, `Failed to re-register a Channel to the new Selector`.

### 2. ReadTimeoutHandler uses the wall clock

Fixed in 4.0.18 (`cf2c8b40ae`). Read batches are taken into account since 4.0.29 (`6703318010`), and the handler is
built on `IdleStateHandler` since 4.0.39.

- 4.0.10's `ReadTimeoutHandler` stores `System.currentTimeMillis()` at each read, and its check task compares
  `currentTimeMillis()` against it.
- A wall clock step forward larger than the timeout makes every connection on that JVM time out at the next check:
  "Timed out" for everyone in the same second. Causes: NTP or Windows time sync stepping the clock, a VM pause or
  live migration, a laptop resuming from sleep (client side).
- A step backward delays timeout detection by the size of the step, so a dead connection lingers.
- **Look for:** many "Timed out" disconnects in the same second, with no lag spike around them.

### 3. autoRead during login

Fixed in 4.0.19 (`c2dc993c42`) and 4.0.24 (`bcfd6da1dd`).

- Vanilla and FML toggle `autoRead` constantly around login: `dispatchPacket` turns it off before a packet that
  changes the protocol state, `setConnectionState` turns it back on, `NetworkDispatcher` turns it off in
  `insertIntoChannel` and on in `handlerAdded`, `kickWithMessage` turns it off. The calls come from the server
  thread, the client thread and the IO threads.
- In 4.0.10:
  - `setAutoRead(false)` only takes effect at the start of the next read, and that read still delivers one more
    batch (up to 64 KiB). Code that turns reading off to make sure nothing is decoded before a state change is not
    protected.
  - `setAutoRead` is a plain volatile read, then a write. Netty's fix describes a livelock where toggles from
    several threads end with `autoRead` on and no read interest, so nothing is read until the timeout.
  - `EmbeddedChannel` ignores `autoRead` entirely (still true in 4.2).
- ArchaicFix's `fixLoginRaceCondition` ("Attempt to fix hanging in SP when logging in", default on) works around
  the resulting singleplayer login hang on the FML side.
- Confidence: medium-low. The defects are in the 4.0.10 code. In vanilla's own sequences the replies cannot arrive
  before the state switch, and the `true` toggles all run on the IO thread, so I did not trace a specific sequence
  to a hang.

### 4. Handlers added from another thread see events before handlerAdded

Fixed in 4.0.34 (`ba381f1a27`) and 4.0.35 (`b6882a5d52`).

- In 4.0.10, `pipeline.addBefore(...)` from a non-IO thread links the handler in at once, but runs `handlerAdded`
  later on the IO thread.
- FML adds `NetworkDispatcher` from the server thread (`NetHandlerLoginServer` calls
  `FMLNetworkHandler.fmlServerHandshake`), and only sets its state and starts the handshake in `handlerAdded`.
  Anything read in between goes through a dispatcher whose state is still `null`. The handshake then runs on the IO
  thread.
- In practice the client sends nothing in that window, so this is low risk. Newer Netty skips a handler until its
  `handlerAdded` has run, which is a behaviour change to keep in mind for phase 6.

### 5. LocalChannel (singleplayer and LAN host)

Fixed in 4.0.19 (`c97c8b7b8e`, NPE when a close races the registration) and 4.0.31 (`37eedb60fe`, `e3f1416478`,
event ordering).

- The client and the integrated server put their local channels on different event loops ("Netty Client IO" and
  "Netty IO"), which is where the ordering bugs live. A write promise completes before the peer has the data, so a
  close from a write listener can overtake the data.
- 4.0.10 also drops anything still queued in a local channel's inbound buffer when it closes. That queue only fills
  while `autoRead` is off. Delivery of data written right before a close was still being fixed in 2026 (4.2.20,
  `cfe1557e92`).
- Most likely symptom: losing the last packets, such as a disconnect reason. Confidence: low to medium, and
  singleplayer only.

### 6. ByteToMessageDecoder semantics

Changed in 4.0.33 (`99dfc9ea79`). Removal fixes in 4.0.31, 4.0.34 and 4.1.43.

- 4.0.10 decodes every frame of a read before passing any of them on. A handler that changes the pipeline or the
  protocol in reaction to frame N cannot affect frames N+1 and later from the same read: they were already split.
- When a decoder is removed mid-stream, its leftover bytes are forwarded before the frames it had already decoded,
  out of order (fixed in 4.1.43).
- Phase 4 is already safe: the decompressor sits after the splitter and recognizes START by its bytes. Any other
  code that switches framing mid-stream must account for this.

### 7. Smaller items

- `DefaultPromise` listener ordering, and a late listener that was never notified (4.0.16, 4.0.20). Vanilla adds its
  listeners on the event loop, so it is probably not affected.
- `maxMessagesPerRead` is 1 for sockets in 4.0.10 (16 since 4.0.14): one socket read per wakeup. Throughput only.
- If `select()` keeps throwing `IOException`, 4.0.10 logs `Unexpected exception in the selector loop.` and sleeps
  1 s, in a loop, freezing every channel on that thread. Recovery (rebuilding the selector) came in 4.1.33.

### Not a Netty bug: FML's shared EmbeddedChannel

Present on every Netty version, because it comes from how FML uses `EmbeddedChannel`.

- `EmbeddedChannel` keeps the first exception that reaches the end of its pipeline in `lastException`, and throws it
  at the next `writeInbound`, `writeOutbound` or `checkException`.
- FML has one `EmbeddedChannel` per mod channel and side, shared by all players and threads.
  `SimpleNetworkWrapper.sendTo` and the other send methods use `writeAndFlush(...)` with
  `FIRE_EXCEPTION_ON_FAILURE`, so a failed send ends up in `lastException`: a mod's `toBytes` throwing, or
  `FMLNetworkException` for a missing target.
- The next inbound packet on that channel, from any player, runs `FMLProxyPacket.processPacket`, which calls
  `writeInbound`. The stale exception is thrown there, and FML calls
  `rejectHandshake("A fatal error has occured, this connection is terminated")`. **The wrong player is kicked**, and
  the logged stack trace is from the failed send, not from the kicked player's packet.
- The shared channel is not thread-safe either. `FML_MESSAGETARGET` and `FML_MESSAGETARGETARGS` are two separate
  attribute writes, and `outboundMessages` is an `ArrayDeque`. Sends from two threads can go to the wrong target, and
  `generatePacketFrom` can return another thread's packet.
- **Look for:** `There was a critical exception handling a packet on channel` with an encode path (`toBytes`,
  `encodeInto`) in its stack trace, and `More than one exception was raised`.
- Fixable with a mixin on our side, independent of any Netty upgrade.

### Java 8 and Java 17+ run 4.0.10 differently

Measured with Netty 4.0.10's `PlatformDependent` on JDK 8, 17 and 21 (17 and 21 with lwjgl3ify's `--add-opens` list):

| | JDK 8 | JDK 17 and 21 |
|---|---|---|
| `hasUnsafe()` | true | false |
| `ioBuffer()` | `UnpooledUnsafeDirectByteBuf` | `UnpooledHeapByteBuf` |
| Log line | none | `Your platform does not provide complete low-level API for accessing direct buffers reliably...` |

- **Java 8.** Every `MessageToByteEncoder` stage (encoder, prepender, encrypt) allocates a new direct buffer per
  packet: `ByteBuffer.allocateDirect` (global accounting in `Bits`, zeroing, a `Cleaner` registered under a global
  lock), then `Cleaner.clean()` on release. Leak detection samples 1 in 113 direct buffers (the `LEAK:` warnings).
  When direct memory runs short, `Bits.reserveMemory` calls `System.gc()` and sleeps.
- **Java 17+.** 4.0.10 cannot use the JDK 9+ cleaner, so it turns Unsafe off and uses heap buffers everywhere. The
  JDK copies them into its own temporary direct buffers on each socket call. No leak detection at all.
- A network problem reported on only one Java family may come from this split.

### Checked and ruled out

Listed so nobody spends time on them again:

- **FML's `VanillaTimeoutWaiter`** schedules a 10-hour task per login and cancels it. 4.0.10 purges cancelled
  scheduled tasks every second, so it is not a leak.
- **Capacity growth dropping bytes** (#1800, fixed in 4.0.12): only bytes before `readerIndex` were lost. Vanilla,
  FML and GT write with `readerIndex` at 0.
- **Gathering write array growth** (#1890, fixed in 4.0.11): needs composite buffers. Vanilla writes one buffer per
  packet.
- **`nioBuffer(index, length)` position** (#1925, fixed in 4.0.11): not on our path.
- **`ByteBufInputStream.read()` ignoring the length limit** (fixed in 4.1.38): AE2's
  `PacketInterfaceTerminalUpdate` uses a bounded stream, but stays in bounds with valid gzip data.
- **Pooled allocator races** (#4198 and others): introduced after 4.0.10.

## Part 2: what 4.0.56 brings

Compatibility:

- `ByteBuf` has the same 143 abstract methods (the diff is whitespace only), so `PacketBuffer` and mod classes
  extending `ByteBuf` keep working.
- `AttributeKey(String)` is still public (deprecated), and `ctx.attr()` is still per handler.
- **Two link breaks** in the whole pack (GTNH daily 804, 348k classes, scanned with `tools/nettylink`):
  `ChannelInboundInvoker` and `ChannelOutboundInvoker` are gone, so FML's `NetworkDispatcher` call to `ctx.read()`
  does not link (every modded login), and Forestry reads the removed `EmptyArrays.EMPTY_INTS`. The override
  prototype relinks both at class load.
- **`readBytes(int)` changed in 4.0.37:** it allocates with the source buffer's allocator, where 4.0.10 always
  returned a heap buffer. Vanilla's `PacketBuffer.readStringFromBuffer` and 11 files in 9 GTNH mods call
  `readBytes(n).array()`, which throws on a direct buffer. So the override runs 4.0.56 with
  `-Dio.netty.noUnsafe=true`: without Unsafe, Netty keeps every buffer on the heap (`-Dio.netty.noPreferDirect=true`
  also works but keeps Unsafe; see `launcher/README.md` for the comparison).
- **Avoid 4.0.20 to 4.0.26.** The new attribute map corrupts its list when an attribute is removed twice (fixed in
  4.0.27), and FML's `NetworkDispatcher.cleanAttributes` removes `FML_DISPATCHER` twice on the same channel.
- Mixins into Netty classes (checked with `javap`): BugTorch's `doWrite` overwrite and ArchaicFix's
  `EmbeddedChannel` mixin find all their targets in 4.0.56. BugTorch's overwrite puts the 4.0.10 write loop back.
- Behaviour to re-test in game:
  - `EmbeddedChannel` adds its handlers after registration (4.0.30);
  - decoders pass frames on one at a time (4.0.33);
  - `ReadTimeoutHandler` is now an `IdleStateHandler`.

Gains:

- Every fix in Part 1 except the later 4.1+ ones (selector `IOException` recovery, LocalChannel close delivery,
  later decoder fixes).
- Native epoll transport (4.0.16+, Linux x86_64, needs the native jar). Vanilla hardcodes the NIO classes, so it
  needs a mixin.
- Pooled allocator with thread-local caches (4.0.18+), opt-in with `-Dio.netty.allocator.type=pooled`. The default
  stays unpooled in 4.0.
- Leak detection levels (`-Dio.netty.leakDetectionLevel=advanced` records accesses): a tool to find which mod leaks.
- Lock-free attribute map (4.0.20), lock-free task queue (4.0.18), `maxMessagesPerRead` 16.
- Java 9+ handling in `PlatformDependent` (4.0.45+). Measured on Java 25: 4.0.56 does find `sun.misc.Unsafe`, so
  it also triggers the JDK 24+ "terminally deprecated" Unsafe warnings. `-Dio.netty.noUnsafe=true` keeps the mode
  stock 4.0.10 runs on Java 17+ (no Unsafe, heap buffers), and is what the override uses.
- Downside: end of life since 2018, no fixes after that.

## Part 3: what 4.2.21 adds, and what it breaks

Breaks, on top of the list in PLAN.md phase 6:

- The `AttributeKey` constructor is private.
- `ByteBuf` has 181 abstract methods, 38 more than 4.0: `AbstractMethodError` on `PacketBuffer` and on any mod class
  extending `ByteBuf`.
- `ctx.attr()` returns the channel's attribute (since 4.1.0). FML keeps its handshake state
  (`HandshakeMessageHandler`) and its inbound packet tracker (`FMLIndexedMessageToMessageCodec`) in `ctx.attr()`.
  Each one is alone in its `EmbeddedChannel`, so probably fine, but the meaning changes.
- Default allocator: pooled in 4.1, `adaptive` in 4.2. A buffer a mod never releases becomes a real leak.
- `NioEventLoopGroup` is deprecated (replaced by `MultiThreadIoEventLoopGroup` with `NioIoHandler`), but still works.
- Modules are split (`codec-base`, `codec-compression` and so on). Java 8 baseline, fine for us.
- BugTorch's overwrite targets 4.0 internals.

Gains over 4.0.56:

- Allocators: thread-local caches, no `Cleaner` per allocation, real direct buffers on Java 9+.
- Transports: epoll, kqueue, io_uring.
- `FlushConsolidationHandler` (since 4.1.3). It only merges flushes already queued on the event loop, while vanilla's
  writes arrive from the server thread over the whole tick, so phase 3's tick-aligned flush is still the better fit.
- `IdleStateHandler(observeOutput)`, `WriteBufferWaterMark`, and accurate pending-byte accounting for writes made
  outside the event loop. Relevant if we ever add backpressure: vanilla never checks `isWritable()`.
- Compression codecs (zstd, brotli, lz4, snappy) in `codec-compression`, if phase 4 ever wants more than
  `java.util.zip`.
- Fixes after 2018 that also apply to the 4.0 code:
  - selector `IOException` recovery (4.1.33);
  - `ByteToMessageDecoder`: overread with `autoRead` off (4.1.38), removal order (4.1.43), leak on a failed
    expansion (4.1.44), reentrancy (2025);
  - LocalChannel data written before a close (4.2.20);
  - security fixes.

Still there in 4.2:

- FML's shared `EmbeddedChannel` problems.
- A blocked IO thread delays the reads of every other channel on it, and their read timeouts can fire as soon as it
  unblocks.
- No backpressure in vanilla.

## Next steps

1. Search existing logs and reports (GTNH issues, Discord, crash reports) for the "Look for" strings above. Each one
   points to one mechanism.
2. Small backports as mixins on 4.0.10, without waiting for phase 6:
   - update `selectionKey` after `rebuildSelector()`, and clear the interrupt flag in the select loop;
   - replace the vanilla `timeout` handler with one based on `nanoTime` (we already hook the pipeline);
   - make `setAutoRead` an atomic get-and-set;
   - FML: check or clear `lastException` right after outbound sends, so it cannot reach the next inbound packet.
3. Phase 6: 4.0.56 first. It brings most of the fixes with little breakage, and the override prototype handles the
   two link breaks (see PLAN.md phase 6 and `launcher/README.md`). Then 4.1 or 4.2 for the allocators and
   transports. On Java 8 servers (Thermos, hosts that force Java 8) only the start script route works, since
   lwjgl3ify is not part of the Java 8 builds.
