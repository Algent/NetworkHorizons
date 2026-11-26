package dev.aruka.networkhorizons.core;

import java.util.Map;

import org.apache.logging.log4j.LogManager;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

/**
 * Coremod entry point, only for {@link NettyLinkFixer}: FML registers the transformer before Forge and mods load
 * their network classes. Returns no transformer on stock Netty.
 */
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.Name("NetworkHorizons")
public class NetworkHorizonsCore implements IFMLLoadingPlugin {

    private static final boolean NEWER_NETTY = NettyEnvironment
        .isNewerThanStock(NetworkHorizonsCore.class.getClassLoader());

    static {
        if (NEWER_NETTY) LogManager.getLogger("networkhorizons")
            .info("Netty newer than 4.0.10 on the classpath: relinking code compiled against 4.0.10");
    }

    /** FML calls this twice (null check, then loop): keep it free of side effects. */
    @Override
    public String[] getASMTransformerClass() {
        return NEWER_NETTY ? new String[] { NettyLinkFixer.class.getName() } : new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {}

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
