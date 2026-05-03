package com.rewindmod.mixin;

import com.rewindmod.world.RewindManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Hooks into ServerWorld#tick to capture snapshots every game tick.
 */
@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin {

    @Shadow @Final private MinecraftServer server;

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("TAIL"))
    private void rewindmod$onTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        RewindManager.getInstance().onServerTick(server);
    }
}
