package com.rewindmod.client;

import com.rewindmod.network.RewindServerNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * Registers client-side packet handlers.
 */
public class RewindClientNetworking {

    public static void register() {
        // Register S2C payload type
        PayloadTypeRegistry.playS2C().register(
                RewindServerNetworking.CooldownSyncPayload.ID,
                RewindServerNetworking.CooldownSyncPayload.CODEC
        );

        // Register C2S payload type (needed on client too for sending)
        PayloadTypeRegistry.playC2S().register(
                RewindServerNetworking.RewindRequestPayload.ID,
                RewindServerNetworking.RewindRequestPayload.CODEC
        );

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
