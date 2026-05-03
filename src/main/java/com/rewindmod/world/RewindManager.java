package com.rewindmod.world;

import com.rewindmod.RewindMod;
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
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;/**
 * Core server-side manager for the Rewind mechanic.
 *
 * Responsibilities:
 *  - Tick-by-tick snapshot collection
 *  - Applying a snapshot (restoring the world 5 seconds back)
 *  - Tracking per-player cooldowns
 */
public class RewindManager {

    private static RewindManager INSTANCE;

    public static RewindManager getInstance() {
        if (INSTANCE == null) INSTANCE = new RewindManager();
        return INSTANCE;
    }

    private static final int REWIND_SECONDS = 5;
    public static final int COOLDOWN_SECONDS = 10;
    public static final int COOLDOWN_TICKS = COOLDOWN_SECONDS * 20;

    private final SnapshotHistory snapshotHistory = new SnapshotHistory();
    // Maps player UUID -> remaining cooldown ticks
    private final Map<UUID, Integer> cooldownMap = new HashMap<>();
    // Whether a rewind is currently in progress (prevent cascading)
    private boolean rewinding = false;

    // ──────────────────────────────────────────────────────
    // Called every server tick from the ServerWorldMixin
    // ──────────────────────────────────────────────────────

    public void onServerTick(MinecraftServer server) {
        // Capture snapshot from overworld
        ServerWorld world = server.getWorld(World.OVERWORLD);
        if (world == null) return;

        if (!rewinding) {
            WorldSnapshot snapshot = WorldSnapshot.capture(world);
            snapshotHistory.add(snapshot);
        }

        // Decrement all cooldowns
        for (UUID uuid : new ArrayList<>(cooldownMap.keySet())) {
            int ticks = cooldownMap.get(uuid);
            if (ticks <= 1) {
                cooldownMap.remove(uuid);
            } else {
                cooldownMap.put(uuid, ticks - 1);
            }
        }
    }

    // ──────────────────────────────────────────────────────
    // Called when a player requests a rewind
    // ──────────────────────────────────────────────────────

    public boolean requestRewind(ServerPlayerEntity requestingPlayer, MinecraftServer server) {
        UUID uuid = requestingPlayer.getUuid();

        if (cooldownMap.containsKey(uuid)) {
            int remaining = (cooldownMap.get(uuid) + 19) / 20; // convert to seconds, rounded up
            requestingPlayer.sendMessage(
                    Text.literal("⏪ Rewind masih cooldown! Tunggu " + remaining + " detik lagi.")
                            .formatted(Formatting.RED),
                    true
            );
            return false;
        }

        WorldSnapshot target = snapshotHistory.getSnapshotSecondsAgo(REWIND_SECONDS);
        if (target == null) {
            requestingPlayer.sendMessage(
                    Text.literal("⏪ Belum ada cukup histori untuk rewind!")
                            .formatted(Formatting.YELLOW),
                    true
            );
            return false;
        }

        // Apply rewind
        rewinding = true;
        try {
            applySnapshot(target, server);
        } finally {
            rewinding = false;
        }

        // Set cooldown for ALL players currently online
        ServerWorld world = server.getWorld(World.OVERWORLD);
        if (world != null) {
            for (ServerPlayerEntity player : world.getPlayers()) {
                cooldownMap.put(player.getUuid(), COOLDOWN_TICKS);
            }
        }

        // Also set cooldown for players in other dimensions
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            cooldownMap.put(player.getUuid(), COOLDOWN_TICKS);
        }

        // Broadcast message
        server.getPlayerManager().broadcast(
                Text.literal("⏪ " + requestingPlayer.getName().getString() + " melakukan REWIND! Dunia mundur 5 detik!")
                        .formatted(Formatting.AQUA),
                false
        );

        // Clear history after rewind to avoid re-rewinding stale data
        snapshotHistory.clear();

        return true;
    }

    // ──────────────────────────────────────────────────────
    // Apply a snapshot to the live world
    // ──────────────────────────────────────────────────────

    private void applySnapshot(WorldSnapshot snapshot, MinecraftServer server) {
        ServerWorld world = server.getWorld(World.OVERWORLD);
        if (world == null) return;

        // 1. Restore players
        Map<UUID, WorldSnapshot.PlayerSnapshot> playerSnaps = snapshot.getPlayerSnapshots();
        for (ServerPlayerEntity player : world.getPlayers()) {
            WorldSnapshot.PlayerSnapshot ps = playerSnaps.get(player.getUuid());
            if (ps == null) continue;
            restorePlayer(player, ps);
        }
        // Also restore players in other dimensions
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!(player.getEntityWorld() instanceof ServerWorld playerWorld) || playerWorld != world) {
                WorldSnapshot.PlayerSnapshot ps = playerSnaps.get(player.getUuid());
                if (ps != null) {
                    restorePlayer(player, ps);
                }
            }
        }

        // 2. Restore non-player entities
        // Build a map of current entities by UUID
        Map<UUID, Entity> currentEntities = new HashMap<>();
        for (Entity entity : world.iterateEntities()) {
            if (!(entity instanceof PlayerEntity)) {
                currentEntities.put(entity.getUuid(), entity);
            }
        }

        Set<UUID> snapshotEntityUUIDs = new HashSet<>();
        for (WorldSnapshot.EntitySnapshot es : snapshot.getEntitySnapshots()) {
            snapshotEntityUUIDs.add(es.uuid);
            Entity existing = currentEntities.get(es.uuid);
            if (existing != null) {
                // Restore position & state
                existing.refreshPositionAndAngles(es.x, es.y, es.z, es.yaw, es.pitch);
                existing.setVelocity(new Vec3d(es.velX, es.velY, es.velZ));
                // Restore full NBT (health, etc.) for living entities
                if (existing instanceof LivingEntity living) {
                    NbtCompound nbt = es.fullNbt.copy();
                    // Only restore health from NBT - 1.21.5+ getFloat returns Optional, use fallback overload
                    if (nbt.contains("Health")) {
                        living.setHealth(nbt.getFloat("Health", living.getMaxHealth()));
                    }
                }
            } else {
                // Entity existed 5s ago but was spawned after – try to recreate it
                spawnEntityFromSnapshot(es, world);
            }
        }

        // 3. Remove entities that spawned within the last 5 seconds (not in snapshot)
        for (Entity entity : world.iterateEntities()) {
            if (entity instanceof PlayerEntity) continue;
            if (!snapshotEntityUUIDs.contains(entity.getUuid())) {
                entity.discard();
            }
        }
    }

    private void restorePlayer(ServerPlayerEntity player, WorldSnapshot.PlayerSnapshot ps) {
        // Teleport - use getEntityWorld() cast to ServerWorld (renamed in 1.21.9)
        ServerWorld playerWorld = (ServerWorld) player.getEntityWorld();
        player.teleport(
                playerWorld,
                ps.x, ps.y, ps.z,
                Set.of(),
                ps.yaw, ps.pitch,
                false
        );
        // Health & food
        player.setHealth(ps.health);
        player.getHungerManager().setFoodLevel(ps.foodLevel);
        player.getHungerManager().setSaturationLevel(ps.saturation);
        // XP
        player.setExperienceLevel(ps.xpLevel);
        player.setExperiencePoints(0);
        player.addExperience((int)(ps.xpProgress * player.getNextLevelExperience()));
        // Inventory: restore slot-by-slot from the NbtList written by Inventories.writeData().
        // Since NbtReadView has no public factory, we decode ItemStacks directly from the
        // "Items" NbtList that Inventories.writeData() writes into the NbtCompound.
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
        // Velocity
        player.setVelocity(ps.velX, ps.velY, ps.velZ);
        // Fire ticks
        player.setFireTicks(ps.fireTicks);
        // Air
        player.setAir(ps.air);

        // Clear status effects then re-apply from snapshot
        player.clearStatusEffects();
    }

    private void spawnEntityFromSnapshot(WorldSnapshot.EntitySnapshot es, ServerWorld world) {
        try {
            // Build a proper entity NBT compound with the "id" key that loadEntityWithPassengers needs
            NbtCompound nbtWithId = es.fullNbt.copy();
            nbtWithId.putString("id", es.entityType);
            // loadEntityWithPassengers handles deserialization from NbtCompound (still valid in 1.21.11)
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

    // ──────────────────────────────────────────────────────
    // Cooldown query (used by client-side HUD too via sync)
    // ──────────────────────────────────────────────────────

    public int getCooldownTicks(UUID playerUuid) {
        return cooldownMap.getOrDefault(playerUuid, 0);
    }

    public boolean isOnCooldown(UUID playerUuid) {
        return cooldownMap.containsKey(playerUuid);
    }
}
