package com.rewindmod.client;

import com.rewindmod.network.RewindServerNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Registers client-side packet handlers.
 */
public class RewindClientNetworking {

    public static void register() {
        // NOTE: C2S payloads are registered server-side in RewindServerNetworking.register().
        // S2C payloads are already registered server-side too.
        // Client only needs to register RECEIVERS for S2C packets.

        // Handle cooldown sync
        ClientPlayNetworking.registerGlobalReceiver(
                RewindServerNetworking.CooldownSyncPayload.ID,
                (payload, context) -> {
                    context.client().execute(() -> {
                        RewindCooldownTracker.getInstance().syncFromServer(payload.cooldownTicks());
                    });
                }
        );

        // Handle rewind start — trigger client-side visual effect
        ClientPlayNetworking.registerGlobalReceiver(
                RewindServerNetworking.RewindStartPayload.ID,
                (payload, context) -> {
                    context.client().execute(() -> {
                        RewindEffectRenderer.getInstance().startRewind(payload.totalFrames());
                    });
                }
        );
    }

    public static void sendRewindRequest() {
        ClientPlayNetworking.send(new RewindServerNetworking.RewindRequestPayload());
    }
}

