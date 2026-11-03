package dev.aruka.networkhorizons.compress;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.network.play.server.S3FPacketCustomPayload;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import dev.aruka.networkhorizons.NetworkHorizons;
import dev.aruka.networkhorizons.config.CompressionConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

/**
 * HELLO (server, at login) / REPLY (client) / START (server). Only START is timing critical, and it is handled in the
 * pipeline by {@link FrameCompressor#install} and {@link FrameDecompressor}, not here.
 */
public final class CompressionHandshake {

    private CompressionHandshake() {}

    public static void register() {
        CompressionHandshake handler = new CompressionHandshake();
        NetworkRegistry.INSTANCE.newEventDrivenChannel(Protocol.CHANNEL)
            .register(handler);
        FMLCommonHandler.instance()
            .bus()
            .register(handler);
    }

    static boolean offered(String player, NetworkManager manager) {
        if (!CompressionConfig.enabled || manager.isLocalChannel()) return false;
        for (String excluded : CompressionConfig.excludedPlayers) {
            if (excluded.equalsIgnoreCase(player)) return false;
        }
        return true;
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        NetworkManager manager = player.playerNetServerHandler.netManager;
        if (!offered(player.getCommandSenderName(), manager)) return;
        manager.scheduleOutboundPacket(
            new S3FPacketCustomPayload(Protocol.CHANNEL, Protocol.hello(CompressionConfig.codec)));
    }

    @SubscribeEvent
    public void onServerPacket(FMLNetworkEvent.ServerCustomPacketEvent event) {
        byte[] msg = bytes(event.packet.payload());
        if (msg.length < 3 || msg[0] != Protocol.REPLY) return;
        String player = ((NetHandlerPlayServer) event.handler).playerEntity.getCommandSenderName();
        if (msg[1] != Protocol.VERSION) {
            NetworkHorizons.LOG.warn(
                "{} runs NetworkHorizons protocol {}, this server {}: not compressing",
                player,
                msg[1],
                Protocol.VERSION);
            return;
        }
        Codec codec = Codec.byId(msg[2]);
        if (codec == null) {
            NetworkHorizons.LOG.info("{} declined compression", player);
            return;
        }
        if (codec != CompressionConfig.codec || !offered(player, event.manager)) return;
        FrameCompressor.install(event.manager.channel(), codec, CompressionConfig.level, player);
    }

    @SubscribeEvent
    public void onClientPacket(FMLNetworkEvent.ClientCustomPacketEvent event) {
        byte[] msg = bytes(event.packet.payload());
        if (msg.length < 3 || msg[0] != Protocol.HELLO) return;
        Codec offered = msg[1] == Protocol.VERSION ? Codec.byId(msg[2]) : null;
        NetworkManager manager = event.manager;
        Channel channel = manager.channel();
        // the decompressor must be in place before the reply leaves, so before START can arrive
        channel.eventLoop()
            .execute(() -> {
                Codec accepted = CompressionConfig.accept && offered != null && FrameDecompressor.install(channel)
                    ? offered
                    : null;
                manager.scheduleOutboundPacket(new C17PacketCustomPayload(Protocol.CHANNEL, Protocol.reply(accepted)));
                NetworkHorizons.LOG.info(
                    "Server offered compression (protocol {}, codec {}): {}",
                    msg[1],
                    msg[2],
                    accepted != null ? "accepted" : "declined");
            });
    }

    private static byte[] bytes(ByteBuf buf) {
        byte[] b = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), b);
        return b;
    }
}
