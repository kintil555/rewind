package com.rewindmod.client;

import com.rewindmod.network.RewindServerNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * Registers client-side packet handlers.
 */
public class RewindClientNetworking {

    public static void register() {
        // NOTE: C2S (RewindRequestPayload) and S2C payloads are already registered
        // server-side in RewindServerNetworking.register(). Do NOT re-register here.

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
