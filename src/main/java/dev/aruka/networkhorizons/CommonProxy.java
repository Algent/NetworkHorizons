package dev.aruka.networkhorizons;

import net.minecraft.server.MinecraftServer;

import com.gtnewhorizon.gtnhlib.config.ConfigException;
import com.gtnewhorizon.gtnhlib.config.ConfigurationManager;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;
import dev.aruka.networkhorizons.command.NetworkHorizonsCommand;
import dev.aruka.networkhorizons.config.EstimatorConfig;
import dev.aruka.networkhorizons.config.StatsConfig;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        try {
            ConfigurationManager.registerConfig(StatsConfig.class);
            ConfigurationManager.registerConfig(EstimatorConfig.class);
        } catch (ConfigException e) {
            throw new RuntimeException(e);
        }

        NetworkHorizons.LOG.info("NetworkHorizons version " + Tags.VERSION);
    }

    public void init(FMLInitializationEvent event) {}

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();
        NetworkHorizonsCommand.register(() -> server.getFile("."));
    }
}
