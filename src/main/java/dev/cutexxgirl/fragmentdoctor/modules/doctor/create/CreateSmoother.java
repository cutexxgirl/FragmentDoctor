package dev.cutexxgirl.fragmentdoctor.modules.doctor.create;

import com.google.gson.JsonObject;
import dev.cutexxgirl.fragmentdoctor.FragmentDoctor;
import dev.cutexxgirl.fragmentdoctor.modules.doctor.leafstorm.LeafstormConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class CreateSmoother {
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Map<String, TickState> BLOCK_ENTITIES = new LinkedHashMap<>();
    private static final Map<String, TickState> ENTITIES = new LinkedHashMap<>();
    private static long serverTickIndex;
    private static long tickStartNanos;
    private static double lastMspt;
    private static int remainingBlockEntityBudget;
    private static int remainingEntityBudget;
    private static int remainingHighRiskBudget;
    private static int remainingForcedBlockEntityBudget;
    private static int remainingForcedEntityBudget;
    private static long lastSummaryLogTick = Long.MIN_VALUE;
    private static Path logPath;
    private static TickCounters counters = new TickCounters();
    private static TickCounters lastCounters = new TickCounters();

    private CreateSmoother() {
    }

    public static void onServerTickStart() {
        serverTickIndex++;
        tickStartNanos = System.nanoTime();
        counters = new TickCounters();
        counters.enabled = enabled();
        counters.pressured = pressured();
        remainingBlockEntityBudget = counters.pressured ? LeafstormConfig.VALUES.createSmootherBlockEntityBudget.get() : Integer.MAX_VALUE;
        remainingEntityBudget = counters.pressured ? LeafstormConfig.VALUES.createSmootherEntityBudget.get() : Integer.MAX_VALUE;
        remainingHighRiskBudget = counters.pressured ? LeafstormConfig.VALUES.createSmootherHighRiskBudget.get() : Integer.MAX_VALUE;
        remainingForcedBlockEntityBudget = counters.pressured ? Math.max(1, remainingBlockEntityBudget / 2) : Integer.MAX_VALUE;
        remainingForcedEntityBudget = counters.pressured ? Math.max(1, remainingEntityBudget / 2) : Integer.MAX_VALUE;
        counters.blockEntityBudget = remainingBlockEntityBudget == Integer.MAX_VALUE ? -1 : remainingBlockEntityBudget;
        counters.entityBudget = remainingEntityBudget == Integer.MAX_VALUE ? -1 : remainingEntityBudget;
    }

    public static void onServerTickEnd(MinecraftServer server) {
        if (tickStartNanos != 0L) {
            lastMspt = (System.nanoTime() - tickStartNanos) / 1_000_000.0D;
            tickStartNanos = 0L;
        }
        prune(BLOCK_ENTITIES);
        prune(ENTITIES);
        counters.trackedBlockEntities = BLOCK_ENTITIES.size();
        counters.trackedEntities = ENTITIES.size();
        lastCounters = counters.copy();
        writeSummaryIfNeeded(server);
    }

    public static boolean shouldSkipBlockEntityTick(BlockEntity blockEntity) {
        if (!enabled() || !pressured() || blockEntity == null || !(blockEntity.getLevel() instanceof ServerLevel level) || !isCreateBlockEntity(blockEntity)) {
            return false;
        }
        String key = blockEntityKey(level, blockEntity);
        counters.seenBlockEntities++;
        return shouldSkip(BLOCK_ENTITIES.computeIfAbsent(key, ignored -> new TickState(key)), true, isHighRiskCreateClass(blockEntity.getClass().getName()));
    }

    public static boolean shouldSkipEntityTick(ServerLevel level, Entity entity) {
        if (!enabled() || !pressured() || entity == null || !isCreateEntity(entity)) {
            return false;
        }
        String key = entityKey(level, entity);
        counters.seenEntities++;
        return shouldSkip(ENTITIES.computeIfAbsent(key, ignored -> new TickState(key)), false, isHighRiskCreateClass(entity.getClass().getName()));
    }

    public static Status status() {
        TickCounters snapshot = lastCounters;
        return new Status(
                enabled(),
                lastMspt,
                snapshot.pressured,
                snapshot.blockEntityBudget,
                snapshot.entityBudget,
                snapshot.seenBlockEntities,
                snapshot.allowedBlockEntities,
                snapshot.skippedBlockEntities,
                snapshot.seenEntities,
                snapshot.allowedEntities,
                snapshot.skippedEntities,
                snapshot.forcedTicks,
                snapshot.trackedBlockEntities,
                snapshot.trackedEntities
        );
    }

    public static void clearForServerStop() {
        BLOCK_ENTITIES.clear();
        ENTITIES.clear();
        serverTickIndex = 0L;
        tickStartNanos = 0L;
        lastMspt = 0.0D;
        remainingBlockEntityBudget = 0;
        remainingEntityBudget = 0;
        remainingHighRiskBudget = 0;
        remainingForcedBlockEntityBudget = 0;
        remainingForcedEntityBudget = 0;
        lastSummaryLogTick = Long.MIN_VALUE;
        logPath = null;
        counters = new TickCounters();
        lastCounters = new TickCounters();
    }

    private static boolean shouldSkip(TickState state, boolean blockEntity, boolean highRisk) {
        state.lastSeenTick = serverTickIndex;
        long age = serverTickIndex - state.firstSeenTick;
        if (serverTickIndex > state.smoothingUntilTick) {
            state.consecutiveSkips = 0;
            allow(blockEntity);
            return false;
        }
        if (highRisk && age <= LeafstormConfig.VALUES.createSmootherHighRiskInitialDelayTicks.get()) {
            skip(blockEntity, state);
            return true;
        }
        int maxSkips = LeafstormConfig.VALUES.createSmootherMaxConsecutiveSkips.get();
        if (state.consecutiveSkips >= maxSkips) {
            if ((!highRisk || tryUseHighRiskBudget()) && tryUseForcedBudget(blockEntity)) {
                state.consecutiveSkips = 0;
                counters.forcedTicks++;
                allow(blockEntity);
                return false;
            }
            skip(blockEntity, state);
            return true;
        }
        if (highRisk && !tryUseHighRiskBudget()) {
            skip(blockEntity, state);
            return true;
        }
        if (blockEntity ? remainingBlockEntityBudget > 0 : remainingEntityBudget > 0) {
            if (blockEntity) {
                remainingBlockEntityBudget--;
            } else {
                remainingEntityBudget--;
            }
            state.consecutiveSkips = 0;
            allow(blockEntity);
            return false;
        }
        skip(blockEntity, state);
        return true;
    }

    private static boolean tryUseHighRiskBudget() {
        if (remainingHighRiskBudget <= 0) {
            return false;
        }
        remainingHighRiskBudget--;
        return true;
    }

    private static boolean tryUseForcedBudget(boolean blockEntity) {
        if (blockEntity) {
            if (remainingForcedBlockEntityBudget <= 0) {
                return false;
            }
            remainingForcedBlockEntityBudget--;
            return true;
        }
        if (remainingForcedEntityBudget <= 0) {
            return false;
        }
        remainingForcedEntityBudget--;
        return true;
    }

    private static void skip(boolean blockEntity, TickState state) {
        state.consecutiveSkips++;
        if (blockEntity) {
            counters.skippedBlockEntities++;
        } else {
            counters.skippedEntities++;
        }
    }

    private static void allow(boolean blockEntity) {
        if (blockEntity) {
            counters.allowedBlockEntities++;
        } else {
            counters.allowedEntities++;
        }
    }

    private static void prune(Map<String, TickState> states) {
        long keepTicks = LeafstormConfig.VALUES.createSmootherWarmupTicks.get() * 2L;
        Iterator<Map.Entry<String, TickState>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            if (serverTickIndex - iterator.next().getValue().lastSeenTick > keepTicks) {
                iterator.remove();
            }
        }
    }

    private static boolean enabled() {
        return LeafstormConfig.VALUES.createSmootherEnabled.get();
    }

    private static boolean pressured() {
        return lastMspt >= LeafstormConfig.VALUES.createSmootherMsptThreshold.get();
    }

    private static boolean isCreateBlockEntity(BlockEntity blockEntity) {
        ResourceLocation key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
        return (key != null && "create".equals(key.getNamespace()))
                || blockEntity.getClass().getName().startsWith("com.simibubi.create.");
    }

    private static boolean isCreateEntity(Entity entity) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return (key != null && "create".equals(key.getNamespace()))
                || entity.getClass().getName().startsWith("com.simibubi.create.");
    }

    private static boolean isHighRiskCreateClass(String className) {
        return className.startsWith("com.simibubi.create.content.kinetics.")
                || className.startsWith("com.simibubi.create.content.fluids.")
                || className.startsWith("com.simibubi.create.content.contraptions.");
    }

    private static String blockEntityKey(ServerLevel level, BlockEntity blockEntity) {
        ResourceLocation key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
        BlockPos pos = blockEntity.getBlockPos();
        return level.dimension().location() + ":" + (key == null ? blockEntity.getClass().getName() : key) + "@" + pos.asLong();
    }

    private static String entityKey(ServerLevel level, Entity entity) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return level.dimension().location() + ":" + (key == null ? entity.getClass().getName() : key) + "@" + entity.getUUID();
    }

    private static void writeSummaryIfNeeded(MinecraftServer server) {
        if (!enabled() || counters.events() <= 0) {
            return;
        }
        int logEveryTicks = LeafstormConfig.VALUES.createSmootherLogEveryTicks.get();
        if (lastSummaryLogTick != Long.MIN_VALUE && serverTickIndex - lastSummaryLogTick < logEveryTicks) {
            return;
        }
        lastSummaryLogTick = serverTickIndex;
        if (LeafstormConfig.VALUES.consoleLogging.get()) {
            FragmentDoctor.LOGGER.warn(
                    "Create smoother: pressured={} mspt={} be_seen={} be_allowed={} be_skipped={} entity_seen={} entity_allowed={} entity_skipped={} forced={} tracked_be={} tracked_entities={}",
                    counters.pressured,
                    String.format(Locale.ROOT, "%.2f", lastMspt),
                    counters.seenBlockEntities,
                    counters.allowedBlockEntities,
                    counters.skippedBlockEntities,
                    counters.seenEntities,
                    counters.allowedEntities,
                    counters.skippedEntities,
                    counters.forcedTicks,
                    counters.trackedBlockEntities,
                    counters.trackedEntities
            );
        }
        if (LeafstormConfig.VALUES.fileLogging.get()) {
            writeJsonlSummary(server);
        }
    }

    private static void writeJsonlSummary(MinecraftServer server) {
        JsonObject summary = new JsonObject();
        summary.addProperty("schema", "fragmentdoctor.create_smoother.v1");
        summary.addProperty("event", "create_smoother_tick");
        summary.addProperty("server_tick", server.getTickCount());
        summary.addProperty("last_mspt", lastMspt);
        summary.addProperty("pressured", counters.pressured);
        summary.addProperty("block_entity_budget", counters.blockEntityBudget);
        summary.addProperty("entity_budget", counters.entityBudget);
        summary.addProperty("seen_block_entities", counters.seenBlockEntities);
        summary.addProperty("allowed_block_entities", counters.allowedBlockEntities);
        summary.addProperty("skipped_block_entities", counters.skippedBlockEntities);
        summary.addProperty("seen_entities", counters.seenEntities);
        summary.addProperty("allowed_entities", counters.allowedEntities);
        summary.addProperty("skipped_entities", counters.skippedEntities);
        summary.addProperty("forced_ticks", counters.forcedTicks);
        summary.addProperty("tracked_block_entities", counters.trackedBlockEntities);
        summary.addProperty("tracked_entities", counters.trackedEntities);
        appendJsonl(server, summary);
    }

    private static void appendJsonl(MinecraftServer server, JsonObject object) {
        Path path = logPath;
        if (path == null) {
            path = server.getServerDirectory()
                    .resolve("fragmentdoctor")
                    .resolve("doctor")
                    .resolve("create")
                    .resolve("create-smoother-" + FILE_STAMP.format(Instant.now()) + ".jsonl");
            logPath = path;
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, object + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            FragmentDoctor.LOGGER.error("Failed to write Create smoother log {}", path, exception);
        }
    }

    private static final class TickState {
        private final long firstSeenTick = serverTickIndex;
        private final long smoothingUntilTick;
        private long lastSeenTick = serverTickIndex;
        private int consecutiveSkips;

        private TickState(String key) {
            int warmupTicks = LeafstormConfig.VALUES.createSmootherWarmupTicks.get();
            int releaseSpread = Math.max(1, warmupTicks);
            this.smoothingUntilTick = serverTickIndex + warmupTicks + Math.floorMod(key.hashCode(), releaseSpread);
        }
    }

    private static final class TickCounters {
        private boolean enabled;
        private boolean pressured;
        private int blockEntityBudget;
        private int entityBudget;
        private int seenBlockEntities;
        private int allowedBlockEntities;
        private int skippedBlockEntities;
        private int seenEntities;
        private int allowedEntities;
        private int skippedEntities;
        private int forcedTicks;
        private int trackedBlockEntities;
        private int trackedEntities;

        private int events() {
            return seenBlockEntities + skippedBlockEntities + seenEntities + skippedEntities;
        }

        private TickCounters copy() {
            TickCounters copy = new TickCounters();
            copy.enabled = enabled;
            copy.pressured = pressured;
            copy.blockEntityBudget = blockEntityBudget;
            copy.entityBudget = entityBudget;
            copy.seenBlockEntities = seenBlockEntities;
            copy.allowedBlockEntities = allowedBlockEntities;
            copy.skippedBlockEntities = skippedBlockEntities;
            copy.seenEntities = seenEntities;
            copy.allowedEntities = allowedEntities;
            copy.skippedEntities = skippedEntities;
            copy.forcedTicks = forcedTicks;
            copy.trackedBlockEntities = trackedBlockEntities;
            copy.trackedEntities = trackedEntities;
            return copy;
        }
    }

    public record Status(
            boolean enabled,
            double lastMspt,
            boolean pressured,
            int blockEntityBudget,
            int entityBudget,
            int seenBlockEntities,
            int allowedBlockEntities,
            int skippedBlockEntities,
            int seenEntities,
            int allowedEntities,
            int skippedEntities,
            int forcedTicks,
            int trackedBlockEntities,
            int trackedEntities
    ) {
    }
}
