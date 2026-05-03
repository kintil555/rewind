package com.rewindmod.world;

import com.rewindmod.RewindMod;
import com.rewindmod.network.RewindServerNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.item.ItemStack;
import com.mojang.serialization.DataResult;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

/**
 * Core server-side manager for the Rewind mechanic.
 *
 * Rewind now plays BACKWARDS frame-by-frame:
 *  - When triggered, we collect all snapshots from the last REWIND_SECONDS seconds
 *  - Each server tick during playback, we apply the previous frame (newest → oldest)
 *  - This creates smooth reverse motion for all entities/players/world
 */
public class RewindManager {

    private static RewindManager INSTANCE;

    public static RewindManager getInstance() {
        if (INSTANCE == null) INSTANCE = new RewindManager();
        return INSTANCE;
    }

    private static final int REWIND_SECONDS = 5;
    public static final int COOLDOWN_SECONDS = 15;
    public static final int COOLDOWN_TICKS = COOLDOWN_SECONDS * 20;

    private final SnapshotHistory snapshotHistory = new SnapshotHistory();
    private final Map<UUID, Integer> cooldownMap = new HashMap<>();

    // Playback state
    private boolean rewinding = false;
    private final Deque<WorldSnapshot> playbackQueue = new ArrayDeque<>();
    private int playbackTotal = 0;
    private int playbackRemaining = 0;

    // ── Tick ──────────────────────────────────────────────────────────────────

    public void onServerTick(MinecraftServer server) {
        ServerWorld world = server.getWorld(World.OVERWORLD);
        if (world == null) return;

        if (rewinding) {
            tickPlayback(world, server);
        } else {
            WorldSnapshot snapshot = WorldSnapshot.capture(world);
            snapshotHistory.add(snapshot);
        }

        // Decrement cooldowns
        for (UUID uuid : new ArrayList<>(cooldownMap.keySet())) {
            int ticks = cooldownMap.get(uuid);
            if (ticks <= 1) cooldownMap.remove(uuid);
            else cooldownMap.put(uuid, ticks - 1);
        }
    }

    // ── Playback tick ─────────────────────────────────────────────────────────

    private void tickPlayback(ServerWorld world, MinecraftServer server) {
        if (playbackQueue.isEmpty()) {
            rewinding = false;
            RewindMod.LOGGER.info("[RewindMod] Rewind playback finished.");
            return;
        }

        // Poll from TAIL → newest remaining → going backwards
        WorldSnapshot frame = playbackQueue.pollLast();
        applyFrame(frame, world, server);
        playbackRemaining--;

        // Sync cooldown each frame
        syncCooldownToAll(server);
    }

    // ── Request rewind ────────────────────────────────────────────────────────

    public boolean requestRewind(ServerPlayerEntity requestingPlayer, MinecraftServer server) {
        UUID uuid = requestingPlayer.getUuid();

        if (cooldownMap.containsKey(uuid)) {
            int remaining = (cooldownMap.get(uuid) + 19) / 20;
            requestingPlayer.sendMessage(
                    Text.literal("Rewind masih cooldown! Tunggu " + remaining + " detik lagi.")
                            .formatted(Formatting.RED),
                    true
            );
            return false;
        }

        if (snapshotHistory.isEmpty()) {
            requestingPlayer.sendMessage(
                    Text.literal("Belum ada cukup histori untuk rewind!")
                            .formatted(Formatting.YELLOW),
                    true
            );
            return false;
        }

        List<WorldSnapshot> frames = snapshotHistory.getLastSeconds(REWIND_SECONDS);
        if (frames.isEmpty()) {
            requestingPlayer.sendMessage(
                    Text.literal("Tidak cukup histori!").formatted(Formatting.YELLOW), true
            );
            return false;
        }

        // frames is oldest→newest. We want to play newest→oldest, so queue as-is and pollLast
        playbackQueue.clear();
        playbackQueue.addAll(frames);
        playbackTotal = frames.size();
        playbackRemaining = frames.size();
        rewinding = true;

        // Set cooldown for all players
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            cooldownMap.put(player.getUuid(), COOLDOWN_TICKS);
        }

        // Notify clients to show rewind visual
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player,
                    new RewindServerNetworking.RewindStartPayload(playbackTotal));
        }

        server.getPlayerManager().broadcast(
                Text.literal("⏪ " + requestingPlayer.getName().getString() + " melakukan REWIND! Dunia mundur " + REWIND_SECONDS + " detik!")
                        .formatted(Formatting.AQUA),
                false
        );

        snapshotHistory.clear();
        return true;
    }

    // ── Apply a single frame ──────────────────────────────────────────────────

    private void applyFrame(WorldSnapshot snapshot, ServerWorld world, MinecraftServer server) {
        // 1. Restore players
        Map<UUID, WorldSnapshot.PlayerSnapshot> playerSnaps = snapshot.getPlayerSnapshots();
        for (ServerPlayerEntity player : world.getPlayers()) {
            WorldSnapshot.PlayerSnapshot ps = playerSnaps.get(player.getUuid());
            if (ps == null) continue;
            restorePlayer(player, ps);
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!(player.getEntityWorld() instanceof ServerWorld pw) || pw != world) {
                WorldSnapshot.PlayerSnapshot ps = playerSnaps.get(player.getUuid());
                if (ps != null) restorePlayer(player, ps);
            }
        }

        // 2. Restore non-player entities
        Map<UUID, Entity> currentEntities = new HashMap<>();
        for (Entity entity : world.iterateEntities()) {
            if (!(entity instanceof PlayerEntity)) {
                currentEntities.put(entity.getUuid(), entity);
            }
        }

        Set<UUID> snapshotUUIDs = new HashSet<>();
        for (WorldSnapshot.EntitySnapshot es : snapshot.getEntitySnapshots()) {
            snapshotUUIDs.add(es.uuid);
            Entity existing = currentEntities.get(es.uuid);
            if (existing != null) {
                existing.refreshPositionAndAngles(es.x, es.y, es.z, es.yaw, es.pitch);
                existing.setVelocity(new Vec3d(es.velX, es.velY, es.velZ));
                if (existing instanceof LivingEntity living) {
                    NbtCompound nbt = es.fullNbt.copy();
                    if (nbt.contains("Health")) {
                        living.setHealth(nbt.getFloat("Health", living.getMaxHealth()));
                    }
                }
            } else {
                spawnEntityFromSnapshot(es, world);
            }
        }

        // 3. Remove entities that didn't exist at this snapshot time
        for (Entity entity : world.iterateEntities()) {
            if (entity instanceof PlayerEntity) continue;
            if (!snapshotUUIDs.contains(entity.getUuid())) {
                entity.discard();
            }
        }
    }

    private void restorePlayer(ServerPlayerEntity player, WorldSnapshot.PlayerSnapshot ps) {
        ServerWorld playerWorld = (ServerWorld) player.getEntityWorld();
        player.teleport(playerWorld, ps.x, ps.y, ps.z, Set.of(), ps.yaw, ps.pitch, false);
        player.setHealth(ps.health);
        player.getHungerManager().setFoodLevel(ps.foodLevel);
        player.getHungerManager().setSaturationLevel(ps.saturation);
        player.setExperienceLevel(ps.xpLevel);
        player.setExperiencePoints(0);
        player.addExperience((int)(ps.xpProgress * player.getNextLevelExperience()));

        player.getInventory().clear();
        NbtCompound invData = ps.inventoryNbt.getCompoundOrEmpty("inventory");
        NbtList itemsList = invData.getListOrEmpty("Items");
        for (int i = 0; i < itemsList.size(); i++) {
            if (itemsList.get(i) instanceof NbtCompound slotNbt) {
                int slot = slotNbt.getByte("Slot", (byte)0) & 0xFF;
                DataResult<ItemStack> result = ItemStack.CODEC.parse(NbtOps.INSTANCE, slotNbt);
                result.result().ifPresent(stack -> {
                    if (slot < player.getInventory().size()) {
                        player.getInventory().setStack(slot, stack);
                    }
                });
            }
        }

        player.setVelocity(0, 0, 0);
        player.setFireTicks(ps.fireTicks);
        player.setAir(ps.air);
        player.clearStatusEffects();
    }

    private void spawnEntityFromSnapshot(WorldSnapshot.EntitySnapshot es, ServerWorld world) {
        try {
            NbtCompound nbtWithId = es.fullNbt.copy();
            nbtWithId.putString("id", es.entityType);
            EntityType.loadEntityWithPassengers(nbtWithId, world, SpawnReason.LOAD, loaded -> {
                loaded.refreshPositionAndAngles(es.x, es.y, es.z, es.yaw, es.pitch);
                loaded.setUuid(es.uuid);
                world.spawnEntity(loaded);
                return loaded;
            });
        } catch (Exception e) {
            RewindMod.LOGGER.warn("Failed to re-spawn entity {} during rewind: {}", es.uuid, e.getMessage());
        }
    }

    // ── Cooldown sync ─────────────────────────────────────────────────────────

    public void syncCooldownToAll(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            int ticks = cooldownMap.getOrDefault(player.getUuid(), 0);
            ServerPlayNetworking.send(player,
                    new RewindServerNetworking.CooldownSyncPayload(ticks));
        }
    }

    public int getCooldownTicks(UUID playerUuid) {
        return cooldownMap.getOrDefault(playerUuid, 0);
    }

    public boolean isOnCooldown(UUID playerUuid) {
        return cooldownMap.containsKey(playerUuid);
    }

    public boolean isRewinding() { return rewinding; }
    public int getPlaybackRemaining() { return playbackRemaining; }
    public int getPlaybackTotal() { return playbackTotal; }
}
