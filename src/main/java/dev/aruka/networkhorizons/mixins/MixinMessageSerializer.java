package dev.aruka.networkhorizons.mixins;

import net.minecraft.network.Packet;
import net.minecraft.util.MessageSerializer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.aruka.networkhorizons.stats.Direction;
import dev.aruka.networkhorizons.stats.TrafficRecorder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

@Mixin(MessageSerializer.class)
public abstract class MixinMessageSerializer {

    // encode is a Netty method (not obfuscated) and class names are deobfuscated at runtime, so no remapping
    @Inject(
        method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/Packet;Lio/netty/buffer/ByteBuf;)V",
        at = @At("TAIL"),
        remap = false)
    private void networkhorizons$countEncoded(ChannelHandlerContext ctx, Packet packet, ByteBuf out, CallbackInfo ci) {
        TrafficRecorder.record(ctx.channel(), Direction.OUT, packet, out.readableBytes());
    }
}
