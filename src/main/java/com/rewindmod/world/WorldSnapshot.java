package com.rewindmod.world;

import com.mojang.serialization.DataResult;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.inventory.StackWithSlot;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.*;

/**
 * Snapshot of the world state at a given point in time.
 */
public class WorldSnapshot {

    public static final int SNAPSHOT_RADIUS = 128;

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

    public long getTimestamp() { return timestamp; }
    public long getWorldTime() { return worldTime; }
    public Map<UUID, PlayerSnapshot> getPlayerSnapshots() { return playerSnapshots; }
    public List<EntitySnapshot> getEntitySnapshots() { return entitySnapshots; }

    public static WorldSnapshot capture(ServerWorld world) {
        long now = System.currentTimeMillis();
        long worldTime = world.getTime();
        Map<UUID, PlayerSnapshot> players = new HashMap<>();
        List<EntitySnapshot> entities = new ArrayList<>();

        for (ServerPlayerEntity player : world.getPlayers()) {
            players.put(player.getUuid(), PlayerSnapshot.capture(player));
        }
        for (Entity entity : world.iterateEntities()) {
            if (entity instanceof PlayerEntity) continue;
            boolean nearPlayer = false;
            for (ServerPlayerEntity player : world.getPlayers()) {
                if (entity.squaredDistanceTo(player) < SNAPSHOT_RADIUS * SNAPSHOT_RADIUS) {
                    nearPlayer = true;
                    break;
                }
            }
            if (nearPlayer) entities.add(EntitySnapshot.capture(entity));
        }
        return new WorldSnapshot(now, worldTime, players, entities);
    }

    // ── Player snapshot ───────────────────────────────────────────────────────

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
        /**
         * Inventory stored slot-by-slot as NbtList of NbtCompound.
         * Each entry: {slot: byte, item: NbtCompound via ItemStack.toNbt}
         */
        public final NbtCompound inventoryNbt;
        public final NbtCompound effectsNbt;
        public final boolean onGround;
        public final double velX, velY, velZ;
        public final int fireTicks;
        public final int air;

        private PlayerSnapshot(UUID uuid, String name,
                               double x, double y, double z, float yaw, float pitch,
                               float health, int foodLevel, float saturation,
                               int xpLevel, float xpProgress, int score,
                               NbtCompound inventoryNbt, NbtCompound effectsNbt,
                               boolean onGround, double velX, double velY, double velZ,
                               int fireTicks, int air) {
            this.uuid = uuid; this.name = name;
            this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch;
            this.health = health; this.foodLevel = foodLevel; this.saturation = saturation;
            this.xpLevel = xpLevel; this.xpProgress = xpProgress; this.score = score;
            this.inventoryNbt = inventoryNbt; this.effectsNbt = effectsNbt;
            this.onGround = onGround;
            this.velX = velX; this.velY = velY; this.velZ = velZ;
            this.fireTicks = fireTicks; this.air = air;
        }

        public static PlayerSnapshot capture(ServerPlayerEntity player) {
            // --- Inventory: store each slot manually using NbtWriteView ---
            // PlayerInventory.writeData(WriteView.ListAppender<StackWithSlot>) requires a ListAppender.
            NbtWriteView invView = NbtWriteView.create(ErrorReporter.EMPTY);
            WriteView.ListAppender<StackWithSlot> invAppender = invView.getListAppender("inventory", StackWithSlot.CODEC);
            player.getInventory().writeData(invAppender);
            NbtCompound invWrapper = new NbtCompound();
            invWrapper.put("inventory", invView.getNbt());

            // --- Status effects: use CODEC (writeNbt removed in 1.21.6+) ---
            NbtList effectList = new NbtList();
            player.getActiveStatusEffects().forEach((effect, instance) -> {
                DataResult<NbtElement> result = StatusEffectInstance.CODEC
                        .encodeStart(NbtOps.INSTANCE, instance);
                result.result().ifPresent(tag -> {
                    if (tag instanceof NbtCompound effectTag) effectList.add(effectTag);
                });
            });
            NbtCompound effectsNbt = new NbtCompound();
            effectsNbt.put("effects", effectList);

            return new PlayerSnapshot(
                    player.getUuid(), player.getName().getString(),
                    player.getX(), player.getY(), player.getZ(),
                    player.getYaw(), player.getPitch(),
                    player.getHealth(),
                    player.getHungerManager().getFoodLevel(),
                    player.getHungerManager().getSaturationLevel(),
                    player.experienceLevel, player.experienceProgress, player.getScore(),
                    invWrapper, effectsNbt,
                    player.isOnGround(),
                    player.getVelocity().x, player.getVelocity().y, player.getVelocity().z,
                    player.getFireTicks(), player.getAir()
            );
        }
    }

    // ── Entity snapshot ───────────────────────────────────────────────────────

    public static class EntitySnapshot {
        public final UUID uuid;
        public final String entityType;
        public final double x, y, z;
        public final float yaw, pitch;
        public final double velX, velY, velZ;
        /**
         * Full entity data captured via NbtWriteView + entity.writeFullData(WriteView).
         * This is the 1.21.11 replacement for saveNbt(NbtCompound).
         */
        public final NbtCompound fullNbt;

        private EntitySnapshot(UUID uuid, String entityType,
                               double x, double y, double z, float yaw, float pitch,
                               double velX, double velY, double velZ, NbtCompound fullNbt) {
            this.uuid = uuid; this.entityType = entityType;
            this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch;
            this.velX = velX; this.velY = velY; this.velZ = velZ;
            this.fullNbt = fullNbt;
        }

        public static EntitySnapshot capture(Entity entity) {
            // saveNbt/writeNbt are not accessible in Yarn 1.21.11+build.4 mapping.
            // Build a minimal NbtCompound manually with the data RewindManager actually uses.
            // RewindManager only reads: "Health" (for LivingEntity restoration).
            NbtCompound entityNbt = new NbtCompound();
            if (entity instanceof net.minecraft.entity.LivingEntity living) {
                entityNbt.putFloat("Health", living.getHealth());
            }
            Identifier typeId = Registries.ENTITY_TYPE.getId(entity.getType());
            return new EntitySnapshot(
                    entity.getUuid(), typeId.toString(),
                    entity.getX(), entity.getY(), entity.getZ(),
                    entity.getYaw(), entity.getPitch(),
                    entity.getVelocity().x, entity.getVelocity().y, entity.getVelocity().z,
                    entityNbt
            );
        }
    }
}
