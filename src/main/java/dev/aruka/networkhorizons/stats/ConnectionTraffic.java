package dev.aruka.networkhorizons.stats;

import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;

import io.netty.channel.Channel;

/** Stats of one live connection. */
public final class ConnectionTraffic {

    public final Channel channel;
    /** Swapped on reset. Written only from the channel's event loop otherwise. */
    volatile TrafficStats stats;

    ConnectionTraffic(Channel channel, TrafficStats stats) {
        this.channel = channel;
        this.stats = stats;
    }

    public TrafficStats stats() {
        return stats;
    }

    /** Player name once in play state on a server, remote address otherwise. */
    public String label() {
        Object handler = channel.pipeline()
            .get("packet_handler");
        if (handler instanceof NetworkManager
            && ((NetworkManager) handler).getNetHandler() instanceof NetHandlerPlayServer) {
            NetHandlerPlayServer play = (NetHandlerPlayServer) ((NetworkManager) handler).getNetHandler();
            if (play.playerEntity != null) return play.playerEntity.getCommandSenderName();
        }
        return String.valueOf(channel.remoteAddress());
    }
}
