package dev.aruka.networkhorizons.mixins;

import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import dev.aruka.networkhorizons.config.FlushConfig;
import dev.aruka.networkhorizons.flush.CoalescingConnection;
import dev.aruka.networkhorizons.flush.FlushCoalescer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.concurrent.GenericFutureListener;

/** Phase 3: write without flushing when {@link FlushCoalescer#shouldDefer} allows it; vanilla path otherwise. */
@Mixin(NetworkManager.class)
public abstract class MixinNetworkManager_CoalesceFlush implements CoalescingConnection {

    @Shadow
    private Channel channel;

    @Shadow
    @Final
    private boolean isClientSide;

    @Shadow
    public abstract boolean isLocalChannel();

    @Unique
    private boolean networkhorizons$pending;

    @Inject(method = "dispatchPacket", at = @At("HEAD"), cancellable = true)
    private void networkhorizons$deferFlush(Packet packet, GenericFutureListener[] listeners, CallbackInfo ci) {
        EnumConnectionState current = channel.attr(NetworkManager.attrKeyConnectionState)
            .get();
        if (!FlushCoalescer.shouldDefer(
            FlushConfig.coalesce,
            isClientSide,
            isLocalChannel(),
            listeners.length,
            current,
            EnumConnectionState.func_150752_a(packet),
            packet instanceof FMLProxyPacket)) {
            return;
        }
        // same as vanilla's off-event-loop branch (no state change here), with write instead of writeAndFlush
        Channel ch = channel;
        ch.eventLoop()
            .execute(
                () -> ch.write(packet)
                    .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE));
        FlushCoalescer.deferred(this);
        ci.cancel();
    }

    @Inject(method = "processReceivedPackets", at = @At("TAIL"))
    private void networkhorizons$afterTickFlush(CallbackInfo ci) {
        FlushCoalescer.flushed(this);
    }

    @Override
    public boolean networkhorizons$isPending() {
        return networkhorizons$pending;
    }

    @Override
    public void networkhorizons$setPending(boolean pending) {
        networkhorizons$pending = pending;
    }

    @Override
    public Channel networkhorizons$channel() {
        return channel;
    }
}
