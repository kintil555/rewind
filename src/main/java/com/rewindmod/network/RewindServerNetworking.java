package com.rewindmod.network;

import com.rewindmod.world.RewindManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * Registers and handles server-side networking for RewindMod.
 */
public class RewindServerNetworking {

    public static void register() {
        // Register the payload types
        PayloadTypeRegistry.playC2S().register(RewindRequestPayload.ID, RewindRequestPayload.CODEC);

        // Handle incoming rewind request from client
        ServerPlayNetworking.registerGlobalReceiver(RewindRequestPayload.ID,
                (payload, context) -> {
                    ServerPlayerEntity player = context.player();
                    context.server().execute(() -> {
                        RewindManager.getInstance().requestRewind(player, context.server());
                    });
                });
    }

    // ──────────────────────────────────────────────────────
    // Payload classes
    // ──────────────────────────────────────────────────────

    /**
     * C2S: Client requests a rewind.
     */
    public record RewindRequestPayload() implements CustomPayload {
        public static final Id<RewindRequestPayload> ID =
                new Id<>(RewindPackets.REWIND_REQUEST);
        public static final PacketCodec<PacketByteBuf, RewindRequestPayload> CODEC =
                PacketCodec.unit(new RewindRequestPayload());

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * S2C: Server syncs cooldown ticks to client.
     */
    public record CooldownSyncPayload(int cooldownTicks) implements CustomPayload {
        public static final Id<CooldownSyncPayload> ID =
                new Id<>(RewindPackets.COOLDOWN_SYNC);
        public static final PacketCodec<PacketByteBuf, CooldownSyncPayload> CODEC =
                PacketCodec.ofStatic(
                        (buf, payload) -> buf.writeInt(payload.cooldownTicks()),
                        buf -> new CooldownSyncPayload(buf.readInt())
                );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}
