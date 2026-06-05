package dev.cutexxgirl.fragmentdoctor.modules.doctor.leafstorm;

import com.google.gson.JsonObject;
import dev.cutexxgirl.fragmentdoctor.FragmentDoctor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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
    private static long nextDeferredSubTickOrder;
    private static int deferredDedupKeyCount;
    private static final Map<Object, LinkedHashMap<DeferredLeafTickKey, Long>> DEFERRED_LEAF_TICKS = new IdentityHashMap<>();
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
        observedQueueDepthThisTick = Math.max(observedQueueDepthThisTick, queueDepth);
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

        removeExpiredDedupKey(tickAccess, blockId, pos, gameTime);
        if (currentMode == Mode.OFF) {
            counters.allowedLeaves++;
            return RUN;
        }
        if (remainingBudget > 0) {
            remainingBudget--;
            counters.allowedLeaves++;
            return RUN;
        }
        if (isDuplicateDeferredTick(tickAccess, blockId, pos, gameTime)) {
            counters.deduplicatedLeaves++;
            return new Decision(Action.DEDUPLICATE, 0);
        }
        counters.deferredLeaves++;
        int delayTicks = LeafstormConfig.VALUES.deferDelayTicks.get();
        rememberDeferredTick(tickAccess, blockId, pos, gameTime + delayTicks);
        return new Decision(Action.DEFER, delayTicks);
    }

    public static long nextDeferredSubTickOrder() {
        return nextDeferredSubTickOrder++;
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
                snapshot.seenLeaves,
                snapshot.allowedLeaves,
                snapshot.deferredLeaves,
                snapshot.deduplicatedLeaves,
                deferredDedupKeyCount,
                snapshot.topLeaves()
        );
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

    private static void removeExpiredDedupKey(Object tickAccess, String blockId, BlockPos pos, long gameTime) {
        LinkedHashMap<DeferredLeafTickKey, Long> ticks = DEFERRED_LEAF_TICKS.get(tickAccess);
        if (ticks == null) {
            return;
        }
        DeferredLeafTickKey key = new DeferredLeafTickKey(blockId, pos.asLong());
        Long triggerTick = ticks.get(key);
        if (triggerTick != null && triggerTick <= gameTime) {
            ticks.remove(key);
            deferredDedupKeyCount--;
            if (ticks.isEmpty()) {
                DEFERRED_LEAF_TICKS.remove(tickAccess);
            }
        }
    }

    private static boolean isDuplicateDeferredTick(Object tickAccess, String blockId, BlockPos pos, long gameTime) {
        if (!LeafstormConfig.VALUES.enableDeduplication.get()) {
            return false;
        }
        LinkedHashMap<DeferredLeafTickKey, Long> ticks = DEFERRED_LEAF_TICKS.get(tickAccess);
        if (ticks == null) {
            return false;
        }
        Long triggerTick = ticks.get(new DeferredLeafTickKey(blockId, pos.asLong()));
        return triggerTick != null && triggerTick + LeafstormConfig.VALUES.dedupGraceTicks.get() > gameTime;
    }

    private static void rememberDeferredTick(Object tickAccess, String blockId, BlockPos pos, long triggerTick) {
        if (!LeafstormConfig.VALUES.enableDeduplication.get()) {
            return;
        }
        LinkedHashMap<DeferredLeafTickKey, Long> ticks = DEFERRED_LEAF_TICKS.computeIfAbsent(tickAccess, ignored -> new LinkedHashMap<>());
        DeferredLeafTickKey key = new DeferredLeafTickKey(blockId, pos.asLong());
        if (!ticks.containsKey(key)) {
            deferredDedupKeyCount++;
        }
        ticks.put(key, triggerTick);
        enforceMaxDedupKeys();
    }

    private static void enforceMaxDedupKeys() {
        int maxKeys = LeafstormConfig.VALUES.maxDeferredDedupKeys.get();
        if (deferredDedupKeyCount <= maxKeys) {
            return;
        }
        Iterator<Map.Entry<Object, LinkedHashMap<DeferredLeafTickKey, Long>>> outer = DEFERRED_LEAF_TICKS.entrySet().iterator();
        while (deferredDedupKeyCount > maxKeys && outer.hasNext()) {
            Map.Entry<Object, LinkedHashMap<DeferredLeafTickKey, Long>> entry = outer.next();
            Iterator<DeferredLeafTickKey> inner = entry.getValue().keySet().iterator();
            while (deferredDedupKeyCount > maxKeys && inner.hasNext()) {
                inner.next();
                inner.remove();
                deferredDedupKeyCount--;
            }
            if (entry.getValue().isEmpty()) {
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
                    "Leafstorm limiter {}: deferred={} deduped={} allowed={} budget={} mspt={} queue={} trend={} top_leaves={}",
                    counters.mode.logName,
                    counters.deferredLeaves,
                    counters.deduplicatedLeaves,
                    counters.allowedLeaves,
                    currentBudget,
                    String.format(Locale.ROOT, "%.2f", lastMspt),
                    counters.lastQueueDepth,
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

        JsonObject queue = baseJsonEvent(server, "leafstorm_queue_snapshot");
        queue.addProperty("last_mspt", lastMspt);
        queue.addProperty("queue_depth", counters.lastQueueDepth);
        queue.addProperty("previous_queue_depth", previousTickQueueDepth);
        queue.addProperty("queue_trend", counters.queueTrend.logName);
        queue.addProperty("dedup_key_count", deferredDedupKeyCount);

        appendJsonl(server, summary, budget, queue);

        if (counters.deferredLeaves > 0) {
            JsonObject deferred = baseJsonEvent(server, "leafstorm_deferred");
            addCommonSummaryFields(deferred);
            deferred.addProperty("deferred_leaf_ticks", counters.deferredLeaves);
            deferred.addProperty("origin", "doctor_reschedule");
            appendJsonl(server, deferred);
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
        object.addProperty("schema", "fragmentdoctor.leafstorm_doctor.v1");
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
        object.addProperty("allowed_leaf_ticks", counters.allowedLeaves);
        object.addProperty("deferred_leaf_ticks", counters.deferredLeaves);
        object.addProperty("deduplicated_leaf_ticks", counters.deduplicatedLeaves);
        object.addProperty("dedup_key_count", deferredDedupKeyCount);
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
        private int deduplicatedLeaves;
        private int lastQueueDepth;
        private String lastBlockId = "unknown";
        private final Map<String, Integer> leafCounts = new LinkedHashMap<>();

        private void incrementLeaf(String blockId) {
            leafCounts.merge(blockId, 1, Integer::sum);
        }

        private int mitigationEvents() {
            return deferredLeaves + deduplicatedLeaves;
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
            copy.deduplicatedLeaves = deduplicatedLeaves;
            copy.lastQueueDepth = lastQueueDepth;
            copy.lastBlockId = lastBlockId;
            copy.leafCounts.putAll(leafCounts);
            return copy;
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
            int seenLeaves,
            int allowedLeaves,
            int deferredLeaves,
            int deduplicatedLeaves,
            int dedupKeyCount,
            String topLeaves
    ) {
    }
}
