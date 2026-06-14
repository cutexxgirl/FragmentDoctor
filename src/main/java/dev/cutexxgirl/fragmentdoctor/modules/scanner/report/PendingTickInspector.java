package dev.cutexxgirl.fragmentdoctor.modules.scanner.report;

import dev.cutexxgirl.fragmentdoctor.modules.scanner.mixin.LevelChunkTicksAccessor;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.mixin.LevelTicksAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Function;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.ScheduledTick;

public final class PendingTickInspector {
    private static final int TYPE_SAMPLES_PER_CONTAINER = 32;
    private static final int MAX_TYPE_SAMPLES = 8192;

    private PendingTickInspector() {
    }

    public static List<PendingTickSnapshot> capture(MinecraftServer server) {
        List<PendingTickSnapshot> snapshots = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            String dimension = level.dimension().location().toString();
            long gameTime = level.getGameTime();
            snapshots.add(captureQueue(
                    dimension,
                    "block",
                    level.getBlockTicks(),
                    gameTime,
                    block -> registryKey(BuiltInRegistries.BLOCK.getKey(block), block)
            ));
            snapshots.add(captureQueue(
                    dimension,
                    "fluid",
                    level.getFluidTicks(),
                    gameTime,
                    fluid -> registryKey(BuiltInRegistries.FLUID.getKey(fluid), fluid)
            ));
        }
        return snapshots;
    }

    @SuppressWarnings("unchecked")
    private static <T> PendingTickSnapshot captureQueue(
            String dimension,
            String queueKind,
            LevelTickAccess<T> tickAccess,
            long gameTime,
            Function<T, String> typeName
    ) {
        int total = tickAccess.count();
        try {
            return inspectQueue(dimension, queueKind, tickAccess, gameTime, typeName, total);
        } catch (RuntimeException exception) {
            return new PendingTickSnapshot(
                    dimension,
                    queueKind,
                    total,
                    0,
                    0,
                    total == 0,
                    "inspection_failed:" + exception.getClass().getSimpleName(),
                    Map.of(),
                    Map.of(),
                    Map.of()
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> PendingTickSnapshot inspectQueue(
            String dimension,
            String queueKind,
            LevelTickAccess<T> tickAccess,
            long gameTime,
            Function<T, String> typeName,
            int total
    ) {
        Map<String, Integer> types = new LinkedHashMap<>();
        Map<String, Integer> chunks = new LinkedHashMap<>();
        Map<String, Integer> dueBuckets = new LinkedHashMap<>();
        int sampled = 0;
        int containers = 0;

        LevelTicksAccessor<T> levelAccessor = (LevelTicksAccessor<T>)(Object)tickAccess;
        Long2ObjectMap<LevelChunkTicks<T>> allContainers = levelAccessor.fragmentdoctor$getAllContainers();
        if (allContainers == null) {
            throw new IllegalStateException("allContainers accessor returned null");
        }
        for (Long2ObjectMap.Entry<LevelChunkTicks<T>> entry : allContainers.long2ObjectEntrySet()) {
            LevelChunkTicks<T> container = entry.getValue();
            if (container == null) {
                continue;
            }
            int containerCount = container.count();
            if (containerCount <= 0) {
                continue;
            }
            containers++;
            chunks.put(chunkKey(entry.getLongKey()), containerCount);
        }

        int samplesPerContainer = containers == 0
                ? 0
                : Math.min(TYPE_SAMPLES_PER_CONTAINER, Math.max(1, MAX_TYPE_SAMPLES / containers));
        for (Long2ObjectMap.Entry<LevelChunkTicks<T>> entry : allContainers.long2ObjectEntrySet()) {
            if (sampled >= MAX_TYPE_SAMPLES) {
                break;
            }
            LevelChunkTicks<T> container = entry.getValue();
            if (container == null || container.count() <= 0) {
                continue;
            }
            LevelChunkTicksAccessor<T> containerAccessor = (LevelChunkTicksAccessor<T>)(Object)container;
            int remaining = Math.min(samplesPerContainer, MAX_TYPE_SAMPLES - sampled);
            Queue<ScheduledTick<T>> queue = containerAccessor.fragmentdoctor$getTickQueue();
            if (queue != null) {
                for (ScheduledTick<T> tick : queue) {
                    if (tick == null) {
                        continue;
                    }
                    addSample(types, dueBuckets, typeName.apply(tick.type()), dueBucket(tick.triggerTick() - gameTime));
                    sampled++;
                    if (--remaining == 0) {
                        break;
                    }
                }
            }

            if (remaining > 0) {
                List<SavedTick<T>> pending = containerAccessor.fragmentdoctor$getPendingTicks();
                if (pending != null) {
                    for (SavedTick<T> tick : pending) {
                        if (tick == null) {
                            continue;
                        }
                        addSample(types, dueBuckets, typeName.apply(tick.type()), dueBucket(tick.delay()));
                        sampled++;
                        if (--remaining == 0) {
                            break;
                        }
                    }
                }
            }
        }

        return new PendingTickSnapshot(
                dimension,
                queueKind,
                total,
                containers,
                sampled,
                sampled >= total,
                "ok",
                types,
                chunks,
                dueBuckets
        );
    }

    private static void addSample(Map<String, Integer> types, Map<String, Integer> dueBuckets, String type, String dueBucket) {
        types.merge(type, 1, Integer::sum);
        dueBuckets.merge(dueBucket, 1, Integer::sum);
    }

    private static String dueBucket(long delay) {
        if (delay <= 0L) {
            return "due_or_overdue";
        }
        if (delay <= 20L) {
            return "due_within_1s";
        }
        if (delay <= 200L) {
            return "due_within_10s";
        }
        return "due_later";
    }

    private static String chunkKey(long packed) {
        int x = (int)packed;
        int z = (int)(packed >>> 32);
        return x + "," + z;
    }

    private static String registryKey(Object key, Object fallback) {
        if (key != null) {
            return key.toString();
        }
        return fallback == null ? "unknown:null" : fallback.getClass().getName();
    }
}
