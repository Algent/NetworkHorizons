package dev.aruka.networkhorizons.config;

import com.gtnewhorizon.gtnhlib.config.Config;

import dev.aruka.networkhorizons.NetworkHorizons;

@Config(modid = NetworkHorizons.MODID, category = "stats")
public class StatsConfig {

    @Config.Comment("Count sent and received packets per connection (/neth stats). Counter updates only, no allocation per packet.")
    @Config.DefaultBoolean(true)
    public static boolean enabled = true;

    @Config.Comment("Rows shown per direction by /neth stats when no count is given.")
    @Config.DefaultInt(10)
    @Config.RangeInt(min = 1, max = 100)
    public static int defaultTop = 10;
}
