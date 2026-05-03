package com.rewindmod.world;

import com.rewindmod.RewindMod;
import com.rewindmod.network.RewindServerNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

/**
 * Core server-side manager for the Rewind mechanic — v2.
 *
 * Improvements over v1:
 *  - Block changes are rewound frame-by-frame (delta restore, reversed order)
 *  - Player inventory is fully restored from NBT (fixes dropped-item ghost bug)
 *  - Player pose/animation state (swimming, sprinting, sneaking, crawling) is restored
 *  - Entity pose restored for smooth backward animation appearance
 *  - BlockChangeTracker.replaying flag prevents re-recording our own restores
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
            BlockChangeTracker.getInstance().setReplaying(false);
            RewindMod.LOGGER.info("[RewindMod] Rewind playback finished.");
            return;
        }

        WorldSnapshot frame = playbackQueue.pollLast();
        BlockChangeTracker.getInstance().setReplaying(true);
        applyFrame(frame, world, server);
        playbackRemaining--;

        syncCooldownToAll(server);
    }

    // ── Request rewind ────────────────────────────────────────────────────────

    public boolean requestRewind(ServerPlayerEntity requestingPlayer, MinecraftServer server) {
        UUID uuid = requestingPlayer.getUuid();

        if (cooldownMap.containsKey(uuid)) {
            int remaining = (cooldownMap.get(uuid) + 19) / 20;
            requestingPlayer.sendMessage(
                    Text.literal("Rewind masih cooldown! Tunggu " + remaining + " detik lagi.")
                            .formatted(Formatting.RED), true);
            return false;
        }

        if (snapshotHistory.isEmpty()) {
            requestingPlayer.sendMessage(
                    Text.literal("Belum ada cukup histori untuk rewind!")
                            .formatted(Formatting.YELLOW), true);
            return false;
        }

        List<WorldSnapshot> frames = snapshotHistory.getLastSeconds(REWIND_SECONDS);
        if (frames.isEmpty()) {
            requestingPlayer.sendMessage(
                    Text.literal("Tidak cukup histori!").formatted(Formatting.YELLOW), true);
            return false;
        }

        playbackQueue.clear();
        playbackQueue.addAll(frames);
        playbackTotal = frames.size();
        playbackRemaining = frames.size();
        rewinding = true;
        BlockChangeTracker.getInstance().setReplaying(true);

        ServerWorld world = server.getWorld(World.OVERWORLD);

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
                        .formatted(Formatting.AQUA), false);

        snapshotHistory.clear();
        return true;
    }

    // ── Apply a single frame ──────────────────────────────────────────────────

    private void applyFrame(WorldSnapshot snapshot, ServerWorld world, MinecraftServer server) {

        // ── 0. World time (day/night cycle) ──────────────────────────────────
        world.setTimeOfDay(snapshot.getWorldTime());

        // ── 1. Restore block changes — IN REVERSE (undo this tick's changes) ─
        // blockChanges list is in chronological order → iterate backwards
        List<BlockChangeRecord> blockChanges = snapshot.getBlockChanges();
        for (int i = blockChanges.size() - 1; i >= 0; i--) {
            BlockChangeRecord rec = blockChanges.get(i);
            // Restore to the BEFORE state (undoing what happened that tick)
            world.setBlockState(rec.pos(), rec.before(),
                    Block_NOTIFY_ALL);
        }

        // ── 2. Restore players ────────────────────────────────────────────────
        Map<UUID, WorldSnapshot.PlayerSnapshot> playerSnaps = snapshot.getPlayerSnapshots();
        for (ServerPlayerEntity player : new ArrayList<>(world.getPlayers())) {
            WorldSnapshot.PlayerSnapshot ps = playerSnaps.get(player.getUuid());
            if (ps != null) restorePlayer(player, ps);
        }
        // Also catch players in other dimensions
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!(player.getEntityWorld() instanceof ServerWorld pw) || pw != world) {
                WorldSnapshot.PlayerSnapshot ps = playerSnaps.get(player.getUuid());
                if (ps != null) restorePlayer(player, ps);
            }
        }

        // ── 3. Collect current non-player entities ────────────────────────────
        List<Entity> entityList = new ArrayList<>();
        for (Entity entity : world.iterateEntities()) {
            if (entity != null && !(entity instanceof PlayerEntity) && !entity.isRemoved()) {
                entityList.add(entity);
            }
        }
        Map<UUID, Entity> currentEntities = new HashMap<>();
        for (Entity entity : entityList) currentEntities.put(entity.getUuid(), entity);

        // ── 4. Restore / respawn entities from snapshot ───────────────────────
        Set<UUID> snapshotUUIDs = new HashSet<>();
        for (WorldSnapshot.EntitySnapshot es : snapshot.getEntitySnapshots()) {
            snapshotUUIDs.add(es.uuid);
            Entity existing = currentEntities.get(es.uuid);

            if (existing != null && !existing.isRemoved() && es.wasAlive) {
                // Entity alive then and now — smooth restore
                existing.refreshPositionAndAngles(es.x, es.y, es.z, es.yaw, es.pitch);
                existing.setBodyYaw(es.bodyYaw);
                existing.setHeadYaw(es.headYaw);
                existing.setVelocity(new Vec3d(es.velX, es.velY, es.velZ));
                existing.velocityDirty = true;
                existing.setSwimming(es.isSwimming);
                existing.setSprinting(es.isSprinting);
                existing.setPose(es.pose);

                if (existing instanceof LivingEntity living) {
                    float targetHealth = es.fullNbt.getFloat("Health", living.getMaxHealth());
                    living.setHealth(targetHealth);
                    living.deathTime = es.fullNbt.getInt("DeathTime", 0);
                    living.hurtTime = es.fullNbt.getInt("HurtTime", 0);
                }
            } else if (es.wasAlive) {
                // Was alive at this time but is now gone — respawn
                if (existing != null) existing.discard();
                spawnEntityFromSnapshot(es, world);
            } else {
                // Was dead at this time — discard if present
                if (existing != null && !existing.isRemoved()) existing.discard();
            }
        }

        // ── 5. Remove entities that didn't exist yet at this snapshot time ────
        for (Entity entity : entityList) {
            if (entity == null || entity.isRemoved() || entity instanceof PlayerEntity) continue;
            if (!snapshotUUIDs.contains(entity.getUuid())) entity.discard();
        }
    }

    /** Block.NOTIFY_NEIGHBORS | NOTIFY_LISTENERS (flags = 3) */
    private static final int Block_NOTIFY_ALL = 3;

    private void restorePlayer(ServerPlayerEntity player, WorldSnapshot.PlayerSnapshot ps) {
        ServerWorld playerWorld = (ServerWorld) player.getEntityWorld();

        // Smooth teleport
        player.teleport(playerWorld, ps.x, ps.y, ps.z, Set.of(), ps.yaw, ps.pitch, false);
        player.setBodyYaw(ps.bodyYaw);
        player.setHeadYaw(ps.headYaw);

        // Health & hunger
        player.setHealth(ps.health);
        player.getHungerManager().setFoodLevel(ps.foodLevel);
        player.getHungerManager().setSaturationLevel(ps.saturation);

        // XP
        player.setExperienceLevel(ps.xpLevel);
        player.setExperiencePoints(0);
        player.addExperience((int)(ps.xpProgress * player.getNextLevelExperience()));

        // ── Full inventory restore from NBT (fixes item-drop ghost bug) ───────
        // Restore inventory via PlayerInventory.readNbt — avoids readCustomDataFromNbt API changes
        try {
            player.getInventory().clear();
            // getList returns empty NbtList if key missing — safe in all versions
            net.minecraft.nbt.NbtList inventoryNbt = (net.minecraft.nbt.NbtList) ps.fullPlayerNbt.get("Inventory");
            if (inventoryNbt != null && !inventoryNbt.isEmpty()) {
                player.getInventory().readNbt(inventoryNbt);
            }
        } catch (Exception e) {
            RewindMod.LOGGER.warn("[RewindMod] Failed to restore inventory for {}: {}",
                    player.getName().getString(), e.getMessage());
            player.getInventory().clear();
        }

        // ── Pose / animation state ────────────────────────────────────────────
        player.setSwimming(ps.isSwimming);
        player.setSprinting(ps.isSprinting);
        player.setSneaking(ps.isSneaking);
        player.setPose(ps.pose);

        // Zero velocity between frames (prevents drift)
        // But invert the stored velocity to simulate backward movement feel
        player.setVelocity(-ps.velX * 0.5, -ps.velY * 0.5, -ps.velZ * 0.5);

        player.setFireTicks(ps.fireTicks);
        player.setAir(ps.air);
        player.clearStatusEffects();
    }

    private void spawnEntityFromSnapshot(WorldSnapshot.EntitySnapshot es, ServerWorld world) {
        try {
            NbtCompound nbtWithId = es.fullNbt.copy();
            nbtWithId.putString("id", es.entityType);
            float health = nbtWithId.getFloat("Health", 1.0f);
            if (health <= 0 && nbtWithId.contains("Health")) return;

            EntityType.loadEntityWithPassengers(nbtWithId, world, SpawnReason.LOAD, loaded -> {
                loaded.refreshPositionAndAngles(es.x, es.y, es.z, es.yaw, es.pitch);
                loaded.setBodyYaw(es.bodyYaw);
                loaded.setHeadYaw(es.headYaw);
                loaded.setVelocity(new Vec3d(es.velX, es.velY, es.velZ));
                loaded.setUuid(es.uuid);
                loaded.setSwimming(es.isSwimming);
                loaded.setSprinting(es.isSprinting);
                loaded.setPose(es.pose);
                world.spawnEntity(loaded);
                return loaded;
            });
        } catch (Exception e) {
            RewindMod.LOGGER.warn("[RewindMod] Failed to re-spawn entity {} during rewind: {}",
                    es.uuid, e.getMessage());
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

    public int getCooldownTicks(UUID playerUuid)   { return cooldownMap.getOrDefault(playerUuid, 0); }
    public boolean isOnCooldown(UUID playerUuid)   { return cooldownMap.containsKey(playerUuid); }
    public boolean isRewinding()                   { return rewinding; }
    public int getPlaybackRemaining()              { return playbackRemaining; }
    public int getPlaybackTotal()                  { return playbackTotal; }
}
