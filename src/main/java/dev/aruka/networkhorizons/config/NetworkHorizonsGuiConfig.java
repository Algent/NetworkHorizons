package dev.aruka.networkhorizons.config;

import net.minecraft.client.gui.GuiScreen;

import com.gtnewhorizon.gtnhlib.config.ConfigException;
import com.gtnewhorizon.gtnhlib.config.SimpleGuiConfig;

import dev.aruka.networkhorizons.NetworkHorizons;

public class NetworkHorizonsGuiConfig extends SimpleGuiConfig {

    public NetworkHorizonsGuiConfig(GuiScreen parent) throws ConfigException {
        super(parent, NetworkHorizons.MODID, "NetworkHorizons");
    }
}
