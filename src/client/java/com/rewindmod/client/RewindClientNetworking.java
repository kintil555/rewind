package com.rewindmod.client;

import com.rewindmod.network.RewindServerNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * Registers client-side packet handlers.
 */
public class RewindClientNetworking {

    public static void register() {
        // Register S2C payload type (client needs to know how to receive this)
        PayloadTypeRegistry.playS2C().register(
                RewindServerNetworking.CooldownSyncPayload.ID,
                RewindServerNetworking.CooldownSyncPayload.CODEC
        );

        // NOTE: C2S payload (RewindRequestPayload) is already registered server-side
        // in RewindServerNetworking.register(). Do NOT register it here again.

        // Handle incoming cooldown sync from server
        ClientPlayNetworking.registerGlobalReceiver(
                RewindServerNetworking.CooldownSyncPayload.ID,
                (payload, context) -> {
                    context.client().execute(() -> {
                        RewindCooldownTracker.getInstance().syncFromServer(payload.cooldownTicks());
                    });
                }
        );
    }

    /**
     * Sends a rewind request to the server.
     */
    public static void sendRewindRequest() {
        ClientPlayNetworking.send(new RewindServerNetworking.RewindRequestPayload());
    }
}
