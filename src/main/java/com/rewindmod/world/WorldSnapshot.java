package com.rewindmod.world;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.*;

/**
 * A snapshot of the world state at a specific point in time.
 * Captures player positions, health, inventory, and nearby entity states.
 */
public class WorldSnapshot {

    public static final int SNAPSHOT_RADIUS = 128; // radius in blocks to capture entities

    private final long timestamp;
    private final long worldTime;
    private final Map<UUID, PlayerSnapshot> playerSnapshots;
    private final List<EntitySnapshot> entitySnapshots;

    public WorldSnapshot(long timestamp, long worldTime,
                         Map<UUID, PlayerSnapshot> playerSnapshots,
                         List<EntitySnapshot> entitySnapshots) {
        this.timestamp = timestamp;
        this.worldTime = worldTime;
        this.playerSnapshots = playerSnapshots;
        this.entitySnapshots = entitySnapshots;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public long getWorldTime() {
        return worldTime;
    }

    public Map<UUID, PlayerSnapshot> getPlayerSnapshots() {
        return playerSnapshots;
    }

    public List<EntitySnapshot> getEntitySnapshots() {
        return entitySnapshots;
    }

    /**
     * Captures the current state of a ServerWorld.
     */
    public static WorldSnapshot capture(ServerWorld world) {
        long now = System.currentTimeMillis();
        long worldTime = world.getTime();

        Map<UUID, PlayerSnapshot> players = new HashMap<>();
        List<EntitySnapshot> entities = new ArrayList<>();

        for (ServerPlayerEntity player : world.getPlayers()) {
            players.put(player.getUuid(), PlayerSnapshot.capture(player));
        }

        // Capture all non-player entities within radius of any player
        for (Entity entity : world.iterateEntities()) {
            if (entity instanceof PlayerEntity) continue;
            // only capture entities near at least one player
            boolean nearPlayer = false;
            for (ServerPlayerEntity player : world.getPlayers()) {
                if (entity.squaredDistanceTo(player) < SNAPSHOT_RADIUS * SNAPSHOT_RADIUS) {
                    nearPlayer = true;
                    break;
                }
            }
            if (nearPlayer) {
                entities.add(EntitySnapshot.capture(entity));
            }
        }

        return new WorldSnapshot(now, worldTime, players, entities);
    }

    // ──────────────────────────────────────────────────────
    // Inner snapshot types
    // ──────────────────────────────────────────────────────

    public static class PlayerSnapshot {
        public final UUID uuid;
        public final String name;
        public final double x, y, z;
        public final float yaw, pitch;
        public final float health;
        public final int foodLevel;
        public final float saturation;
        public final int xpLevel;
        public final float xpProgress;
        public final int score;
        public final NbtCompound inventoryNbt;
        public final NbtCompound effectsNbt;
        public final boolean onGround;
        public final double velX, velY, velZ;
        public final int fireTicks;
        public final int air;

        private PlayerSnapshot(UUID uuid, String name,
                               double x, double y, double z,
                               float yaw, float pitch,
                               float health, int foodLevel, float saturation,
                               int xpLevel, float xpProgress, int score,
                               NbtCompound inventoryNbt, NbtCompound effectsNbt,
                               boolean onGround, double velX, double velY, double velZ,
                               int fireTicks, int air) {
            this.uuid = uuid;
            this.name = name;
            this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch;
            this.health = health;
            this.foodLevel = foodLevel;
            this.saturation = saturation;
            this.xpLevel = xpLevel;
            this.xpProgress = xpProgress;
            this.score = score;
            this.inventoryNbt = inventoryNbt;
            this.effectsNbt = effectsNbt;
            this.onGround = onGround;
            this.velX = velX; this.velY = velY; this.velZ = velZ;
            this.fireTicks = fireTicks;
            this.air = air;
        }

        public static PlayerSnapshot capture(ServerPlayerEntity player) {
            // In 1.21.x yarn, writeNbt(NbtList) returns NbtList directly
            NbtList invList = player.getInventory().writeNbt(new NbtList());
            NbtCompound invNbt = new NbtCompound();
            invNbt.put("inventory", invList);

            NbtCompound effectsNbt = new NbtCompound();
            NbtList effectList = new NbtList();
            player.getActiveStatusEffects().forEach((effect, instance) -> {
                NbtCompound effectTag = instance.toNbt();
                effectList.add(effectTag);
            });
            effectsNbt.put("effects", effectList);

            return new PlayerSnapshot(
                    player.getUuid(),
                    player.getName().getString(),
                    player.getX(), player.getY(), player.getZ(),
                    player.getYaw(), player.getPitch(),
                    player.getHealth(),
                    player.getHungerManager().getFoodLevel(),
                    player.getHungerManager().getSaturationLevel(),
                    player.experienceLevel,
                    player.experienceProgress,
                    player.getScore(),
                    invNbt, effectsNbt,
                    player.isOnGround(),
                    player.getVelocity().x, player.getVelocity().y, player.getVelocity().z,
                    player.getFireTicks(),
                    player.getAir()
            );
        }
    }

    public static class EntitySnapshot {
        public final UUID uuid;
        public final String entityType; // entity type ID string
        public final double x, y, z;
        public final float yaw, pitch;
        public final double velX, velY, velZ;
        public final NbtCompound fullNbt;

        private EntitySnapshot(UUID uuid, String entityType,
                               double x, double y, double z,
                               float yaw, float pitch,
                               double velX, double velY, double velZ,
                               NbtCompound fullNbt) {
            this.uuid = uuid;
            this.entityType = entityType;
            this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch;
            this.velX = velX; this.velY = velY; this.velZ = velZ;
            this.fullNbt = fullNbt;
        }

        public static EntitySnapshot capture(Entity entity) {
            NbtCompound nbt = new NbtCompound();
            entity.saveSelfNbt(nbt);
            // Use registry to get proper entity type ID
            Identifier typeId = Registries.ENTITY_TYPE.getId(entity.getType());
            return new EntitySnapshot(
                    entity.getUuid(),
                    typeId.toString(),
                    entity.getX(), entity.getY(), entity.getZ(),
                    entity.getYaw(), entity.getPitch(),
                    entity.getVelocity().x, entity.getVelocity().y, entity.getVelocity().z,
                    nbt
            );
        }
    }
}
