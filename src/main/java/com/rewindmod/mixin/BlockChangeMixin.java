package com.rewindmod.mixin;

import com.rewindmod.world.BlockChangeTracker;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/**
 * Intercepts World.setBlockState so we can record every block change
 * that happens each tick. Used to build block-change deltas in WorldSnapshot.
 *
 * We capture the state BEFORE and AFTER so rewind can restore it precisely.
 */
@Mixin(World.class)
public abstract class BlockChangeMixin {

    @Inject(
        method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z",
        at = @At("HEAD")
    )
    private void rewindmod$captureBeforeState(BlockPos pos, BlockState newState, int flags,
                                              CallbackInfoReturnable<Boolean> cir) {
        World self = (World)(Object)this;
        // Only track server-side, not client-side
        if (self.isClient) return;
        if (BlockChangeTracker.getInstance().isReplaying()) return;

        BlockState before = self.getBlockState(pos);
        // Avoid recording no-ops (state didn't actually change type)
        if (before == newState) return;

        BlockChangeTracker.getInstance().record(pos, before, newState);
    }
}
