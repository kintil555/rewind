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
 * Frame-by-frame playback (newest → oldest):
 *  - World time is set each frame → smooth day/night rewind
 *  - Dead entities are re-spawned cleanly (no miring/corrupt state)
 *  - Entity position + velocity set per-frame for smooth interpolation
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

        // frames is oldest→newest; pollLast gives newest first (backwards playback)
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
                Text.literal("⏪ " + requestingPlayer.getName().getString()
                        + " melakukan REWIND! Dunia mundur " + REWIND_SECONDS + " detik!")
                        .formatted(Formatting.AQUA),
                false
        );

        snapshotHistory.clear();
        return true;
    }

    // ── Apply a single frame ──────────────────────────────────────────────────

    private void applyFrame(WorldSnapshot snapshot, ServerWorld world, MinecraftServer server) {

        // ── 0. Restore world time (day/night cycle) ───────────────────────────
        // In 1.21.1 (Yarn), world.setTime() does not exist.
        // Only timeOfDay can be modified; raw world time is an internal tick counter.
        // setTimeOfDay() controls the day/night cycle which is what we want for rewind.
        world.setTimeOfDay(snapshot.getWorldTime());

        // ── 1. Restore players ────────────────────────────────────────────────
        Map<UUID, WorldSnapshot.PlayerSnapshot> playerSnaps = snapshot.getPlayerSnapshots();
        for (ServerPlayerEntity player : new ArrayList<>(world.getPlayers())) {
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

        // ── 2. Collect current non-player entities safely ─────────────────────
        List<Entity> entityList = new ArrayList<>();
        for (Entity entity : world.iterateEntities()) {
            if (entity != null && !(entity instanceof PlayerEntity) && !entity.isRemoved()) {
                entityList.add(entity);
            }
        }

        Map<UUID, Entity> currentEntities = new HashMap<>();
        for (Entity entity : entityList) {
            currentEntities.put(entity.getUuid(), entity);
        }

        // ── 3. Restore / respawn entities from snapshot ───────────────────────
        Set<UUID> snapshotUUIDs = new HashSet<>();
        for (WorldSnapshot.EntitySnapshot es : snapshot.getEntitySnapshots()) {
            snapshotUUIDs.add(es.uuid);
            Entity existing = currentEntities.get(es.uuid);

            boolean entityShouldExist = es.wasAlive;
            if (existing != null && !existing.isRemoved() && entityShouldExist) {
                // Entity alive in snapshot and alive now — smooth position restore
                existing.refreshPositionAndAngles(es.x, es.y, es.z, es.yaw, es.pitch);
                existing.setVelocity(new Vec3d(es.velX, es.velY, es.velZ));
                existing.velocityDirty = true;

                if (existing instanceof LivingEntity living) {
                    float targetHealth = es.fullNbt.getFloat("Health", living.getMaxHealth());
                    living.setHealth(targetHealth);
                    // Reset death animation state so mob stands upright
                    living.deathTime = es.fullNbt.getInt("DeathTime", 0);
                    living.hurtTime = es.fullNbt.getInt("HurtTime", 0);
                }
            } else if (entityShouldExist) {
                // Entity was alive at this snapshot time but is now dead/missing — respawn it
                if (existing != null) existing.discard();
                spawnEntityFromSnapshot(es, world);
            } else {
                // Entity was already dead at this snapshot time — discard if present
                if (existing != null && !existing.isRemoved()) existing.discard();
            }
        }

        // ── 4. Remove entities that spawned AFTER this snapshot time ──────────
        for (Entity entity : entityList) {
            if (entity == null || entity.isRemoved()) continue;
            if (entity instanceof PlayerEntity) continue;
            if (!snapshotUUIDs.contains(entity.getUuid())) {
                entity.discard();
            }
        }
    }

    private void restorePlayer(ServerPlayerEntity player, WorldSnapshot.PlayerSnapshot ps) {
        ServerWorld playerWorld = (ServerWorld) player.getEntityWorld();

        // Teleport smoothly — use setPosition during rewind instead of full teleport
        // to reduce client-side stutter. Full teleport every frame causes the
        // "confirmation packet" overhead. We only teleport position, not full relocation.
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

        // Zero out velocity so player doesn't drift between frames
        player.setVelocity(0, 0, 0);
        player.setFireTicks(ps.fireTicks);
        player.setAir(ps.air);
        player.clearStatusEffects();
    }

    private void spawnEntityFromSnapshot(WorldSnapshot.EntitySnapshot es, ServerWorld world) {
        try {
            NbtCompound nbtWithId = es.fullNbt.copy();
            nbtWithId.putString("id", es.entityType);
            // Only spawn if entity has valid health (> 0) or is non-living
            float health = nbtWithId.getFloat("Health", 1.0f);
            if (health <= 0 && nbtWithId.contains("Health")) {
                // Don't respawn dead entities
                return;
            }
            EntityType.loadEntityWithPassengers(nbtWithId, world, SpawnReason.LOAD, loaded -> {
                loaded.refreshPositionAndAngles(es.x, es.y, es.z, es.yaw, es.pitch);
                loaded.setVelocity(new Vec3d(es.velX, es.velY, es.velZ));
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
