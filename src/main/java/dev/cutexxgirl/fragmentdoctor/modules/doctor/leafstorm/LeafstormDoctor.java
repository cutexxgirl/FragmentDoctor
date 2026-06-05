package dev.cutexxgirl.fragmentdoctor.modules.doctor.leafstorm;

import com.google.gson.JsonObject;
import dev.cutexxgirl.fragmentdoctor.FragmentDoctor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;

public final class LeafstormDoctor {
    private static final Decision RUN = new Decision(Action.RUN, 0);
    private static long serverTickIndex;
    private static long tickStartNanos;
    private static double lastMspt;
    private static int lastTickQueueDepth;
    private static int previousTickQueueDepth;
    private static int observedQueueDepthThisTick;
    private static QueueTrend queueTrend = QueueTrend.UNKNOWN;
    private static int currentBudget = Integer.MAX_VALUE;
    private static int remainingBudget = Integer.MAX_VALUE;
    private static Mode currentMode = Mode.OFF;
    private static Mode lastLoggedMode = Mode.OFF;
    private static long lastSummaryLogTick = Long.MIN_VALUE;
    private static int deferredQueueSize;
    private static int currentDrainBudget;
    private static int remainingDrainBudget;
    private static boolean drainPaused;
    private static String drainPauseReason = "none";
    private static final Map<Object, DeferredLeafTickQueue> DEFERRED_QUEUES = new IdentityHashMap<>();
    private static TickCounters counters = new TickCounters();
    private static TickCounters lastCounters = new TickCounters();

    private LeafstormDoctor() {
    }

    public static void onServerTickStart(MinecraftServer server) {
        serverTickIndex++;
        tickStartNanos = System.nanoTime();
        previousTickQueueDepth = lastTickQueueDepth;
        observedQueueDepthThisTick = 0;
        counters = new TickCounters();
        currentMode = modeFor(lastMspt, previousTickQueueDepth);
        currentBudget = budgetFor(currentMode);
        remainingBudget = currentBudget;
        currentDrainBudget = drainBudgetForCurrentTick();
        remainingDrainBudget = currentDrainBudget;
        counters.drainBudget = currentDrainBudget;
        counters.remainingDrainBudget = remainingDrainBudget;
        counters.drainPaused = drainPaused;
        counters.drainPauseReason = drainPauseReason;
    }

    public static void onServerTickEnd(MinecraftServer server) {
        if (tickStartNanos != 0L) {
            lastMspt = (System.nanoTime() - tickStartNanos) / 1_000_000.0D;
        }
        lastTickQueueDepth = Math.max(observedQueueDepthThisTick, counters.lastQueueDepth);
        queueTrend = trendFor(previousTickQueueDepth, lastTickQueueDepth);
        lastCounters = counters.copy();
        writeSummaryIfNeeded(server);
    }

    public static Decision beforeScheduledTick(Object tickAccess, Object type, BlockPos pos, int queueDepth, long gameTime) {
        if (!LeafstormConfig.enabled() || !(type instanceof Block block) || !(block instanceof LeavesBlock)) {
            return RUN;
        }
        updateLivePressure(queueDepth);
        QueueTrend liveTrend = trendFor(previousTickQueueDepth, observedQueueDepthThisTick);
        Mode liveMode = modeFor(lastMspt, observedQueueDepthThisTick, liveTrend);
        if (liveMode.moreSevereThan(currentMode)) {
            currentMode = liveMode;
            currentBudget = budgetFor(liveMode);
            remainingBudget = Math.min(remainingBudget, currentBudget);
        }

        counters.seenLeaves++;
        counters.lastQueueDepth = Math.max(counters.lastQueueDepth, queueDepth);
        counters.mode = currentMode;
        counters.queueTrend = liveTrend;
        String blockId = blockId(block);
        counters.lastBlockId = blockId;
        counters.incrementLeaf(blockId);

        DeferredLeafTickKey key = new DeferredLeafTickKey(blockId, pos.asLong());
        if (currentMode == Mode.OFF) {
            counters.allowedLeaves++;
            return RUN;
        }
        if (remainingBudget > 0) {
            remainingBudget--;
            counters.allowedLeaves++;
            return RUN;
        }
        if (isDuplicateDeferredTick(tickAccess, key, gameTime)) {
            counters.deduplicatedLeaves++;
            return new Decision(Action.DEDUPLICATE, 0);
        }
        counters.deferredLeaves++;
        int delayTicks = LeafstormConfig.VALUES.deferDelayTicks.get();
        rememberDeferredTick(tickAccess, key, type, pos, gameTime + delayTicks);
        return new Decision(Action.DEFER, delayTicks);
    }

    @SuppressWarnings("unchecked")
    public static <T> void drainDeferredTicks(Object tickAccess, BiConsumer<BlockPos, T> ticker, int queueDepth, long gameTime) {
        if (!LeafstormConfig.enabled()) {
            return;
        }
        DeferredLeafTickQueue queue = DEFERRED_QUEUES.get(tickAccess);
        if (queue == null || queue.queue.isEmpty()) {
            return;
        }
        updateLivePressure(queueDepth);
        counters.lastQueueDepth = Math.max(counters.lastQueueDepth, queueDepth);
        counters.mode = currentMode;
        counters.queueTrend = trendFor(previousTickQueueDepth, observedQueueDepthThisTick);

        counters.drainBudget = currentDrainBudget;
        counters.remainingDrainBudget = remainingDrainBudget;
        counters.drainPaused = drainPaused;
        counters.drainPauseReason = drainPauseReason;
        if (remainingDrainBudget <= 0) {
            counters.skippedDeferredDrains += readyDeferredTicks(queue, gameTime, 16);
            return;
        }
        long drainBudgetNanos = (long)(LeafstormConfig.VALUES.maxDeferredDrainMillisPerTick.get() * 1_000_000.0D);
        long deadlineNanos = drainBudgetNanos <= 0L ? 0L : System.nanoTime() + drainBudgetNanos;
        while (remainingDrainBudget > 0) {
            DeferredLeafTick tick = queue.queue.peek();
            if (tick == null || tick.triggerTick > gameTime) {
                break;
            }
            if (deadlineNanos > 0L && System.nanoTime() >= deadlineNanos) {
                counters.drainPaused = true;
                counters.drainPauseReason = "time_budget";
                break;
            }
            queue.queue.poll();
            if (queue.byKey.remove(tick.key) == null) {
                continue;
            }
            deferredQueueSize--;
            remainingDrainBudget--;
            counters.drainedDeferredLeaves++;
            counters.allowedLeaves++;
            counters.lastBlockId = tick.key.blockId();
            counters.incrementLeaf(tick.key.blockId());
            ticker.accept(tick.pos, (T)tick.type);
        }
        if (queue.queue.isEmpty()) {
            DEFERRED_QUEUES.remove(tickAccess);
        }
    }

    public static Status status() {
        TickCounters snapshot = lastCounters;
        return new Status(
                LeafstormConfig.enabled(),
                currentMode.name().toLowerCase(Locale.ROOT),
                queueTrend.name().toLowerCase(Locale.ROOT),
                lastMspt,
                lastTickQueueDepth,
                currentBudget == Integer.MAX_VALUE ? -1 : currentBudget,
                remainingBudget == Integer.MAX_VALUE ? -1 : remainingBudget,
                snapshot.drainBudget,
                snapshot.remainingDrainBudget,
                snapshot.drainPaused,
                snapshot.drainPauseReason,
                snapshot.seenLeaves,
                snapshot.allowedLeaves,
                snapshot.deferredLeaves,
                snapshot.drainedDeferredLeaves,
                snapshot.skippedDeferredDrains,
                snapshot.deduplicatedLeaves,
                deferredQueueSize,
                snapshot.topLeaves()
        );
    }

    private static void updateLivePressure(int queueDepth) {
        observedQueueDepthThisTick = Math.max(observedQueueDepthThisTick, queueDepth);
    }

    private static Mode modeFor(double mspt, int queueDepth) {
        return modeFor(mspt, queueDepth, queueTrend);
    }

    private static Mode modeFor(double mspt, int queueDepth, QueueTrend trend) {
        if (!LeafstormConfig.enabled()) {
            return Mode.OFF;
        }
        if (mspt >= LeafstormConfig.VALUES.emergencyMsptThreshold.get()) {
            return Mode.EMERGENCY;
        }
        if (queueDepth >= LeafstormConfig.VALUES.emergencyQueueDepth.get()) {
            boolean recovering = LeafstormConfig.VALUES.queueRecoveryAllowsHardBudget.get()
                    && trend == QueueTrend.SHRINKING
                    && mspt < LeafstormConfig.VALUES.hardMsptThreshold.get();
            return recovering ? Mode.HARD : Mode.EMERGENCY;
        }
        if (mspt >= LeafstormConfig.VALUES.hardMsptThreshold.get()) {
            return Mode.HARD;
        }
        if (mspt >= LeafstormConfig.VALUES.softMsptThreshold.get()) {
            return Mode.SOFT;
        }
        return Mode.OFF;
    }

    private static QueueTrend trendFor(int previousDepth, int currentDepth) {
        if (previousDepth <= 0 || currentDepth <= 0) {
            return QueueTrend.UNKNOWN;
        }
        int threshold = Math.max(1_000, previousDepth / 50);
        if (currentDepth > previousDepth + threshold) {
            return QueueTrend.GROWING;
        }
        if (currentDepth < previousDepth - threshold) {
            return QueueTrend.SHRINKING;
        }
        return QueueTrend.STABLE;
    }

    private static int budgetFor(Mode mode) {
        return switch (mode) {
            case SOFT -> LeafstormConfig.VALUES.softBudget.get();
            case HARD -> LeafstormConfig.VALUES.hardBudget.get();
            case EMERGENCY -> LeafstormConfig.VALUES.emergencyBudget.get();
            case OFF -> Integer.MAX_VALUE;
        };
    }

    private static int drainBudgetForCurrentTick() {
        drainPaused = false;
        drainPauseReason = "none";
        if (deferredQueueSize <= 0) {
            return 0;
        }
        int configuredBudget = LeafstormConfig.VALUES.maxDeferredDrainsPerTick.get();
        if (configuredBudget <= 0) {
            drainPaused = true;
            drainPauseReason = "disabled";
            return 0;
        }
        if (lastMspt >= LeafstormConfig.VALUES.deferredDrainMsptCeiling.get()) {
            drainPaused = true;
            drainPauseReason = "mspt";
            return 0;
        }
        int quietLeafTicks = LeafstormConfig.VALUES.deferredDrainQuietLeafTicks.get();
        if (quietLeafTicks > 0 && lastCounters.seenLeaves >= quietLeafTicks) {
            drainPaused = true;
            drainPauseReason = "active_leafstorm";
            return 0;
        }
        if (previousTickQueueDepth >= LeafstormConfig.VALUES.emergencyQueueDepth.get()
                && queueTrend != QueueTrend.SHRINKING
                && currentMode != Mode.OFF) {
            drainPaused = true;
            drainPauseReason = "queue_pressure";
            return 0;
        }
        return configuredBudget;
    }

    private static int readyDeferredTicks(DeferredLeafTickQueue queue, long gameTime, int limit) {
        int ready = 0;
        for (DeferredLeafTick tick : queue.queue) {
            if (tick.triggerTick > gameTime || ready >= limit) {
                break;
            }
            ready++;
        }
        return ready;
    }

    private static boolean isDuplicateDeferredTick(Object tickAccess, DeferredLeafTickKey key, long gameTime) {
        if (!LeafstormConfig.VALUES.enableDeduplication.get()) {
            return false;
        }
        DeferredLeafTickQueue queue = DEFERRED_QUEUES.get(tickAccess);
        if (queue == null) {
            return false;
        }
        DeferredLeafTick tick = queue.byKey.get(key);
        if (tick == null) {
            return false;
        }
        return tick.triggerTick > gameTime + LeafstormConfig.VALUES.dedupGraceTicks.get();
    }

    private static void rememberDeferredTick(Object tickAccess, DeferredLeafTickKey key, Object type, BlockPos pos, long triggerTick) {
        DeferredLeafTickQueue queue = DEFERRED_QUEUES.computeIfAbsent(tickAccess, ignored -> new DeferredLeafTickQueue());
        DeferredLeafTick previous = queue.byKey.get(key);
        if (previous != null) {
            previous.triggerTick = Math.max(previous.triggerTick, triggerTick);
            return;
        }
        DeferredLeafTick tick = new DeferredLeafTick(key, type, pos.immutable(), triggerTick);
        queue.byKey.put(key, tick);
        queue.queue.add(tick);
        deferredQueueSize++;
        enforceMaxDeferredQueueSize();
    }

    private static void enforceMaxDeferredQueueSize() {
        int maxKeys = LeafstormConfig.VALUES.maxDeferredDedupKeys.get();
        if (deferredQueueSize <= maxKeys) {
            return;
        }
        Iterator<Map.Entry<Object, DeferredLeafTickQueue>> outer = DEFERRED_QUEUES.entrySet().iterator();
        while (deferredQueueSize > maxKeys && outer.hasNext()) {
            Map.Entry<Object, DeferredLeafTickQueue> entry = outer.next();
            DeferredLeafTick tick = entry.getValue().queue.poll();
            if (tick != null && entry.getValue().byKey.remove(tick.key) != null) {
                deferredQueueSize--;
                counters.droppedDeferredLeaves++;
            }
            if (entry.getValue().queue.isEmpty()) {
                outer.remove();
            }
        }
    }

    private static void writeSummaryIfNeeded(MinecraftServer server) {
        if (!LeafstormConfig.enabled() || counters.mitigationEvents() <= 0) {
            if (currentMode == Mode.OFF) {
                lastLoggedMode = Mode.OFF;
            }
            return;
        }
        int logEveryTicks = LeafstormConfig.VALUES.logEveryTicks.get();
        boolean modeChanged = counters.mode != lastLoggedMode;
        boolean due = serverTickIndex - lastSummaryLogTick >= logEveryTicks;
        if (!modeChanged && !due) {
            return;
        }
        lastSummaryLogTick = serverTickIndex;
        lastLoggedMode = counters.mode;
        if (LeafstormConfig.VALUES.consoleLogging.get()) {
            FragmentDoctor.LOGGER.warn(
                    "Leafstorm limiter {}: deferred={} drained={} skipped_drain={} deduped={} dropped={} allowed={} budget={} drain_budget={} mspt={} queue={} internal_queue={} drain_pause={} trend={} top_leaves={}",
                    counters.mode.logName,
                    counters.deferredLeaves,
                    counters.drainedDeferredLeaves,
                    counters.skippedDeferredDrains,
                    counters.deduplicatedLeaves,
                    counters.droppedDeferredLeaves,
                    counters.allowedLeaves,
                    currentBudget,
                    currentDrainBudget,
                    String.format(Locale.ROOT, "%.2f", lastMspt),
                    counters.lastQueueDepth,
                    deferredQueueSize,
                    counters.drainPaused ? counters.drainPauseReason : "none",
                    counters.queueTrend.logName,
                    counters.topLeaves()
            );
        }
        if (LeafstormConfig.VALUES.fileLogging.get()) {
            writeJsonlSummary(server);
        }
    }

    private static void writeJsonlSummary(MinecraftServer server) {
        JsonObject summary = baseJsonEvent(server, "leafstorm_limiter_tick");
        addCommonSummaryFields(summary);
        summary.addProperty("last_leaf", counters.lastBlockId);
        summary.addProperty("top_leaves", counters.topLeaves());

        JsonObject budget = baseJsonEvent(server, "leafstorm_budget");
        addCommonSummaryFields(budget);
        budget.addProperty("remaining_budget", remainingBudget == Integer.MAX_VALUE ? -1 : remainingBudget);
        budget.addProperty("drain_budget", currentDrainBudget);
        budget.addProperty("remaining_drain_budget", remainingDrainBudget);
        budget.addProperty("drain_paused", counters.drainPaused);
        budget.addProperty("drain_pause_reason", counters.drainPauseReason);

        JsonObject queue = baseJsonEvent(server, "leafstorm_queue_snapshot");
        queue.addProperty("last_mspt", lastMspt);
        queue.addProperty("queue_depth", counters.lastQueueDepth);
        queue.addProperty("previous_queue_depth", previousTickQueueDepth);
        queue.addProperty("queue_trend", counters.queueTrend.logName);
        queue.addProperty("deferred_queue_size", deferredQueueSize);

        appendJsonl(server, summary, budget, queue);

        if (counters.deferredLeaves > 0) {
            JsonObject deferred = baseJsonEvent(server, "leafstorm_deferred");
            addCommonSummaryFields(deferred);
            deferred.addProperty("deferred_leaf_ticks", counters.deferredLeaves);
            deferred.addProperty("origin", "doctor_deferred");
            appendJsonl(server, deferred);
        }
        if (counters.drainedDeferredLeaves > 0) {
            JsonObject executed = baseJsonEvent(server, "leafstorm_executed");
            addCommonSummaryFields(executed);
            executed.addProperty("executed_deferred_leaf_ticks", counters.drainedDeferredLeaves);
            appendJsonl(server, executed);
        }
        if (counters.deduplicatedLeaves > 0) {
            JsonObject deduplicated = baseJsonEvent(server, "leafstorm_deduplicated");
            addCommonSummaryFields(deduplicated);
            deduplicated.addProperty("deduplicated_leaf_ticks", counters.deduplicatedLeaves);
            deduplicated.addProperty("origin", "doctor_deduplicated");
            appendJsonl(server, deduplicated);
        }
    }

    private static JsonObject baseJsonEvent(MinecraftServer server, String event) {
        JsonObject object = new JsonObject();
        object.addProperty("schema", "fragmentdoctor.leafstorm_doctor.v2");
        object.addProperty("event", event);
        object.addProperty("server_tick", server.getTickCount());
        return object;
    }

    private static void addCommonSummaryFields(JsonObject object) {
        object.addProperty("mode", counters.mode.logName);
        object.addProperty("last_mspt", lastMspt);
        object.addProperty("queue_depth", counters.lastQueueDepth);
        object.addProperty("queue_trend", counters.queueTrend.logName);
        object.addProperty("budget", currentBudget == Integer.MAX_VALUE ? -1 : currentBudget);
        object.addProperty("drain_budget", counters.drainBudget);
        object.addProperty("remaining_drain_budget", counters.remainingDrainBudget);
        object.addProperty("drain_paused", counters.drainPaused);
        object.addProperty("drain_pause_reason", counters.drainPauseReason);
        object.addProperty("allowed_leaf_ticks", counters.allowedLeaves);
        object.addProperty("deferred_leaf_ticks", counters.deferredLeaves);
        object.addProperty("drained_deferred_leaf_ticks", counters.drainedDeferredLeaves);
        object.addProperty("skipped_deferred_leaf_ticks", counters.skippedDeferredDrains);
        object.addProperty("deduplicated_leaf_ticks", counters.deduplicatedLeaves);
        object.addProperty("dropped_deferred_leaf_ticks", counters.droppedDeferredLeaves);
        object.addProperty("deferred_queue_size", deferredQueueSize);
    }

    private static void appendJsonl(MinecraftServer server, JsonObject... objects) {
        Path path = server.getServerDirectory()
                .resolve("fragmentdoctor")
                .resolve("doctor")
                .resolve("leafstorm")
                .resolve("leafstorm-limiter.jsonl");
        StringBuilder builder = new StringBuilder();
        for (JsonObject object : objects) {
            builder.append(object).append(System.lineSeparator());
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, builder.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            FragmentDoctor.LOGGER.error("Failed to write leafstorm limiter log {}", path, exception);
        }
    }

    private static String blockId(Block block) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        return key == null ? block.getClass().getName() : key.toString();
    }

    private enum Mode {
        OFF("off"),
        SOFT("soft"),
        HARD("hard"),
        EMERGENCY("emergency");

        private final String logName;

        Mode(String logName) {
            this.logName = logName;
        }

        private boolean moreSevereThan(Mode other) {
            return ordinal() > other.ordinal();
        }
    }

    private enum QueueTrend {
        UNKNOWN("unknown"),
        SHRINKING("shrinking"),
        STABLE("stable"),
        GROWING("growing");

        private final String logName;

        QueueTrend(String logName) {
            this.logName = logName;
        }
    }

    public enum Action {
        RUN,
        DEFER,
        DEDUPLICATE
    }

    private static final class TickCounters {
        private Mode mode = Mode.OFF;
        private QueueTrend queueTrend = QueueTrend.UNKNOWN;
        private int seenLeaves;
        private int allowedLeaves;
        private int deferredLeaves;
        private int drainedDeferredLeaves;
        private int skippedDeferredDrains;
        private int deduplicatedLeaves;
        private int droppedDeferredLeaves;
        private int lastQueueDepth;
        private int drainBudget;
        private int remainingDrainBudget;
        private boolean drainPaused;
        private String drainPauseReason = "none";
        private String lastBlockId = "unknown";
        private final Map<String, Integer> leafCounts = new LinkedHashMap<>();

        private void incrementLeaf(String blockId) {
            leafCounts.merge(blockId, 1, Integer::sum);
        }

        private int mitigationEvents() {
            return deferredLeaves + drainedDeferredLeaves + skippedDeferredDrains + deduplicatedLeaves + droppedDeferredLeaves;
        }

        private String topLeaves() {
            if (leafCounts.isEmpty()) {
                return "none";
            }
            return leafCounts.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                    .limit(5)
                    .map(entry -> entry.getKey() + " x" + entry.getValue())
                    .collect(Collectors.joining(", "));
        }

        private TickCounters copy() {
            TickCounters copy = new TickCounters();
            copy.mode = mode;
            copy.queueTrend = queueTrend;
            copy.seenLeaves = seenLeaves;
            copy.allowedLeaves = allowedLeaves;
            copy.deferredLeaves = deferredLeaves;
            copy.drainedDeferredLeaves = drainedDeferredLeaves;
            copy.skippedDeferredDrains = skippedDeferredDrains;
            copy.deduplicatedLeaves = deduplicatedLeaves;
            copy.droppedDeferredLeaves = droppedDeferredLeaves;
            copy.lastQueueDepth = lastQueueDepth;
            copy.drainBudget = drainBudget;
            copy.remainingDrainBudget = remainingDrainBudget;
            copy.drainPaused = drainPaused;
            copy.drainPauseReason = drainPauseReason;
            copy.lastBlockId = lastBlockId;
            copy.leafCounts.putAll(leafCounts);
            return copy;
        }
    }

    private static final class DeferredLeafTickQueue {
        private final ArrayDeque<DeferredLeafTick> queue = new ArrayDeque<>();
        private final Map<DeferredLeafTickKey, DeferredLeafTick> byKey = new LinkedHashMap<>();
    }

    private static final class DeferredLeafTick {
        private final DeferredLeafTickKey key;
        private final Object type;
        private final BlockPos pos;
        private long triggerTick;

        private DeferredLeafTick(DeferredLeafTickKey key, Object type, BlockPos pos, long triggerTick) {
            this.key = key;
            this.type = type;
            this.pos = pos;
            this.triggerTick = triggerTick;
        }
    }

    private record DeferredLeafTickKey(String blockId, long packedPos) {
    }

    public record Decision(Action action, int delayTicks) {
        public boolean defer() {
            return action == Action.DEFER;
        }

        public boolean deduplicated() {
            return action == Action.DEDUPLICATE;
        }
    }

    public record Status(
            boolean enabled,
            String mode,
            String queueTrend,
            double lastMspt,
            int queueDepth,
            int budget,
            int remainingBudget,
            int drainBudget,
            int remainingDrainBudget,
            boolean drainPaused,
            String drainPauseReason,
            int seenLeaves,
            int allowedLeaves,
            int deferredLeaves,
            int drainedDeferredLeaves,
            int skippedDeferredDrains,
            int deduplicatedLeaves,
            int deferredQueueSize,
            String topLeaves
    ) {
    }
}
