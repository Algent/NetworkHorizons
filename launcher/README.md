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
- **The other risk** is behaviour, inside one game: some defaults changed. The known one: 4.0.56 prefers direct
  buffers, and vanilla calls `array()` on buffers, which fails on direct buffers. Both setups below pass
  `-Dio.netty.noPreferDirect=true`. It must be on the JVM command line: in GTNH, Netty reads it during coremod
  discovery (FalsePattern's dependency loader uses Netty), before any mod code runs. NetworkHorizons logs an error
  at startup when it is missing.
- **Check what runs:** `/neth info` (server) or `/nethc info` (client) shows the Netty version, the jar it was
  loaded from, and whether direct buffers are preferred. The same line is in the log at startup
  (`[networkhorizons]: Netty ...`).

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
`+jvmArgs` is a Prism feature; on MultiMC add `-Dio.netty.noPreferDirect=true` in the instance's Java settings.

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
java -Xms6G -Xmx6G -XX:+UseStringDeduplication -XX:+UseCompressedOops -XX:+UseCodeCacheFlushing -Dfml.readTimeout=180 -Duser.language=en -Dio.netty.noPreferDirect=true -cp libraries/io/netty/netty-all/4.0.56.Final/netty-all-4.0.56.Final.jar:forge-1.7.10-10.13.4.1614-1.7.10-universal.jar cpw.mods.fml.relauncher.ServerLaunchWrapper nogui
```

**Java 17+ pack** (`startserver-java9.sh`; same `;` rule for `startserver-java9.bat`):

```bash
java -Xms6G -Xmx6G -Dfml.readTimeout=180 -Duser.language=en -Dio.netty.noPreferDirect=true @java9args.txt -cp libraries/io/netty/netty-all/4.0.56.Final/netty-all-4.0.56.Final.jar:lwjgl3ify-forgePatches.jar me.eigenraven.lwjgl3ify.rfb.entry.ServerMain nogui
```

The main classes are the `Main-Class` of the jar normally passed to `-jar`. To remove: restore the original line.

## Development

`./gradlew runServer -PnettyOverride=4.0.56.Final` (or `runClient`) puts that Netty first on the dev classpath,
with the same JVM flag.
