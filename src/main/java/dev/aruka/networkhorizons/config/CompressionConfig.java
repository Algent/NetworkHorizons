package dev.aruka.networkhorizons.config;

import com.gtnewhorizon.gtnhlib.config.Config;

import dev.aruka.networkhorizons.NetworkHorizons;
import dev.aruka.networkhorizons.compress.Codec;

@Config(modid = NetworkHorizons.MODID, category = "compression")
public class CompressionConfig {

    @Config.Comment({ "Server: compress what the server sends to clients that have NetworkHorizons too.",
        "One stream per connection, one compressed message per tick. Clients without the mod are not affected.",
        "Turning it off sends raw from then on, also on connections already compressed." })
    @Config.DefaultBoolean(true)
    public static boolean enabled = true;

    @Config.Comment("Server: compression codec. Only deflate for now.")
    @Config.DefaultEnum("DEFLATE")
    public static Codec codec = Codec.DEFLATE;

    @Config.Comment({ "Server: deflate level, 1 (fastest) to 9 (smallest). Applies to new connections.",
        "Measured on GTNH traffic, CPU is small at every level." })
    @Config.DefaultInt(6)
    @Config.RangeInt(min = 1, max = 9)
    public static int level = 6;

    @Config.Comment("Server: players never offered compression.")
    @Config.DefaultStringList({})
    public static String[] excludedPlayers = {};

    @Config.Comment("Client: accept compression when a server offers it.")
    @Config.DefaultBoolean(true)
    public static boolean accept = true;
}
