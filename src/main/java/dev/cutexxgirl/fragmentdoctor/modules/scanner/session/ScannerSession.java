package dev.cutexxgirl.fragmentdoctor.modules.scanner.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.cutexxgirl.fragmentdoctor.FragmentDoctor;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.ObservedTarget;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.SectionRecord;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.log.JsonlLogWriter;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.metrics.TickMetrics;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.report.ClientFrameTracker;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.report.EntityQueryTracker;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.report.GlErrorTracker;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.report.InputPollTracker;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.report.ScanSummaryWriter;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.report.ScheduledTickTracker;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.report.SuspectTracker;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.report.ThreadSampleTracker;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.attribution.CallerAttribution;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

public final class ScannerSession {
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final long TICK_SPIKE_NANOS = 50_000_000L;
    private static final long MICRO_FREEZE_NANOS = 100_000_000L;
    private static final long SECTION_THRESHOLD_NANOS = 2_000_000L;
    private static final long ENTITY_THRESHOLD_NANOS = 1_000_000L;
    private static final long BLOCK_ENTITY_THRESHOLD_NANOS = 1_000_000L;
    private static final long LEVEL_TICK_THRESHOLD_NANOS = 50_000_000L;
    private static final long CHUNK_TICK_THRESHOLD_NANOS = 5_000_000L;
    private static final long SAMPLER_INTERVAL_MILLIS = 250L;
    private static final long SPIKE_THREAD_DUMP_INTERVAL_NANOS = 10_000_000_000L;

    private final String sessionId;
    private final Instant startedAt;
    private final long startedNanos;
    private final int durationSeconds;
    private final int durationTicks;
    private final Path logPath;
    private final Path summaryPath;
    private final JsonlLogWriter writer;
    private final TickMetrics metrics = new TickMetrics();
    private final SuspectTracker suspects = new SuspectTracker();
    private final ScheduledTickTracker scheduledTicks = new ScheduledTickTracker();
    private final GlErrorTracker glErrors = new GlErrorTracker();
    private final InputPollTracker inputPolls = new InputPollTracker();
    private final ClientFrameTracker clientFrames = new ClientFrameTracker();
    private final EntityQueryTracker entityQueries = new EntityQueryTracker();
    private final ThreadSampleTracker threadSamples = new ThreadSampleTracker();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private long tickStartNanos;
    private int elapsedTicks;
    private GcTotals lastGcTotals = GcTotals.now();
    private long lastSpikeThreadDumpNanos;

    private ScannerSession(String sessionId, Instant startedAt, long startedNanos, int durationSeconds, int durationTicks, Path logPath, Path summaryPath, JsonlLogWriter writer) {
        this.sessionId = sessionId;
        this.startedAt = startedAt;
        this.startedNanos = startedNanos;
        this.durationSeconds = durationSeconds;
        this.durationTicks = durationTicks;
        this.logPath = logPath;
        this.summaryPath = summaryPath;
        this.writer = writer;
        writeSessionStart();
        startSamplerThread();
    }

    public static ScannerSession start(Path directory, int seconds) throws IOException {
        long startedNanos = System.nanoTime();
        Instant now = Instant.now();
        String sessionId = "scan-" + FILE_STAMP.format(now);
        Path logPath = directory.resolve(sessionId + ".jsonl");
        Path summaryPath = directory.resolve(sessionId + "-summary.md");
        return new ScannerSession(sessionId, now, startedNanos, seconds, seconds * 20, logPath, summaryPath, new JsonlLogWriter(logPath));
    }

    public String sessionId() {
        return sessionId;
    }

    public Path logPath() {
        return logPath;
    }

    public boolean isActive() {
        return active.get();
    }

    public int elapsedTicks() {
        return elapsedTicks;
    }

    public int durationTicks() {
        return durationTicks;
    }

    public int durationSeconds() {
        return durationSeconds;
    }

    public double elapsedWallSeconds() {
        return elapsedWallSeconds(System.nanoTime());
    }

    public void onServerTickStart(MinecraftServer server) {
        tickStartNanos = System.nanoTime();
    }

    public Path onServerTickEnd(MinecraftServer server) {
        long now = System.nanoTime();
        long duration = now - tickStartNanos;
        elapsedTicks++;
        metrics.addTick(duration);
        if (duration >= TICK_SPIKE_NANOS) {
            writeTickSpike(server, duration);
            if (now - lastSpikeThreadDumpNanos >= SPIKE_THREAD_DUMP_INTERVAL_NANOS) {
                lastSpikeThreadDumpNanos = now;
                writeThreadSample();
            }
        }
        if (elapsedWallSeconds(now) >= durationSeconds || elapsedTicks >= durationTicks) {
            return finish(server, "duration_elapsed");
        }
        return null;
    }

    public void recordSection(SectionRecord record) {
        if (!isActive() || !shouldLog(record)) {
            return;
        }
        suspects.add(record);
        if ("entity_query_cost".equals(record.eventType())) {
            entityQueries.add(record);
        }
        JsonObject object = baseEvent(record.eventType());
        object.addProperty("section", record.section());
        object.addProperty("duration_nanos", record.durationNanos());
        object.addProperty("duration_ms", record.durationMillis());
        addTarget(object, record.target());
        JsonObject caller = new JsonObject();
        caller.addProperty("suspect_mod_id", record.caller().suspectModId());
        caller.addProperty("class", record.caller().className());
        caller.addProperty("method", record.caller().methodName());
        JsonArray stack = new JsonArray();
        record.caller().stack().forEach(stack::add);
        caller.add("stack", stack);
        object.add("caller", caller);
        writer.write(object);
    }

    public void recordScheduledTick(ObservedTarget target, CallerAttribution caller, String origin) {
        if (!isActive()) {
            return;
        }
        scheduledTicks.add(target, caller, origin);
    }

    public void recordGlError(int error, String description, String section, CallerAttribution caller) {
        if (!isActive()) {
            return;
        }
        glErrors.add(error, description, section, caller);
    }

    public void recordInvalidKeyPoll(int keyCode, CallerAttribution caller) {
        if (!isActive()) {
            return;
        }
        inputPolls.add(keyCode, caller);
    }

    public void recordClientFrame(String section, long durationNanos, int reportedFps, String screenName, boolean inWorld, java.util.List<String> stack) {
        if (!isActive()) {
            return;
        }
        boolean spike = clientFrames.addFrame(section, durationNanos, stack);
        if (!spike) {
            return;
        }
        JsonObject object = baseEvent("client_frame_spike");
        object.addProperty("section", section);
        object.addProperty("duration_nanos", durationNanos);
        object.addProperty("duration_ms", durationNanos / 1_000_000.0D);
        object.addProperty("reported_fps", reportedFps);
        object.addProperty("screen", screenName);
        object.addProperty("in_world", inWorld);
        object.addProperty("micro_freeze", durationNanos >= 100_000_000L);
        JsonArray stackJson = new JsonArray();
        stack.forEach(stackJson::add);
        object.add("stack", stackJson);
        writer.write(object);
    }

    public Path finish(MinecraftServer server, String reason) {
        if (!active.compareAndSet(true, false)) {
            return summaryPath;
        }
        long endedNanos = System.nanoTime();
        Instant endedAt = Instant.now();
        writeScheduledTickSummaryEvent();
        writeGlErrorSummaryEvent();
        writeInvalidKeyPollSummaryEvent();
        writeEntityQuerySummaryEvent();
        writeThreadSampleSummaryEvent();
        JsonObject object = baseEvent("session_end");
        object.addProperty("reason", reason);
        object.addProperty("requested_seconds", durationSeconds);
        object.addProperty("expected_ticks", durationTicks);
        object.addProperty("elapsed_wall_seconds", elapsedWallSeconds(endedNanos));
        object.addProperty("actual_tps", actualTps(endedNanos));
        object.addProperty("ticks", metrics.tickCount());
        object.addProperty("avg_mspt", metrics.averageMspt());
        object.addProperty("max_mspt", metrics.maxMspt());
        object.addProperty("p95_mspt", metrics.p95Mspt());
        object.addProperty("p99_mspt", metrics.p99Mspt());
        object.addProperty("rolling_tps", metrics.rollingTps());
        object.addProperty("jsonl_dropped_events", writer.droppedEvents());
        writer.write(object);
        try {
            writer.close();
            ScanSummaryWriter.write(summaryPath, sessionId, startedAt, endedAt, durationSeconds, metrics, suspects, scheduledTicks, glErrors, inputPolls, clientFrames, entityQueries, threadSamples, logPath);
        } catch (IOException exception) {
            FragmentDoctor.LOGGER.error("Failed to close Scanner session {}", sessionId, exception);
        }
        return summaryPath;
    }

    private boolean shouldLog(SectionRecord record) {
        if ("level_tick".equals(record.section())) {
            return record.durationNanos() >= LEVEL_TICK_THRESHOLD_NANOS;
        }
        if ("chunk_tick".equals(record.section())) {
            return record.durationNanos() >= CHUNK_TICK_THRESHOLD_NANOS;
        }
        return switch (record.eventType()) {
            case "entity_cost" -> record.durationNanos() >= ENTITY_THRESHOLD_NANOS;
            case "block_entity_cost" -> record.durationNanos() >= BLOCK_ENTITY_THRESHOLD_NANOS;
            case "ai_goal_cost", "brain_behavior_cost", "brain_sensor_cost", "navigation_cost" -> record.durationNanos() >= 250_000L;
            case "entity_query_cost" -> record.durationNanos() >= 250_000L;
            case "event_listener_cost" -> record.durationNanos() >= 500_000L;
            case "render_cost" -> record.durationNanos() >= 2_000_000L;
            default -> record.durationNanos() >= SECTION_THRESHOLD_NANOS;
        };
    }

    private void writeSessionStart() {
        JsonObject object = baseEvent("session_start");
        object.addProperty("duration_ticks", durationTicks);
        object.addProperty("duration_seconds", durationSeconds);
        object.addProperty("tick_spike_threshold_ms", TICK_SPIKE_NANOS / 1_000_000L);
        object.addProperty("micro_freeze_threshold_ms", MICRO_FREEZE_NANOS / 1_000_000L);
        writer.write(object);
    }

    private void writeTickSpike(MinecraftServer server, long duration) {
        JsonObject object = baseEvent("tick_spike");
        object.addProperty("duration_nanos", duration);
        object.addProperty("duration_ms", duration / 1_000_000.0D);
        object.addProperty("micro_freeze", duration >= MICRO_FREEZE_NANOS);
        object.addProperty("rolling_tps", metrics.rollingTps());
        object.addProperty("avg_mspt", metrics.averageMspt());
        object.add("memory", memorySnapshot());
        object.add("gc_delta", gcDelta());
        JsonArray levels = new JsonArray();
        for (ServerLevel level : server.getAllLevels()) {
            JsonObject levelJson = new JsonObject();
            levelJson.addProperty("dimension", level.dimension().location().toString());
            levelJson.addProperty("players", level.players().size());
            levelJson.addProperty("watchdog_stats", level.getWatchdogStats());
            levelJson.addProperty("block_ticks", level.getBlockTicks().count());
            levelJson.addProperty("fluid_ticks", level.getFluidTicks().count());
            int entityCount = 0;
            for (Entity ignored : level.getAllEntities()) {
                entityCount++;
            }
            levelJson.addProperty("entity_count", entityCount);
            levelJson.addProperty("chunk_source_stats", level.gatherChunkSourceStats());
            levels.add(levelJson);
        }
        object.add("levels", levels);
        writer.write(object);
    }

    private void writeScheduledTickSummaryEvent() {
        JsonObject object = baseEvent("scheduled_tick_summary");
        JsonArray entries = new JsonArray();
        scheduledTicks.ranked().stream().limit(50).forEach(evidence -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("key", evidence.key());
            entry.addProperty("origin", evidence.origin());
            entry.addProperty("suspected_mod", evidence.suspectedModId());
            entry.addProperty("count", evidence.count());
            entry.add("targets", countedMap(evidence.targets()));
            entry.add("chunks", countedMap(evidence.chunks()));
            entry.add("frames", countedMap(evidence.frames()));
            entries.add(entry);
        });
        object.add("entries", entries);
        writer.write(object);
    }

    private void writeGlErrorSummaryEvent() {
        JsonObject object = baseEvent("gl_error_summary");
        JsonArray entries = new JsonArray();
        glErrors.ranked().stream().limit(50).forEach(evidence -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("error", evidence.error());
            entry.addProperty("description", evidence.description());
            entry.addProperty("section", evidence.section());
            entry.addProperty("caller", evidence.callerKey());
            entry.addProperty("suspected_mod", evidence.suspectedModId());
            entry.addProperty("count", evidence.count());
            entry.add("frames", countedMap(evidence.frames()));
            entries.add(entry);
        });
        object.add("entries", entries);
        writer.write(object);
    }

    private void writeInvalidKeyPollSummaryEvent() {
        JsonObject object = baseEvent("invalid_key_poll_summary");
        JsonArray entries = new JsonArray();
        inputPolls.ranked().stream().limit(50).forEach(evidence -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("key", evidence.key());
            entry.addProperty("suspected_mod", evidence.suspectedModId());
            entry.addProperty("key_code", evidence.keyCode());
            entry.addProperty("count", evidence.count());
            entry.add("caller_methods", countedMap(evidence.callerMethods()));
            entry.add("frames", countedMap(evidence.frames()));
            entries.add(entry);
        });
        object.add("entries", entries);
        writer.write(object);
    }

    private void writeEntityQuerySummaryEvent() {
        JsonObject object = baseEvent("entity_query_summary");
        JsonArray entries = new JsonArray();
        entityQueries.ranked().stream().limit(50).forEach(evidence -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("key", evidence.key());
            entry.addProperty("suspected_mod", evidence.suspectedModId());
            entry.addProperty("confidence", evidence.confidence());
            entry.addProperty("count", evidence.count());
            entry.addProperty("total_ms", evidence.totalMillis());
            entry.addProperty("max_ms", evidence.maxMillis());
            entry.addProperty("max_results", evidence.maxResults());
            entry.addProperty("max_aabb_volume", evidence.maxVolume());
            entry.add("query_shapes", countedMap(evidence.queryShapes()));
            entry.add("ai_owners", countedMap(evidence.aiOwners()));
            entry.add("chunks", countedMap(evidence.chunks()));
            entry.add("frames", countedMap(evidence.frames()));
            entries.add(entry);
        });
        object.add("entries", entries);
        writer.write(object);
    }

    private void writeThreadSampleSummaryEvent() {
        JsonObject object = baseEvent("thread_sample_summary");
        object.addProperty("render_samples", threadSamples.renderSamples());
        object.addProperty("server_samples", threadSamples.serverSamples());
        object.addProperty("worker_samples", threadSamples.workerSamples());
        object.add("render_frames", countedMap(threadSamples.renderFrames()));
        object.add("server_frames", countedMap(threadSamples.serverFrames()));
        object.add("worker_frames", countedMap(threadSamples.workerFrames()));
        writer.write(object);
    }

    private double actualTps(long nowNanos) {
        double elapsedSeconds = elapsedWallSeconds(nowNanos);
        if (elapsedSeconds <= 0.0D) {
            return 0.0D;
        }
        return metrics.tickCount() / elapsedSeconds;
    }

    private double elapsedWallSeconds(long nowNanos) {
        return Math.max(0.0D, (nowNanos - startedNanos) / 1_000_000_000.0D);
    }

    private static JsonArray countedMap(Map<String, Integer> map) {
        JsonArray array = new JsonArray();
        map.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(20)
                .forEach(entry -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("key", entry.getKey());
                    object.addProperty("count", entry.getValue());
                    array.add(object);
                });
        return array;
    }

    private void writeThreadSample() {
        JsonObject object = baseEvent("thread_sample");
        JsonArray threads = new JsonArray();
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            if (!thread.getName().contains("Server") && !thread.getName().contains("Render") && !thread.getName().contains("Worker")) {
                continue;
            }
            JsonObject threadJson = new JsonObject();
            threadJson.addProperty("name", thread.getName());
            threadJson.addProperty("state", thread.getState().name());
            JsonArray stack = new JsonArray();
            StackTraceElement[] frames = entry.getValue();
            for (int index = 0; index < Math.min(18, frames.length); index++) {
                stack.add(frames[index].toString());
            }
            threadJson.add("stack", stack);
            threads.add(threadJson);
        }
        object.add("threads", threads);
        writer.write(object);
    }

    private void startSamplerThread() {
        Thread sampler = new Thread(() -> {
            while (isActive()) {
                sampleInterestingThreads();
                try {
                    Thread.sleep(SAMPLER_INTERVAL_MILLIS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "FragmentDoctor scanner sampler " + sessionId);
        sampler.setDaemon(true);
        sampler.start();
    }

    private void sampleInterestingThreads() {
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            String name = entry.getKey().getName();
            if (name.contains("Render") || name.contains("Server") || name.contains("Worker")) {
                threadSamples.add(name, entry.getValue());
            }
        }
    }

    private JsonObject memorySnapshot() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        JsonObject object = new JsonObject();
        object.addProperty("heap_used", heap.getUsed());
        object.addProperty("heap_committed", heap.getCommitted());
        object.addProperty("heap_max", heap.getMax());
        object.addProperty("non_heap_used", nonHeap.getUsed());
        object.addProperty("non_heap_committed", nonHeap.getCommitted());
        return object;
    }

    private JsonObject gcDelta() {
        GcTotals now = GcTotals.now();
        JsonObject object = new JsonObject();
        object.addProperty("collections", now.collections - lastGcTotals.collections);
        object.addProperty("collection_time_ms", now.collectionTimeMillis - lastGcTotals.collectionTimeMillis);
        lastGcTotals = now;
        if (object.get("collections").getAsLong() > 0 || object.get("collection_time_ms").getAsLong() > 0) {
            JsonObject event = baseEvent("gc_pause");
            event.add("gc_delta", object.deepCopy());
            writer.write(event);
        }
        return object;
    }

    private JsonObject baseEvent(String eventType) {
        JsonObject object = new JsonObject();
        object.addProperty("event", eventType);
        object.addProperty("schema", "fragmentdoctor.scanner.v1");
        object.addProperty("session_id", sessionId);
        object.addProperty("timestamp", Instant.now().toString());
        object.addProperty("tick_index", elapsedTicks);
        return object;
    }

    private static void addTarget(JsonObject object, ObservedTarget target) {
        JsonObject json = new JsonObject();
        json.addProperty("kind", target.kind());
        json.addProperty("id", target.id());
        json.addProperty("owner_mod_id", target.ownerModId());
        json.addProperty("dimension", target.dimension());
        json.addProperty("position", target.position());
        json.addProperty("chunk", target.chunk());
        json.addProperty("class", target.className());
        json.addProperty("detail", target.detail());
        object.add("target", json);
    }

    private record GcTotals(long collections, long collectionTimeMillis) {
        private static GcTotals now() {
            long collections = 0L;
            long time = 0L;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                collections += Math.max(0L, bean.getCollectionCount());
                time += Math.max(0L, bean.getCollectionTime());
            }
            return new GcTotals(collections, time);
        }
    }
}
