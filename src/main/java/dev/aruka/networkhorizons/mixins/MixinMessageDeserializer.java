package dev.aruka.networkhorizons.mixins;

import java.util.List;

import net.minecraft.network.Packet;
import net.minecraft.util.MessageDeserializer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.aruka.networkhorizons.stats.Direction;
import dev.aruka.networkhorizons.stats.TrafficRecorder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

@Mixin(MessageDeserializer.class)
public abstract class MixinMessageDeserializer {

    // One decoder per channel, called on its event loop only
    @Unique
    private int networkhorizons$frameSize;
    @Unique
    private int networkhorizons$frameIndex;
    @Unique
    private int networkhorizons$outSize;

    @Inject(
        method = "decode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Ljava/util/List;)V",
        at = @At("HEAD"),
        remap = false)
    private void networkhorizons$beforeDecode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out,
        CallbackInfo ci) {
        networkhorizons$frameIndex = in.readerIndex();
        networkhorizons$frameSize = in.readableBytes();
        networkhorizons$outSize = out.size();
    }

    @Inject(
        method = "decode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Ljava/util/List;)V",
        at = @At("TAIL"),
        remap = false)
    private void networkhorizons$countDecoded(ChannelHandlerContext ctx, ByteBuf in, List<Object> out,
        CallbackInfo ci) {
        if (out.size() > networkhorizons$outSize) {
            // the frame is still in the buffer: decode only moved the reader index
            TrafficRecorder.record(
                ctx.channel(),
                Direction.IN,
                (Packet) out.get(out.size() - 1),
                in,
                networkhorizons$frameIndex,
                networkhorizons$frameSize);
        }
    }
}
