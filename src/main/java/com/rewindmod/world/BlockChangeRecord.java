package com.rewindmod.world;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/**
 * Records a single block change (pos → before/after state).
 * Captured by BlockChangeMixin whenever setBlockState is called.
 */
public record BlockChangeRecord(BlockPos pos, BlockState before, BlockState after) {

    public BlockChangeRecord {
        // make pos immutable (BlockPos.toImmutable does nothing if already immutable)
        pos = pos.toImmutable();
    }
}
