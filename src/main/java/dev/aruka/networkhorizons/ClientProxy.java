package dev.aruka.networkhorizons;

import net.minecraftforge.client.ClientCommandHandler;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import dev.aruka.networkhorizons.command.ClientTrafficCommand;

public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        ClientCommandHandler.instance.registerCommand(new ClientTrafficCommand());
    }
}
