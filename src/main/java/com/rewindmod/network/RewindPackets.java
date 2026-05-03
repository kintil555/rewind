package com.rewindmod.network;

import net.minecraft.util.Identifier;

/**
 * Packet IDs used by RewindMod.
 */
public class RewindPackets {

    /** Client → Server: player pressed the rewind key */
    public static final Identifier REWIND_REQUEST = Identifier.of("rewindmod", "rewind_request");

    /** Server → Client: sync cooldown ticks */
    public static final Identifier COOLDOWN_SYNC = Identifier.of("rewindmod", "cooldown_sync");

    /** Server → Client: signal that rewind animation started, with total frame count */
    public static final Identifier REWIND_START = Identifier.of("rewindmod", "rewind_start");

    private RewindPackets() {}
}
