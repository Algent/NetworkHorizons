package dev.aruka.networkhorizons.config;

import com.gtnewhorizon.gtnhlib.config.Config;

import dev.aruka.networkhorizons.NetworkHorizons;

@Config(modid = NetworkHorizons.MODID, category = "flush")
public class FlushConfig {

    @Config.Comment({ "Server only: packets sent from the server thread in play state are written without flushing,",
        "and go out together at the network tick and at the end of the server tick, instead of one TCP segment each.",
        "Kicks, disconnects, other threads, login and local (singleplayer) connections keep the immediate flush." })
    @Config.DefaultBoolean(true)
    public static boolean coalesce = true;
}
