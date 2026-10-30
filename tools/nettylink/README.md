# nettylink

Checks every `io/netty/*` reference in a set of jars against another Netty version, the way the JVM linker
resolves them: missing classes, methods (searched in the class, its superclasses and superinterfaces), fields,
and abstract methods a mod class no longer implements. Used to vet the Netty override in `launcher/`.

Needs Java 11+ (single-file launch) and ASM 9 on the classpath:

```bash
java -cp asm-9.9.1.jar tools/nettylink/NettyLinkCheck.java netty-all-4.0.56.Final.jar minecraft.jar forge-universal.jar path/to/mods
```

Directories are scanned for `*.jar`, nested jars included. Run it against the stock `netty-all-4.0.10.Final.jar`
too and compare: the scanner does not index JDK classes, so methods inherited from them (`InputStream.close()`,
`Iterable.iterator()`, `Executor.execute()`) show up in both runs and are not real breaks.

Result on GTNH daily 804 (vanilla client and server, Forge, lwjgl3ify, all mods of the four packs, 348k classes,
29k references) against 4.0.56: two breaks, both relinked by NetworkHorizons (`NettyLinkFixer`):

- `ChannelHandlerContext.read()Lio/netty/channel/ChannelOutboundInvoker;` in Forge's `NetworkDispatcher`
- `EmptyArrays.EMPTY_INTS` in Forestry
