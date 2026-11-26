# NetworkHorizons

Network traffic measurement and optimization for modded Minecraft 1.7.10, aimed at GTNH first.

The idea is to measure first, then optimize: find out where the bytes go (per packet type, per mod channel,
per player), estimate what compression would gain on real traffic, and only then change what goes over
the wire, in steps the server can switch off.

See [PLAN.md](PLAN.md) for the phases, design notes and open questions, and [NETTY.md](NETTY.md) for how
Netty 4.0.10 (shipped with 1.7.10) compares with 4.0.56 and 4.2.21. The optional, experimental Netty 4.0.56
override is described in [launcher/README.md](launcher/README.md).

## Status

Nothing is implemented yet. The project currently builds a mod that loads and does nothing:

- Forge 1.7.10 mod skeleton (GTNH buildscript), optional on both sides.
- Mixins enabled, with an empty, early-loaded mixin config, ready for hooks into vanilla network classes.

## Building

```
./gradlew build
```

The jar ends up in `build/libs/`.

## License

LGPL-3.0, see [LICENSE](LICENSE).
