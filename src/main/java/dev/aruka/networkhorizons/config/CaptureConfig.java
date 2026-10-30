package dev.aruka.networkhorizons.config;

import com.gtnewhorizon.gtnhlib.config.Config;

import dev.aruka.networkhorizons.NetworkHorizons;

@Config(modid = NetworkHorizons.MODID, category = "capture")
public class CaptureConfig {

    @Config.Comment({ "Allow /neth capture and /nethc capture: raw frames of a connection written to",
        "networkhorizons/captures. A capture contains everything the connection carries (chat, positions,",
        "inventories). It stays on this machine; share it only with people you trust." })
    @Config.DefaultBoolean(true)
    public static boolean allowed = true;

    @Config.Comment("Duration of a capture when none is given, in seconds.")
    @Config.DefaultInt(600)
    @Config.RangeInt(min = 10, max = 86400)
    public static int defaultSeconds = 600;

    @Config.Comment("Frames waiting to be written are dropped (and counted) beyond this many megabytes.")
    @Config.DefaultInt(64)
    @Config.RangeInt(min = 1, max = 1024)
    public static int maxQueuedMegabytes = 64;
}
