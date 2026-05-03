package com.rewindmod.world;

import com.mojang.serialization.DataResult;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Snapshot of the world state at a given point in time.
 *
 * v2 — Includes:
 *  - Block change DELTAS (delta-based, low memory, 5-chunk radius)
 *  - Full player pose/animation state (swimming, sprinting, sneaking, crawling)
 *  - Full NBT inventory capture (fixes item-drop-then-rewind returning ghost item)
 *  - Entity full NBT via writeNbt for accurate respawn
 */
public class WorldSnapshot {

    /** 5 chunks = 80 blocks radius for block tracking */
    public static final int BLOCK_TRACK_RADIUS = 80;

    private final long timestamp;
    private final long worldTime;
    private final Map<UUID, PlayerSnapshot> playerSnapshots;
    private final List<EntitySnapshot> entitySnapshots;
    private final List<BlockChangeRecord> blockChanges;

    public WorldSnapshot(long timestamp, long worldTime,
                         Map<UUID, PlayerSnapshot> playerSnapshots,
                         List<EntitySnapshot> entitySnapshots,
                         List<BlockChangeRecord> blockChanges) {
        this.timestamp = timestamp;
        this.worldTime = worldTime;
        this.playerSnapshots = playerSnapshots;
        this.entitySnapshots = entitySnapshots;
        this.blockChanges = blockChanges;
    }

    public long getTimestamp()                            { return timestamp; }
    public long getWorldTime()                            { return worldTime; }
    public Map<UUID, PlayerSnapshot> getPlayerSnapshots() { return playerSnapshots; }
    public List<EntitySnapshot> getEntitySnapshots()      { return entitySnapshots; }
    /** Block changes this tick in chronological order. Rewind reverses them. */
    public List<BlockChangeRecord> getBlockChanges()      { return blockChanges; }

    // ── Capture ────────────────────────────────────────────────────────────────

    public static WorldSnapshot capture(ServerWorld world) {
        long now = System.currentTimeMillis();
        long worldTime = world.getTimeOfDay();

        // Drain block changes accumulated by BlockChangeMixin this tick
        List<BlockChangeRecord> blockChanges = BlockChangeTracker.getInstance().drainAndGet();

        Map<UUID, PlayerSnapshot> players = new HashMap<>();
        List<EntitySnapshot> entities = new ArrayList<>();

        Set<BlockPos> playerPositions = new HashSet<>();
        for (ServerPlayerEntity player : world.getPlayers()) {
            players.put(player.getUuid(), PlayerSnapshot.capture(player));
            playerPositions.add(player.getBlockPos());
        }

        for (Entity entity : world.iterateEntities()) {
            if (entity == null) continue;
            if (entity instanceof PlayerEntity) continue;
            boolean near = false;
            for (BlockPos pp : playerPositions) {
                if (entity.getBlockPos().isWithinDistance(pp, BLOCK_TRACK_RADIUS)) {
                    near = true;
                    break;
                }
            }
            if (near) entities.add(EntitySnapshot.capture(entity));
        }

        return new WorldSnapshot(now, worldTime, players, entities, blockChanges);
    }

    // ── Player snapshot ───────────────────────────────────────────────────────

    public static class PlayerSnapshot {
        public final UUID uuid;
        public final String name;
        public final double x, y, z;
        public final float yaw, pitch, bodyYaw, headYaw;
        public final float health;
        public final int foodLevel;
        public final float saturation;
        public final int xpLevel;
        public final float xpProgress;
        public final int score;
        // Full NBT — includes inventory, effects, etc. (replaces old per-field approach)
        public final NbtCompound fullPlayerNbt;
        public final boolean onGround;
        public final double velX, velY, velZ;
        public final int fireTicks;
        public final int air;
        // Pose/animation states
        public final boolean isSwimming;
        public final boolean isSprinting;
        public final boolean isSneaking;
        public final boolean isCrawling;
        public final EntityPose pose;

        private PlayerSnapshot(UUID uuid, String name,
                               double x, double y, double z,
                               float yaw, float pitch, float bodyYaw, float headYaw,
                               float health, int foodLevel, float saturation,
                               int xpLevel, float xpProgress, int score,
                               NbtCompound fullPlayerNbt,
                               boolean onGround,
                               double velX, double velY, double velZ,
                               int fireTicks, int air,
                               boolean isSwimming, boolean isSprinting,
                               boolean isSneaking, boolean isCrawling, EntityPose pose) {
            this.uuid = uuid; this.name = name;
            this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch;
            this.bodyYaw = bodyYaw; this.headYaw = headYaw;
            this.health = health; this.foodLevel = foodLevel; this.saturation = saturation;
            this.xpLevel = xpLevel; this.xpProgress = xpProgress; this.score = score;
            this.fullPlayerNbt = fullPlayerNbt;
            this.onGround = onGround;
            this.velX = velX; this.velY = velY; this.velZ = velZ;
            this.fireTicks = fireTicks; this.air = air;
            this.isSwimming = isSwimming; this.isSprinting = isSprinting;
            this.isSneaking = isSneaking; this.isCrawling = isCrawling; this.pose = pose;
        }

        public static PlayerSnapshot capture(ServerPlayerEntity player) {
            // Full player NBT — contains Inventory list, ActiveEffects, etc.
            NbtCompound fullNbt = new NbtCompound();
            player.writeCustomDataToNbt(fullNbt);

            return new PlayerSnapshot(
                    player.getUuid(), player.getName().getString(),
                    player.getX(), player.getY(), player.getZ(),
                    player.getYaw(), player.getPitch(),
                    player.getBodyYaw(), player.getHeadYaw(),
                    player.getHealth(),
                    player.getHungerManager().getFoodLevel(),
                    player.getHungerManager().getSaturationLevel(),
                    player.experienceLevel, player.experienceProgress, player.getScore(),
                    fullNbt,
                    player.isOnGround(),
                    player.getVelocity().x, player.getVelocity().y, player.getVelocity().z,
                    player.getFireTicks(), player.getAir(),
                    player.isSwimming(), player.isSprinting(), player.isSneaking(),
                    player.isCrawling(), player.getPose()
            );
        }
    }

    // ── Entity snapshot ───────────────────────────────────────────────────────

    public static class EntitySnapshot {
        public final UUID uuid;
        public final String entityType;
        public final double x, y, z;
        public final float yaw, pitch, bodyYaw, headYaw;
        public final double velX, velY, velZ;
        public final NbtCompound fullNbt;
        public final boolean wasAlive;
        public final boolean isSwimming;
        public final boolean isSprinting;
        public final EntityPose pose;

        private EntitySnapshot(UUID uuid, String entityType,
                               double x, double y, double z,
                               float yaw, float pitch, float bodyYaw, float headYaw,
                               double velX, double velY, double velZ,
                               NbtCompound fullNbt, boolean wasAlive,
                               boolean isSwimming, boolean isSprinting, EntityPose pose) {
            this.uuid = uuid; this.entityType = entityType;
            this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch;
            this.bodyYaw = bodyYaw; this.headYaw = headYaw;
            this.velX = velX; this.velY = velY; this.velZ = velZ;
            this.fullNbt = fullNbt;
            this.wasAlive = wasAlive;
            this.isSwimming = isSwimming; this.isSprinting = isSprinting; this.pose = pose;
        }

        public static EntitySnapshot capture(Entity entity) {
            NbtCompound entityNbt = new NbtCompound();
            boolean alive = true;
            try {
                entity.writeNbt(entityNbt);
            } catch (Exception ignored) {}

            if (entity instanceof LivingEntity living) {
                entityNbt.putFloat("Health", living.getHealth());
                entityNbt.putInt("DeathTime", living.deathTime);
                entityNbt.putInt("HurtTime", living.hurtTime);
                alive = living.getHealth() > 0 && !living.isDead();
            }

            Identifier typeId = Registries.ENTITY_TYPE.getId(entity.getType());
            return new EntitySnapshot(
                    entity.getUuid(), typeId.toString(),
                    entity.getX(), entity.getY(), entity.getZ(),
                    entity.getYaw(), entity.getPitch(),
                    entity.getBodyYaw(), entity.getHeadYaw(),
                    entity.getVelocity().x, entity.getVelocity().y, entity.getVelocity().z,
                    entityNbt, alive,
                    entity.isSwimming(), entity.isSprinting(), entity.getPose()
            );
        }
    }
}
