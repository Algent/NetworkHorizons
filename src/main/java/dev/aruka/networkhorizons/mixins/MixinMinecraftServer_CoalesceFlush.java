package dev.aruka.networkhorizons.mixins;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.aruka.networkhorizons.flush.FlushCoalescer;

@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer_CoalesceFlush {

    @Inject(method = "tick", at = @At("HEAD"))
    private void networkhorizons$markServerThread(CallbackInfo ci) {
        FlushCoalescer.serverThread = Thread.currentThread();
    }

    /** After the post-tick handlers of mods, which run after vanilla's network tick flush. */
    @Inject(method = "tick", at = @At("TAIL"))
    private void networkhorizons$flushEndOfTick(CallbackInfo ci) {
        FlushCoalescer.flushPending();
    }
}
