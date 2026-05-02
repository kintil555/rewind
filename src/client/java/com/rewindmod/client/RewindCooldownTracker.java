package com.rewindmod.client;

import com.rewindmod.world.RewindManager;

/**
 * Tracks rewind cooldown on the client side.
 * Updated both by the server sync packet and by local prediction.
 */
public class RewindCooldownTracker {

    private static RewindCooldownTracker INSTANCE;

    public static RewindCooldownTracker getInstance() {
        if (INSTANCE == null) INSTANCE = new RewindCooldownTracker();
        return INSTANCE;
    }

    private int cooldownTicks = 0;

    /**
     * Called every client tick.
     */
    public void tick() {
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }
    }

    /**
     * Called when the server sends a cooldown sync packet.
     */
    public void syncFromServer(int ticks) {
        this.cooldownTicks = ticks;
    }

    /**
     * Called when the player triggers a rewind locally (for immediate feedback).
     */
    public void triggerLocalCooldown() {
        this.cooldownTicks = RewindManager.COOLDOWN_TICKS;
    }

    public int getCooldownTicks() {
        return cooldownTicks;
    }

    public boolean isOnCooldown() {
        return cooldownTicks > 0;
    }

    /**
     * Returns progress from 0.0 (full cooldown) to 1.0 (ready).
     */
    public float getProgress() {
        if (cooldownTicks <= 0) return 1.0f;
        return 1.0f - ((float) cooldownTicks / (float) RewindManager.COOLDOWN_TICKS);
    }

    public int getCooldownSeconds() {
        return (cooldownTicks + 19) / 20;
    }
}
