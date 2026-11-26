# Netty 4.0.56 override (optional, experimental)

Minecraft 1.7.10 ships Netty 4.0.10.Final (2013). 4.0.56.Final (2018) is the last release of the same 4.0.x line:
API compatible, with five years of bug fixes. This folder swaps it in **at launch**, on the classpath,
the same way lwjgl3ify replaces libraries. Nothing in the NetworkHorizons jar changes Netty: remove the override
and you are back on stock Netty, with or without the mod.

- **Opt-in.** Nothing here is applied by installing the mod.
- **Requires NetworkHorizons on the same side.** 4.0.56 is not fully binary compatible with code compiled
  against 4.0.10. Scanning GTNH (vanilla, Forge, lwjgl3ify and every mod of the client and server packs,
  348k classes) with `tools/nettylink` finds two breaks: Forge's `NetworkDispatcher` calls `ctx.read()` with a
  return type that no longer exists (every modded login fails with `NoSuchMethodError`), and Forestry reads
  `EmptyArrays.EMPTY_INTS`, removed from Netty. When it sees a newer Netty, NetworkHorizons registers a class
  transformer that relinks both (log: `Relinked ... Netty 4.0.10 reference(s) in ...`). Without the mod, do not
  use the override.
- **Client and server are independent.** Netty does not change the bytes on the wire, so a 4.0.56 client
  can play on a 4.0.10 server and the other way around.
- **The other risk** is behaviour, inside one game: some defaults changed. The known one: since 4.0.37,
  `readBytes(int)` allocates with the source buffer's allocator (4.0.10 always returned a heap buffer), so on
  direct frames it returns a direct buffer. Vanilla's `PacketBuffer.readStringFromBuffer` and 11 files in 9 GTNH
  mods call `readBytes(n).array()`, which fails on a direct buffer. Both setups below pass
  `-Dio.netty.noUnsafe=true`: without `sun.misc.Unsafe`, Netty never prefers direct buffers, so every buffer stays
  on the heap, and it never calls the Unsafe memory-access methods the JDK is phasing out (warning since Java 24).
  That is the mode stock 4.0.10 already runs on Java 17+, now on every Java. On Java 8, stock 4.0.10 used Unsafe
  and direct buffers, so Java 8 changes mode with the override.
  The flag must be on the JVM command line: in GTNH, Netty reads it during coremod discovery (FalsePattern's
  dependency loader uses Netty), before any mod code runs. NetworkHorizons logs an error at startup when Netty
  prefers direct buffers, and a warning when Netty still uses Unsafe (for example with the earlier
  `-Dio.netty.noPreferDirect=true` setup).
- **Mixins into Netty classes** are not seen by `tools/nettylink` (their references point at the mixin class), so
  they are checked separately; see "Mixins into Netty classes" below.
- **Check what runs:** `/neth info` (server) or `/nethc info` (client) shows the Netty version, the jar it was
  loaded from, whether it uses Unsafe and whether direct buffers are preferred. The same line is in the log at
  startup (`[networkhorizons]: Netty 4.0.56.Final from netty-all-4.0.56.Final.jar, Unsafe: false, direct buffers
  preferred: false`).

## Prism Launcher / MultiMC instances (Java 8 and Java 17+)

1. Copy `prism/patches/dev.aruka.networkhorizons.netty.json` into the instance's `patches/` folder
   (create it on Java 8 instances).
2. Add this entry at the end of `"components"` in the instance's `mmc-pack.json`:

   ```json
   {
       "cachedName": "NetworkHorizons Netty",
       "cachedVersion": "4.0.56.Final",
       "uid": "dev.aruka.networkhorizons.netty"
   }
   ```

3. Start the instance. Prism downloads the jar from Maven Central (checksum in the component).

How it works: Prism keeps one copy of each library and replaces it when a later component brings a strictly newer
version (`LaunchProfile::applyLibrary`). So `io.netty:netty-all:4.0.56.Final` replaces the 4.0.10 that the
`net.minecraft` component brings, on plain Java 8 instances and on lwjgl3ify (Java 17+) instances alike.
`+jvmArgs` is a Prism feature; on MultiMC add `-Dio.netty.noUnsafe=true` in the instance's Java settings.

To remove: delete the entry from `mmc-pack.json` (or remove the component in the instance's Version page).

## Dedicated servers

On servers Netty 4.0.10 is not a separate library: it is bundled inside `minecraft_server.1.7.10.jar`.
Putting the 4.0.56 jar **before** it on the classpath makes it win. That means starting the server with `-cp`
and the main class instead of `-jar`.

1. Download `netty-all-4.0.56.Final.jar` into `libraries/io/netty/netty-all/4.0.56.Final/`:
   <https://repo1.maven.org/maven2/io/netty/netty-all/4.0.56.Final/netty-all-4.0.56.Final.jar>
   (SHA-1 `26b28bcb33ec9e4286cd856321eea3df57c31aaf`, 2291648 bytes).
2. Change the `java` line of the start script. Keep your own memory and other flags; only the end changes.

**Java 8 pack** (`startserver.sh`; in `startserver.bat` use `;` instead of `:` between the two jars):

```bash
java -Xms6G -Xmx6G -XX:+UseStringDeduplication -XX:+UseCompressedOops -XX:+UseCodeCacheFlushing -Dfml.readTimeout=180 -Duser.language=en -Dio.netty.noUnsafe=true -cp libraries/io/netty/netty-all/4.0.56.Final/netty-all-4.0.56.Final.jar:forge-1.7.10-10.13.4.1614-1.7.10-universal.jar cpw.mods.fml.relauncher.ServerLaunchWrapper nogui
```

**Java 17+ pack** (`startserver-java9.sh`; same `;` rule for `startserver-java9.bat`):

```bash
java -Xms6G -Xmx6G -Dfml.readTimeout=180 -Duser.language=en -Dio.netty.noUnsafe=true @java9args.txt -cp libraries/io/netty/netty-all/4.0.56.Final/netty-all-4.0.56.Final.jar:lwjgl3ify-forgePatches.jar me.eigenraven.lwjgl3ify.rfb.entry.ServerMain nogui
```

The main classes are the `Main-Class` of the jar normally passed to `-jar`. To remove: restore the original line.

Java 8 servers (Thermos and other Java 8 environments) can only use this route: lwjgl3ify is not part of the
Java 8 builds. Thermos is not tested yet.

## Mixins into Netty classes

`tools/nettylink` cannot see mixins that target Netty classes: their references point at the mixin class. The ones
in GTNH, checked with `javap` against both Netty jars (2026-11-26):

| Mod | Mixin | On 4.0.56 |
|---|---|---|
| BugTorch | `@Overwrite` of `NioSocketChannel.doWrite` (`fixNettyConnectionFailureResourceLeak`, default on) | Applies: the method and every `ChannelOutboundBuffer` call it makes still exist. It puts the 4.0.10 write loop back in place of 4.0.56's. With flush coalescing (phase 3), it is the busy write path. |
| ArchaicFix | `EmbeddedChannel` (`fixLoginRaceCondition`, default on): `writeInbound`, `doBeginRead`, `doClose`, `lastException` | Applies: all targets still exist, and 4.0.56 has no method that clashes with its private `checkException(ChannelPromise)`. |
| Hodgepodge | `Bootstrap.checkAddress` (`fixNettyNPE`, client) | The method exists in neither 4.0.10 nor 4.0.56, so it never applies. |

## Why noUnsafe and not noPreferDirect

`-Dio.netty.noPreferDirect=true` also fixes the `array()` problem, but keeps Unsafe: heap buffers accessed through
Unsafe, a mode no GTNH setup runs today, and on Java 24+ the JDK's "terminally deprecated" Unsafe warning from
Netty (seen on Java 25). `noUnsafe` costs little:

- slightly slower multi-byte reads and writes on heap buffers, noise at Minecraft packet rates;
- the two explicitly allocated direct buffers (GT's pooled `directBuffer()`, Netty's 64 KiB per-thread write
  buffer) are freed by the GC instead of at release, as on stock 4.0.10 with Java 17+; both live for the session;
- no native epoll on 4.0.56 (it needs Unsafe there); transports belong to the later 4.1/4.2 step anyway.

On Java 25 clients the JDK still prints the Unsafe warning once, from GTNHLib's `MemoryUtilities`, not from Netty.

## Development

`./gradlew runServer -PnettyOverride=4.0.56.Final` (or `runClient`, and the modern Java tasks such as
`runServer25` / `runClient25`) puts that Netty first on the dev classpath, with the same JVM flag
(`-PnettyNoUnsafe=false` or `unset` to try without it).
`./gradlew test -PnettyOverride=4.0.56.Final` runs the unit tests on it.

To test a login without clicking: start the server, then
`./gradlew runClient -PnettyOverride=4.0.56.Final --mcArgs=--server --mcArgs=127.0.0.1 --mcArgs=--port --mcArgs=25565`.
