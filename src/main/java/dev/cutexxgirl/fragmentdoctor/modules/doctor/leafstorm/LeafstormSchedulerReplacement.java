package dev.cutexxgirl.fragmentdoctor.modules.doctor.leafstorm;

import com.google.gson.JsonObject;
import dev.cutexxgirl.fragmentdoctor.FragmentDoctor;
import dev.cutexxgirl.fragmentdoctor.modules.scaner.instrumentation.CostRecorder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;

public final class LeafstormSchedulerReplacement {
    private static final Map<Object, LeafScheduler> SCHEDULERS = new IdentityHashMap<>();
    private static long serverTickIndex;
    private static long sequence;
    private static int totalJobs;
    private static int currentGlobalBudget;
    private static double lastMspt;
    private static long lastSummaryLogTick = Long.MIN_VALUE;
    private static TickCounters counters = new TickCounters();
    private static TickCounters lastCounters = new TickCounters();

    private LeafstormSchedulerReplacement() {
    }

    public static void onServerTickStart(double previousMspt) {
        serverTickIndex++;
        lastMspt = previousMspt;
        counters = new TickCounters();
        if (!configured()) {
            clear();
            currentGlobalBudget = 0;
            return;
        }
        currentGlobalBudget = globalBudgetFor(previousMspt);
        counters.globalBudget = currentGlobalBudget;
        counters.perChunkBudget = LeafstormConfig.VALUES.schedulerPerChunkBudget.get();
        counters.shadowMode = shadowMode();
    }

    public static void onServerTickEnd(MinecraftServer server) {
        counters.pendingJobs = totalJobs;
        counters.heapEntries = heapEntries();
        lastCounters = counters.copy();
        writeSummaryIfNeeded(server);
    }

    public static ScheduleDecision beforeSchedule(Object tickAccess, ScheduledTick<?> tick) {
        if (tick == null || !configured() || !LeafstormConfig.VALUES.schedulerInterceptVanillaLeafSchedules.get()) {
            return ScheduleDecision.PASS_THROUGH;
        }
        Object type = tick.type();
        if (!(type instanceof Block block) || !(block instanceof LeavesBlock)) {
            return ScheduleDecision.PASS_THROUGH;
        }

        String blockId = blockId(block);
        BlockPos pos = tick.pos();
        counters.intercepted++;
        counters.lastBlockId = blockId;
        counters.incrementLeaf(blockId);
        counters.incrementChunk(chunkKey(pos));
        CostRecorder.recordScheduledTick(type, pos, CostRecorder.ORIGIN_VANILLA_LEAF_INTERCEPTED);

        if (shadowMode()) {
            counters.shadowIntercepted++;
            return ScheduleDecision.RECORDED_PASS_THROUGH;
        }

        LeafScheduler scheduler = SCHEDULERS.computeIfAbsent(tickAccess, ignored -> new LeafScheduler());
        LeafKey key = new LeafKey(blockId, pos.asLong());
        LeafJob existing = scheduler.jobsByKey.get(key);
        if (existing == null) {
            LeafJob job = new LeafJob(key, type, pos.immutable(), tick.triggerTick(), tick.priority(), tick.subTickOrder(), serverTickIndex);
            scheduler.jobsByKey.put(key, job);
            scheduler.dueHeap.add(new HeapEntry(key, job.dueTick, job.priority, job.subTickOrder, job.version, sequence++));
            totalJobs++;
            counters.enqueued++;
            CostRecorder.recordScheduledTick(type, pos, CostRecorder.ORIGIN_SCHEDULER_ENQUEUED);
            enforceMaxJobs();
            return ScheduleDecision.CANCEL_VANILLA;
        }

        existing.touches++;
        existing.lastTouchedServerTick = serverTickIndex;
        if (isEarlier(tick, existing)) {
            existing.dueTick = tick.triggerTick();
            existing.priority = tick.priority();
            existing.subTickOrder = tick.subTickOrder();
            existing.version++;
            scheduler.dueHeap.add(new HeapEntry(key, existing.dueTick, existing.priority, existing.subTickOrder, existing.version, sequence++));
        }
        counters.coalesced++;
        CostRecorder.recordScheduledTick(type, pos, CostRecorder.ORIGIN_SCHEDULER_COALESCED);
        return ScheduleDecision.CANCEL_VANILLA;
    }

    @SuppressWarnings("unchecked")
    public static <T> void drain(Object tickAccess, BiConsumer<BlockPos, T> ticker, long gameTime) {
        if (!active()) {
            return;
        }
        LeafScheduler scheduler = SCHEDULERS.get(tickAccess);
        if (scheduler == null || scheduler.jobsByKey.isEmpty()) {
            return;
        }

        scheduler.chunkBudgets.clear();
        int remainingGlobal = currentGlobalBudget;
        int perChunkBudget = LeafstormConfig.VALUES.schedulerPerChunkBudget.get();
        while (remainingGlobal > 0) {
            LeafJob job = pollReadyJob(scheduler, gameTime);
            if (job == null) {
                break;
            }
            if (isExpired(job, gameTime)) {
                dropJob(scheduler, job, CostRecorder.ORIGIN_SCHEDULER_DROPPED_STALE);
                counters.droppedStale++;
                continue;
            }
            long chunkKey = chunkKey(job.pos);
            int usedInChunk = scheduler.chunkBudgets.getOrDefault(chunkKey, 0);
            if (usedInChunk >= perChunkBudget) {
                remainingGlobal--;
                counters.skippedBudget++;
                rescheduleForNextTick(scheduler, job, gameTime);
                CostRecorder.recordScheduledTick(job.type, job.pos, CostRecorder.ORIGIN_SCHEDULER_SKIPPED_BUDGET);
                continue;
            }

            scheduler.jobsByKey.remove(job.key);
            totalJobs--;
            scheduler.chunkBudgets.put(chunkKey, usedInChunk + 1);
            remainingGlobal--;
            counters.executed++;
            counters.lastBlockId = job.key.blockId();
            counters.incrementLeaf(job.key.blockId());
            counters.incrementChunk(chunkKey);
            CostRecorder.recordScheduledTick(job.type, job.pos, CostRecorder.ORIGIN_SCHEDULER_EXECUTED);
            ticker.accept(job.pos, (T)job.type);
        }

        if (remainingGlobal <= 0 && hasReadyJob(scheduler, gameTime)) {
            counters.skippedBudget += Math.max(1, readyJobCount(scheduler, gameTime, 16));
        }
        compactHeapIfNeeded(scheduler);
        if (scheduler.jobsByKey.isEmpty()) {
            SCHEDULERS.remove(tickAccess);
        }
    }

    public static Status status() {
        TickCounters snapshot = lastCounters;
        return new Status(
                configured(),
                active(),
                snapshot.shadowMode,
                totalJobs,
                snapshot.heapEntries,
                snapshot.globalBudget,
                snapshot.perChunkBudget,
                snapshot.intercepted,
                snapshot.shadowIntercepted,
                snapshot.enqueued,
                snapshot.coalesced,
                snapshot.executed,
                snapshot.skippedBudget,
                snapshot.droppedStale,
                snapshot.topLeaves(),
                snapshot.topChunks()
        );
    }

    private static boolean configured() {
        return LeafstormConfig.VALUES.schedulerEnabled.get();
    }

    private static boolean shadowMode() {
        return LeafstormConfig.VALUES.schedulerShadowMode.get();
    }

    private static boolean active() {
        return configured() && !shadowMode();
    }

    private static int globalBudgetFor(double mspt) {
        int configuredBudget = LeafstormConfig.VALUES.schedulerGlobalBudget.get();
        if (configuredBudget <= 0) {
            return 0;
        }
        if (mspt < LeafstormConfig.VALUES.schedulerHealthyMsptThreshold.get()) {
            return Math.min(65_536, Math.max(configuredBudget, configuredBudget * 2));
        }
        if (mspt < LeafstormConfig.VALUES.schedulerSoftMsptThreshold.get()) {
            return configuredBudget;
        }
        if (mspt < LeafstormConfig.VALUES.schedulerHardMsptThreshold.get()) {
            return Math.max(1, configuredBudget / 4);
        }
        return Math.min(4, Math.max(0, configuredBudget / 16));
    }

    private static LeafJob pollReadyJob(LeafScheduler scheduler, long gameTime) {
        while (!scheduler.dueHeap.isEmpty()) {
            HeapEntry entry = scheduler.dueHeap.peek();
            LeafJob job = scheduler.jobsByKey.get(entry.key());
            if (job == null || job.version != entry.version() || job.dueTick != entry.dueTick()) {
                scheduler.dueHeap.poll();
                continue;
            }
            if (entry.dueTick() > gameTime) {
                return null;
            }
            scheduler.dueHeap.poll();
            return job;
        }
        return null;
    }

    private static boolean hasReadyJob(LeafScheduler scheduler, long gameTime) {
        return readyJobCount(scheduler, gameTime, 1) > 0;
    }

    private static int readyJobCount(LeafScheduler scheduler, long gameTime, int limit) {
        int ready = 0;
        for (HeapEntry entry : scheduler.dueHeap) {
            LeafJob job = scheduler.jobsByKey.get(entry.key());
            if (job != null && job.version == entry.version() && job.dueTick == entry.dueTick() && entry.dueTick() <= gameTime) {
                ready++;
                if (ready >= limit) {
                    break;
                }
            }
        }
        return ready;
    }

    private static void rescheduleForNextTick(LeafScheduler scheduler, LeafJob job, long gameTime) {
        job.dueTick = gameTime + 1;
        job.version++;
        scheduler.dueHeap.add(new HeapEntry(job.key, job.dueTick, job.priority, job.subTickOrder, job.version, sequence++));
    }

    private static boolean isExpired(LeafJob job, long gameTime) {
        return gameTime - job.firstDueTick > LeafstormConfig.VALUES.schedulerJobTtlTicks.get();
    }

    private static void dropJob(LeafScheduler scheduler, LeafJob job, String origin) {
        if (scheduler.jobsByKey.remove(job.key) != null) {
            totalJobs--;
            CostRecorder.recordScheduledTick(job.type, job.pos, origin);
        }
    }

    private static void compactHeapIfNeeded(LeafScheduler scheduler) {
        if (scheduler.dueHeap.size() <= Math.max(64, scheduler.jobsByKey.size() * 3)) {
            return;
        }
        PriorityQueue<HeapEntry> compacted = new PriorityQueue<>(HeapEntry.ORDERING);
        for (LeafJob job : scheduler.jobsByKey.values()) {
            compacted.add(new HeapEntry(job.key, job.dueTick, job.priority, job.subTickOrder, job.version, sequence++));
        }
        scheduler.dueHeap = compacted;
    }

    private static void enforceMaxJobs() {
        int maxJobs = LeafstormConfig.VALUES.schedulerMaxJobs.get();
        if (totalJobs <= maxJobs) {
            return;
        }
        Iterator<Map.Entry<Object, LeafScheduler>> outer = SCHEDULERS.entrySet().iterator();
        while (totalJobs > maxJobs && outer.hasNext()) {
            LeafScheduler scheduler = outer.next().getValue();
            Iterator<LeafJob> jobs = scheduler.jobsByKey.values().iterator();
            if (jobs.hasNext()) {
                LeafJob job = jobs.next();
                jobs.remove();
                totalJobs--;
                counters.droppedStale++;
                CostRecorder.recordScheduledTick(job.type, job.pos, CostRecorder.ORIGIN_SCHEDULER_DROPPED_STALE);
            }
            if (scheduler.jobsByKey.isEmpty()) {
                outer.remove();
            }
        }
    }

    private static void clear() {
        SCHEDULERS.clear();
        totalJobs = 0;
    }

    private static int heapEntries() {
        int entries = 0;
        for (LeafScheduler scheduler : SCHEDULERS.values()) {
            entries += scheduler.dueHeap.size();
        }
        return entries;
    }

    private static void writeSummaryIfNeeded(MinecraftServer server) {
        if (!configured() || counters.events() <= 0) {
            return;
        }
        int logEveryTicks = LeafstormConfig.VALUES.schedulerLogEveryTicks.get();
        if (serverTickIndex - lastSummaryLogTick < logEveryTicks) {
            return;
        }
        lastSummaryLogTick = serverTickIndex;
        if (LeafstormConfig.VALUES.consoleLogging.get()) {
            FragmentDoctor.LOGGER.warn(
                    "Leafstorm scheduler: active={} shadow={} intercepted={} enqueued={} coalesced={} executed={} skipped_budget={} dropped_stale={} pending={} budget={} per_chunk={} mspt={} top_leaves={} top_chunks={}",
                    active(),
                    shadowMode(),
                    counters.intercepted,
                    counters.enqueued,
                    counters.coalesced,
                    counters.executed,
                    counters.skippedBudget,
                    counters.droppedStale,
                    totalJobs,
                    currentGlobalBudget,
                    LeafstormConfig.VALUES.schedulerPerChunkBudget.get(),
                    String.format(Locale.ROOT, "%.2f", lastMspt),
                    counters.topLeaves(),
                    counters.topChunks()
            );
        }
        if (LeafstormConfig.VALUES.fileLogging.get()) {
            writeJsonlSummary(server);
        }
    }

    private static void writeJsonlSummary(MinecraftServer server) {
        JsonObject summary = new JsonObject();
        summary.addProperty("schema", "fragmentdoctor.leafstorm_scheduler.v1");
        summary.addProperty("event", "leafstorm_scheduler_tick");
        summary.addProperty("server_tick", server.getTickCount());
        summary.addProperty("active", active());
        summary.addProperty("shadow_mode", shadowMode());
        summary.addProperty("last_mspt", lastMspt);
        summary.addProperty("global_budget", currentGlobalBudget);
        summary.addProperty("per_chunk_budget", LeafstormConfig.VALUES.schedulerPerChunkBudget.get());
        summary.addProperty("intercepted", counters.intercepted);
        summary.addProperty("shadow_intercepted", counters.shadowIntercepted);
        summary.addProperty("enqueued", counters.enqueued);
        summary.addProperty("coalesced", counters.coalesced);
        summary.addProperty("executed", counters.executed);
        summary.addProperty("skipped_budget", counters.skippedBudget);
        summary.addProperty("dropped_stale", counters.droppedStale);
        summary.addProperty("pending_jobs", totalJobs);
        summary.addProperty("heap_entries", heapEntries());
        summary.addProperty("top_leaves", counters.topLeaves());
        summary.addProperty("top_chunks", counters.topChunks());
        appendJsonl(server, summary);
    }

    private static void appendJsonl(MinecraftServer server, JsonObject object) {
        Path path = server.getServerDirectory()
                .resolve("fragmentdoctor")
                .resolve("doctor")
                .resolve("leafstorm")
                .resolve("leafstorm-scheduler.jsonl");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, object + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            FragmentDoctor.LOGGER.error("Failed to write leafstorm scheduler log {}", path, exception);
        }
    }

    private static String blockId(Block block) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        return key == null ? block.getClass().getName() : key.toString();
    }

    private static boolean isEarlier(ScheduledTick<?> tick, LeafJob existing) {
        int dueComparison = Long.compare(tick.triggerTick(), existing.dueTick);
        if (dueComparison != 0) {
            return dueComparison < 0;
        }
        int priorityComparison = tick.priority().compareTo(existing.priority);
        if (priorityComparison != 0) {
            return priorityComparison < 0;
        }
        return tick.subTickOrder() < existing.subTickOrder;
    }

    private static long chunkKey(BlockPos pos) {
        long chunkX = pos.getX() >> 4;
        long chunkZ = pos.getZ() >> 4;
        return (chunkX & 0xffffffffL) << 32 | (chunkZ & 0xffffffffL);
    }

    private static String chunkLabel(long chunkKey) {
        int chunkX = (int)(chunkKey >> 32);
        int chunkZ = (int)chunkKey;
        return chunkX + "," + chunkZ;
    }

    public enum ScheduleDecision {
        PASS_THROUGH(false, false),
        RECORDED_PASS_THROUGH(true, false),
        CANCEL_VANILLA(true, true);

        private final boolean scannerRecorded;
        private final boolean cancelVanilla;

        ScheduleDecision(boolean scannerRecorded, boolean cancelVanilla) {
            this.scannerRecorded = scannerRecorded;
            this.cancelVanilla = cancelVanilla;
        }

        public boolean scannerRecorded() {
            return scannerRecorded;
        }

        public boolean cancelVanilla() {
            return cancelVanilla;
        }
    }

    private static final class LeafScheduler {
        private final Map<LeafKey, LeafJob> jobsByKey = new LinkedHashMap<>();
        private PriorityQueue<HeapEntry> dueHeap = new PriorityQueue<>(HeapEntry.ORDERING);
        private final Map<Long, Integer> chunkBudgets = new HashMap<>();
    }

    private static final class LeafJob {
        private final LeafKey key;
        private final Object type;
        private final BlockPos pos;
        private final long firstDueTick;
        private long dueTick;
        private TickPriority priority;
        private long subTickOrder;
        private long version;
        private int touches = 1;
        private long lastTouchedServerTick;

        private LeafJob(LeafKey key, Object type, BlockPos pos, long dueTick, TickPriority priority, long subTickOrder, long serverTickIndex) {
            this.key = key;
            this.type = type;
            this.pos = pos;
            this.firstDueTick = dueTick;
            this.dueTick = dueTick;
            this.priority = priority;
            this.subTickOrder = subTickOrder;
            this.lastTouchedServerTick = serverTickIndex;
        }
    }

    private record LeafKey(String blockId, long packedPos) {
    }

    private record HeapEntry(LeafKey key, long dueTick, TickPriority priority, long subTickOrder, long version, long sequence) {
        private static final Comparator<HeapEntry> ORDERING = Comparator
                .comparingLong(HeapEntry::dueTick)
                .thenComparing(HeapEntry::priority)
                .thenComparingLong(HeapEntry::subTickOrder)
                .thenComparingLong(HeapEntry::sequence);
    }

    private static final class TickCounters {
        private boolean shadowMode;
        private int globalBudget;
        private int perChunkBudget;
        private int intercepted;
        private int shadowIntercepted;
        private int enqueued;
        private int coalesced;
        private int executed;
        private int skippedBudget;
        private int droppedStale;
        private int pendingJobs;
        private int heapEntries;
        private String lastBlockId = "unknown";
        private final Map<String, Integer> leafCounts = new LinkedHashMap<>();
        private final Map<Long, Integer> chunkCounts = new LinkedHashMap<>();

        private void incrementLeaf(String blockId) {
            leafCounts.merge(blockId, 1, Integer::sum);
        }

        private void incrementChunk(long chunkKey) {
            chunkCounts.merge(chunkKey, 1, Integer::sum);
        }

        private int events() {
            return intercepted + enqueued + coalesced + executed + skippedBudget + droppedStale;
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

        private String topChunks() {
            if (chunkCounts.isEmpty()) {
                return "none";
            }
            return chunkCounts.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                    .limit(5)
                    .map(entry -> chunkLabel(entry.getKey()) + " x" + entry.getValue())
                    .collect(Collectors.joining(", "));
        }

        private TickCounters copy() {
            TickCounters copy = new TickCounters();
            copy.shadowMode = shadowMode;
            copy.globalBudget = globalBudget;
            copy.perChunkBudget = perChunkBudget;
            copy.intercepted = intercepted;
            copy.shadowIntercepted = shadowIntercepted;
            copy.enqueued = enqueued;
            copy.coalesced = coalesced;
            copy.executed = executed;
            copy.skippedBudget = skippedBudget;
            copy.droppedStale = droppedStale;
            copy.pendingJobs = pendingJobs;
            copy.heapEntries = heapEntries;
            copy.lastBlockId = lastBlockId;
            copy.leafCounts.putAll(leafCounts);
            copy.chunkCounts.putAll(chunkCounts);
            return copy;
        }
    }

    public record Status(
            boolean configured,
            boolean active,
            boolean shadowMode,
            int pendingJobs,
            int heapEntries,
            int globalBudget,
            int perChunkBudget,
            int intercepted,
            int shadowIntercepted,
            int enqueued,
            int coalesced,
            int executed,
            int skippedBudget,
            int droppedStale,
            String topLeaves,
            String topChunks
    ) {
    }
}
