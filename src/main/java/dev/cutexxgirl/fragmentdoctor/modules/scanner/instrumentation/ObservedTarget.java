package dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import java.util.Locale;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.Event;

public record ObservedTarget(
        String kind,
        String id,
        String ownerModId,
        String dimension,
        String position,
        String chunk,
        String className,
        String detail
) {
    public static ObservedTarget server(String detail) {
        return new ObservedTarget("server", "minecraft:server", "minecraft", null, null, null, null, detail);
    }

    public static ObservedTarget client(String section, String detail) {
        return new ObservedTarget("client", section, "client", null, null, null, null, detail);
    }

    public static ObservedTarget eventListener(Event event, Object listener) {
        String eventClass = event == null ? "unknown_event" : event.getClass().getName();
        String listenerClass = listener == null ? "unknown_listener" : listener.getClass().getName();
        return new ObservedTarget(
                "event_listener",
                eventClass,
                "event_bus",
                null,
                null,
                null,
                listenerClass,
                listener == null ? "unknown" : listener.toString()
        );
    }

    public static ObservedTarget level(ServerLevel level) {
        String dimension = dimension(level);
        return new ObservedTarget("level", dimension, "minecraft", dimension, null, null, level.getClass().getName(), level.getWatchdogStats());
    }

    public static ObservedTarget chunk(ServerLevel level, LevelChunk chunk) {
        String dimension = dimension(level);
        String chunkPos = chunk.getPos().x + "," + chunk.getPos().z;
        return new ObservedTarget("chunk", dimension + ":" + chunkPos, "minecraft", dimension, null, chunkPos, chunk.getClass().getName(), "random/block chunk tick");
    }

    public static ObservedTarget entity(ServerLevel level, Entity entity) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        String id = key.toString();
        BlockPos pos = entity.blockPosition();
        return new ObservedTarget(
                "entity",
                id,
                key.getNamespace(),
                dimension(level),
                pos(pos),
                entity.chunkPosition().x + "," + entity.chunkPosition().z,
                entity.getClass().getName(),
                "uuid=" + entity.getUUID() + ", name=" + entity.getName().getString()
        );
    }

    public static ObservedTarget goal(WrappedGoal wrappedGoal, Mob owner, String phase) {
        String goalClass = wrappedGoal.getGoal().getClass().getName();
        return livingAiTarget("ai_goal", goalClass, goalClass, owner, "priority=" + wrappedGoal.getPriority() + ", phase=" + phase);
    }

    public static ObservedTarget brainBehavior(BehaviorControl<?> behavior, LivingEntity owner, String phase) {
        String behaviorClass = behavior.getClass().getName();
        return livingAiTarget("brain_behavior", behaviorClass, behaviorClass, owner, "phase=" + phase + ", debug=" + behavior.debugString());
    }

    public static ObservedTarget brainSensor(Sensor<?> sensor, LivingEntity owner) {
        String sensorClass = sensor.getClass().getName();
        return livingAiTarget("brain_sensor", sensorClass, sensorClass, owner, "sensor_tick");
    }

    public static ObservedTarget navigation(PathNavigation navigation, Mob owner) {
        String navigationClass = navigation.getClass().getName();
        return livingAiTarget("navigation", navigationClass, navigationClass, owner, "path_navigation_tick");
    }

    public static ObservedTarget entityQuery(Level level, AABB bounds, int resultCount, String queryKind) {
        return entityQuery(level, bounds, resultCount, queryKind, null);
    }

    public static ObservedTarget entityQuery(Level level, AABB bounds, int resultCount, String queryKind, String context) {
        double centerX = (bounds.minX + bounds.maxX) / 2.0D;
        double centerY = (bounds.minY + bounds.maxY) / 2.0D;
        double centerZ = (bounds.minZ + bounds.maxZ) / 2.0D;
        int chunkX = (int)Math.floor(centerX) >> 4;
        int chunkZ = (int)Math.floor(centerZ) >> 4;
        double sizeX = bounds.maxX - bounds.minX;
        double sizeY = bounds.maxY - bounds.minY;
        double sizeZ = bounds.maxZ - bounds.minZ;
        String dimension = level.dimension().location().toString();
        String detail = String.format(Locale.ROOT, "aabb=%.2fx%.2fx%.2f, results=%d", sizeX, sizeY, sizeZ, resultCount);
        if (context != null && !context.isBlank()) {
            detail += ", " + context;
        }
        return new ObservedTarget(
                "entity_query",
                queryKind,
                "entity_query",
                dimension,
                (int)Math.floor(centerX) + "," + (int)Math.floor(centerY) + "," + (int)Math.floor(centerZ),
                chunkX + "," + chunkZ,
                "net.minecraft.world.level.Level",
                detail
        );
    }

    public static ObservedTarget blockEntity(BlockEntity blockEntity) {
        ResourceLocation key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
        String id = key == null ? blockEntity.getType().toString() : key.toString();
        String owner = key == null ? "unknown" : key.getNamespace();
        BlockPos pos = blockEntity.getBlockPos();
        String dimension = blockEntity.getLevel() == null ? null : blockEntity.getLevel().dimension().location().toString();
        return new ObservedTarget(
                "block_entity",
                id,
                owner,
                dimension,
                pos(pos),
                (pos.getX() >> 4) + "," + (pos.getZ() >> 4),
                blockEntity.getClass().getName(),
                "removed=" + blockEntity.isRemoved()
        );
    }

    public static ObservedTarget blockTick(ServerLevel level, BlockState state, BlockPos pos) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return new ObservedTarget(
                "block_tick",
                key.toString(),
                key.getNamespace(),
                dimension(level),
                pos(pos),
                (pos.getX() >> 4) + "," + (pos.getZ() >> 4),
                state.getBlock().getClass().getName(),
                "scheduled/random block tick"
        );
    }

    public static ObservedTarget scheduledTick(Object type, BlockPos pos) {
        ResourceLocation key;
        String kind;
        String className = type == null ? "unknown" : type.getClass().getName();
        if (type instanceof Block block) {
            key = BuiltInRegistries.BLOCK.getKey(block);
            kind = "scheduled_block_tick";
        } else if (type instanceof Fluid fluid) {
            key = BuiltInRegistries.FLUID.getKey(fluid);
            kind = "scheduled_fluid_tick";
        } else {
            String fallback = type == null ? "unknown" : type.toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_/.-]", "_");
            key = ResourceLocation.withDefaultNamespace(fallback);
            kind = "scheduled_tick";
        }
        return new ObservedTarget(
                kind,
                key.toString(),
                key.getNamespace(),
                null,
                pos(pos),
                (pos.getX() >> 4) + "," + (pos.getZ() >> 4),
                className,
                "scheduled tick enqueue"
        );
    }

    private static String dimension(ServerLevel level) {
        return level.dimension().location().toString();
    }

    private static ObservedTarget livingAiTarget(String kind, String id, String className, LivingEntity entity, String detail) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        BlockPos pos = entity.blockPosition();
        String dimension = entity.level().dimension().location().toString();
        return new ObservedTarget(
                kind,
                id,
                key.getNamespace(),
                dimension,
                pos(pos),
                entity.chunkPosition().x + "," + entity.chunkPosition().z,
                className,
                detail + ", entity=" + key + ", uuid=" + entity.getUUID() + ", name=" + entity.getName().getString()
        );
    }

    private static String pos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
