package dev.aruka.networkhorizons.config;

import com.gtnewhorizon.gtnhlib.config.Config;

import dev.aruka.networkhorizons.NetworkHorizons;

@Config(modid = NetworkHorizons.MODID, category = "estimator")
public class EstimatorConfig {

    @Config.Comment({ "Keys reported separately by /neth estimate: results are shown with and without them.",
        "Same labels as /neth stats. Default: Galacticraft gear slot packets, sent 9 times per tick per player nearby",
        "by a Galacticraft bug, which would make compression look better than it is once fixed." })
    @Config.DefaultStringList({ "[GalacticraftCore]#0" })
    public static String[] excludedKeys = { "[GalacticraftCore]#0" };

    @Config.Comment("Duration of /neth estimate start when none is given, in seconds.")
    @Config.DefaultInt(600)
    @Config.RangeInt(min = 10, max = 86400)
    public static int defaultSeconds = 600;

    @Config.Comment("Frames waiting for the estimator thread are dropped (and counted) beyond this many megabytes.")
    @Config.DefaultInt(64)
    @Config.RangeInt(min = 1, max = 1024)
    public static int maxQueuedMegabytes = 64;
}
