package com.rewindmod.world;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Singleton accumulator for block changes in the current tick.
 *
 * Flow:
 *  1. BlockChangeMixin intercepts World.setBlockState() → pushes a record here.
 *  2. WorldSnapshot.capture() drains the list into the snapshot.
 *  3. RewindManager.applyFrame() replays each record in reverse (after → before).
 *
 * This approach is delta-based: we don't snapshot the whole chunk, only changed blocks,
 * keeping memory usage very low even over 5+ seconds of recording.
 */
public class BlockChangeTracker {

    private static final BlockChangeTracker INSTANCE = new BlockChangeTracker();

    public static BlockChangeTracker getInstance() { return INSTANCE; }

    /** Accumulates changes for the tick currently being processed. */
    private final List<BlockChangeRecord> pending = new ArrayList<>();

    /** Whether we are currently replaying (to avoid recording our own restores). */
    private boolean replaying = false;

    private BlockChangeTracker() {}

    // Called by BlockChangeMixin
    public void record(BlockPos pos, BlockState before, BlockState after) {
        if (replaying) return; // Don't self-record rewind restores
        pending.add(new BlockChangeRecord(pos, before, after));
    }

    /** Drain all pending records into a snapshot-bound list. */
    public List<BlockChangeRecord> drainAndGet() {
        List<BlockChangeRecord> copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }

    public void setReplaying(boolean replaying) { this.replaying = replaying; }
    public boolean isReplaying() { return replaying; }
}
