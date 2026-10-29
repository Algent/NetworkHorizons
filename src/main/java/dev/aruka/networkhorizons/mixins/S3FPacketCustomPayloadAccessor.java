package dev.aruka.networkhorizons.mixins;

import net.minecraft.network.play.server.S3FPacketCustomPayload;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The vanilla getters are {@code @SideOnly(CLIENT)}, so they are stripped on dedicated servers. */
@Mixin(S3FPacketCustomPayload.class)
public interface S3FPacketCustomPayloadAccessor {

    @Accessor("field_149172_a")
    String networkhorizons$getChannel();

    @Accessor("field_149171_b")
    byte[] networkhorizons$getData();
}
