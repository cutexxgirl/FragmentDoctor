package dev.cutexxgirl.fragmentdoctor.modules.scanner.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.cutexxgirl.fragmentdoctor.FragmentDoctor;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.ObservedTarget;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.SectionRecord;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.ClientFrameBreakdown;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.ClientFrameContext;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.ScannerSelfOverhead;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.log.JsonlLogWriter;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.metrics.TickMetrics;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.report.AiCostTracker;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.report.ClientFrameTracker;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.report.EntityQueryTracker;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.report.GlErrorTracker;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.report.InputPollTracker;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.report.PendingTickInspector;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.report.PendingTickSnapshot;
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
import java.util.ArrayList;
import java.util.List;
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
    private static final long PRESSURED_SAMPLER_INTERVAL_MILLIS = 100L;
    private static final long THREAD_DISCOVERY_INTERVAL_NANOS = 5_000_000_000L;
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
    private final AiCostTracker aiCosts = new AiCostTracker();
    private final ThreadSampleTracker threadSamples = new ThreadSampleTracker();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private long tickStartNanos;
    private int elapsedTicks;
    private GcTotals lastGcTotals = GcTotals.now();
    private long lastSpikeThreadDumpNanos;
    private long lastThreadDiscoveryNanos;
    private final List<Thread> sampledThreads = new ArrayList<>();

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
        ScannerSelfOverhead.reset();
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
        ScannerSelfOverhead.beginServerTick();
        tickStartNanos = System.nanoTime();
    }

    public Path onServerTickEnd(MinecraftServer server) {
        long now = System.nanoTime();
        long duration = now - tickStartNanos;
        long scannerOverheadNanos = ScannerSelfOverhead.currentServerTickNanos();
        elapsedTicks++;
        metrics.addTick(duration, scannerOverheadNanos);
        if (duration >= TICK_SPIKE_NANOS) {
            long spikeSnapshotStart = ScannerSelfOverhead.start();
            try {
                writeTickSpike(server, duration);
            } finally {
                ScannerSelfOverhead.end("tick_spike_snapshot", spikeSnapshotStart);
            }
            if (now - lastSpikeThreadDumpNanos >= SPIKE_THREAD_DUMP_INTERVAL_NANOS) {
                lastSpikeThreadDumpNanos = now;
                long threadDumpStart = ScannerSelfOverhead.start();
                try {
                    writeThreadSample();
                } finally {
                    ScannerSelfOverhead.end("spike_thread_dump", threadDumpStart);
                }
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
        if (isAiCost(record.eventType())) {
            aiCosts.add(record);
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

    public void recordClientFrame(
            String section,
            long durationNanos,
            int reportedFps,
            String screenName,
            boolean inWorld,
            java.util.List<String> stack,
            ClientFrameBreakdown.Snapshot breakdown,
            ClientFrameContext context
    ) {
        if (!isActive()) {
            return;
        }
        long elapsedNanos = Math.max(0L, System.nanoTime() - startedNanos);
        boolean spike = clientFrames.addFrame(section, elapsedNanos, durationNanos, reportedFps, stack, breakdown, context);
        if (!spike) {
            return;
        }
        JsonObject object = baseEvent("client_frame_spike");
        object.addProperty("section", section);
        object.addProperty("duration_nanos", durationNanos);
        object.addProperty("duration_ms", durationNanos / 1_000_000.0D);
        object.addProperty("scanner_overhead_ms", breakdown.scannerOverheadNanos() / 1_000_000.0D);
        object.addProperty("scanner_between_frames_ms", breakdown.scannerBetweenFramesNanos() / 1_000_000.0D);
        object.addProperty("scanner_adjusted_duration_ms", Math.max(0L, durationNanos - breakdown.scannerOverheadNanos()) / 1_000_000.0D);
        object.addProperty("reported_fps", reportedFps);
        object.addProperty("screen", screenName);
        object.addProperty("in_world", inWorld);
        object.addProperty("micro_freeze", Math.max(0L, durationNanos - breakdown.scannerOverheadNanos()) >= 100_000_000L);
        object.addProperty("game_renderer_ms", breakdown.gameRendererNanos() / 1_000_000.0D);
        object.addProperty("render_level_ms", breakdown.renderLevelNanos() / 1_000_000.0D);
        object.addProperty("present_ms", breakdown.presentNanos() / 1_000_000.0D);
        object.addProperty("width", context.width());
        object.addProperty("height", context.height());
        object.addProperty("render_distance", context.renderDistance());
        object.addProperty("client_entity_count", context.clientEntityCount());
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
        long activeServerOverheadNanos = ScannerSelfOverhead.totalServerThreadNanos();
        long activeRenderOverheadNanos = ScannerSelfOverhead.totalRenderThreadNanos();
        long pendingSnapshotStart = ScannerSelfOverhead.start();
        List<PendingTickSnapshot> pendingTickSnapshots;
        try {
            pendingTickSnapshots = PendingTickInspector.capture(server);
        } finally {
            ScannerSelfOverhead.end("pending_tick_snapshot", pendingSnapshotStart);
        }
        writeScheduledTickSummaryEvent();
        writePendingTickSnapshotEvent(pendingTickSnapshots);
        writeGlErrorSummaryEvent();
        writeInvalidKeyPollSummaryEvent();
        writeAiCostSummaryEvent();
        writeEntityQuerySummaryEvent();
        writeClientFrameSummaryEvents();
        writeThreadSampleSummaryEvent();
        JsonObject object = baseEvent("session_end");
        object.addProperty("reason", reason);
        object.addProperty("requested_seconds", durationSeconds);
        object.addProperty("expected_ticks", durationTicks);
        object.addProperty("elapsed_wall_seconds", elapsedWallSeconds(endedNanos));
        object.addProperty("actual_tps", actualTps(endedNanos));
        object.addProperty("scanner_adjusted_actual_tps", scannerAdjustedActualTps(endedNanos, activeServerOverheadNanos));
        object.addProperty("ticks", metrics.tickCount());
        object.addProperty("avg_mspt", metrics.averageMspt());
        object.addProperty("max_mspt", metrics.maxMspt());
        object.addProperty("p95_mspt", metrics.p95Mspt());
        object.addProperty("p99_mspt", metrics.p99Mspt());
        object.addProperty("scanner_adjusted_avg_mspt", metrics.adjustedAverageMspt());
        object.addProperty("scanner_adjusted_max_mspt", metrics.adjustedMaxMspt());
        object.addProperty("scanner_adjusted_p95_mspt", metrics.adjustedP95Mspt());
        object.addProperty("scanner_adjusted_p99_mspt", metrics.adjustedP99Mspt());
        object.addProperty("scanner_server_overhead_total_ms", metrics.totalScannerOverheadMs());
        object.addProperty("scanner_server_overhead_avg_ms", metrics.averageScannerOverheadMs());
        object.addProperty("scanner_server_overhead_max_ms", metrics.maxScannerOverheadMs());
        object.addProperty("scanner_client_overhead_total_ms", clientFrames.totalScannerOverheadMs());
        object.addProperty("scanner_client_overhead_avg_ms", clientFrames.averageScannerOverheadMs());
        object.addProperty("scanner_client_overhead_max_ms", clientFrames.maxScannerOverheadMs());
        object.addProperty("scanner_active_server_main_thread_total_ms", activeServerOverheadNanos / 1_000_000.0D);
        object.addProperty("scanner_active_render_main_thread_total_ms", activeRenderOverheadNanos / 1_000_000.0D);
        object.add("scanner_self_overhead_by_category_ms", nanosMapAsMillis(ScannerSelfOverhead.totalsNanos()));
        object.add("scanner_self_overhead_call_counts", longCountedMap(ScannerSelfOverhead.counts()));
        object.addProperty("rolling_tps", metrics.rollingTps());
        object.addProperty("jsonl_dropped_events", writer.droppedEvents());
        writer.write(object);
        try {
            writer.close();
            ScanSummaryWriter.write(
                    summaryPath,
                    sessionId,
                    startedAt,
                    endedAt,
                    durationSeconds,
                    metrics,
                    suspects,
                    scheduledTicks,
                    pendingTickSnapshots,
                    glErrors,
                    inputPolls,
                    clientFrames,
                    entityQueries,
                    aiCosts,
                    threadSamples,
                    activeServerOverheadNanos,
                    activeRenderOverheadNanos,
                    logPath
            );
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
        object.addProperty("scanner_overhead_ms", ScannerSelfOverhead.currentServerTickNanos() / 1_000_000.0D);
        object.addProperty("scanner_adjusted_duration_ms", Math.max(0L, duration - ScannerSelfOverhead.currentServerTickNanos()) / 1_000_000.0D);
        object.addProperty("micro_freeze", duration >= MICRO_FREEZE_NANOS);
        object.addProperty("rolling_tps", metrics.rollingTps());
        object.addProperty("avg_mspt", metrics.averageMspt());
        object.add("memory", memorySnapshot());
        JsonObject gcDelta = gcDelta();
        object.add("gc_delta", gcDelta);
        clientFrames.addServerSpike(
                Math.max(0L, System.nanoTime() - startedNanos),
                duration / 1_000_000.0D,
                gcDelta.get("collection_time_ms").getAsLong()
        );
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

    private void writePendingTickSnapshotEvent(List<PendingTickSnapshot> snapshots) {
        JsonObject object = baseEvent("pending_tick_snapshot");
        JsonArray entries = new JsonArray();
        snapshots.stream()
                .filter(snapshot -> snapshot.totalCount() > 0)
                .forEach(snapshot -> {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("dimension", snapshot.dimension());
                    entry.addProperty("queue_kind", snapshot.queueKind());
                    entry.addProperty("total_count", snapshot.totalCount());
                    entry.addProperty("container_count", snapshot.containerCount());
                    entry.addProperty("sampled_count", snapshot.sampledCount());
                    entry.addProperty("sample_coverage", snapshot.sampleCoverage());
                    entry.addProperty("complete_type_sample", snapshot.completeTypeSample());
                    entry.addProperty("status", snapshot.status());
                    entry.add("sampled_types", countedMap(snapshot.sampledTypes()));
                    entry.add("exact_chunks", countedMap(snapshot.exactChunks()));
                    entry.add("sampled_due_buckets", countedMap(snapshot.sampledDueBuckets()));
                    entries.add(entry);
                });
        object.add("entries", entries);
        writer.write(object);
    }

    private void writeClientFrameSummaryEvents() {
        JsonObject windowsObject = baseEvent("client_frame_window_summary");
        windowsObject.addProperty("p10_window_fps", clientFrames.p10WindowFps());
        windowsObject.addProperty("median_window_fps", clientFrames.medianWindowFps());
        windowsObject.addProperty("scanner_adjusted_median_window_fps", clientFrames.adjustedMedianWindowFps());
        windowsObject.addProperty("p90_window_fps", clientFrames.p90WindowFps());
        windowsObject.addProperty("baseline_window_fps", clientFrames.baselineWindowFps());
        windowsObject.addProperty("drop_threshold_fps", clientFrames.dropThresholdFps());
        JsonArray windows = new JsonArray();
        for (ClientFrameTracker.FrameWindow window : clientFrames.frameWindows()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("window", window.index());
            entry.addProperty("start_seconds", window.startSeconds());
            entry.addProperty("end_seconds", window.endSeconds());
            entry.addProperty("frames", window.frameCount());
            entry.addProperty("fps", window.fps());
            entry.addProperty("scanner_adjusted_fps", window.scannerAdjustedFps());
            entry.addProperty("avg_frame_ms", window.averageFrameMillis());
            entry.addProperty("p95_frame_ms", window.p95FrameMillis());
            entry.addProperty("max_frame_ms", window.maxFrameMillis());
            entry.addProperty("reported_fps_min", window.minReportedFps());
            entry.addProperty("reported_fps_max", window.maxReportedFps());
            entry.addProperty("width", window.width());
            entry.addProperty("height", window.height());
            entry.addProperty("render_distance", window.renderDistance());
            entry.addProperty("max_client_entity_count", window.maxClientEntityCount());
            entry.addProperty("player_travel_distance", window.playerTravelDistance());
            entry.addProperty("scanner_between_frames_percent", window.scannerBetweenFramesPercent());
            addFrameBreakdown(entry, window.worldRenderPercent(), window.otherRendererPercent(), window.presentPercent(), window.otherFramePercent(), window.dominantComponent());
            windows.add(entry);
        }
        windowsObject.add("windows", windows);
        writer.write(windowsObject);

        JsonObject dropsObject = baseEvent("client_fps_drop_summary");
        dropsObject.addProperty("baseline_window_fps", clientFrames.baselineWindowFps());
        dropsObject.addProperty("drop_threshold_fps", clientFrames.dropThresholdFps());
        JsonArray episodes = new JsonArray();
        for (ClientFrameTracker.FpsDropEpisode episode : clientFrames.dropEpisodes()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("start_seconds", episode.startSeconds());
            entry.addProperty("end_seconds", episode.endSeconds());
            entry.addProperty("observed_seconds", episode.observedSeconds());
            entry.addProperty("average_fps", episode.averageFps());
            entry.addProperty("scanner_adjusted_average_fps", episode.scannerAdjustedAverageFps());
            entry.addProperty("minimum_window_fps", episode.minimumWindowFps());
            entry.addProperty("p95_frame_ms", episode.p95FrameMillis());
            entry.addProperty("max_frame_ms", episode.maxFrameMillis());
            entry.addProperty("reported_fps_min", episode.minReportedFps());
            entry.addProperty("reported_fps_max", episode.maxReportedFps());
            entry.addProperty("width", episode.width());
            entry.addProperty("height", episode.height());
            entry.addProperty("render_distance", episode.renderDistance());
            entry.addProperty("max_client_entity_count", episode.maxClientEntityCount());
            entry.addProperty("player_travel_distance", episode.playerTravelDistance());
            entry.addProperty("scanner_between_frames_percent", episode.scannerBetweenFramesPercent());
            entry.addProperty("correlated_server_spikes", episode.correlatedServerSpikes());
            entry.addProperty("max_correlated_server_spike_ms", episode.maxServerSpikeMillis());
            entry.addProperty("correlated_gc_ms", episode.correlatedGcMillis());
            addFrameBreakdown(entry, episode.worldRenderPercent(), episode.otherRendererPercent(), episode.presentPercent(), episode.otherFramePercent(), episode.dominantComponent());
            entry.add("top_render_frames", countedMap(threadSamples.framesBetween(ThreadSampleTracker.ROLE_RENDER, episode.startWindow(), episode.endWindow())));
            entry.add("top_worker_frames", countedMap(threadSamples.framesBetween(ThreadSampleTracker.ROLE_WORKER, episode.startWindow(), episode.endWindow())));
            entry.add("top_server_frames", countedMap(threadSamples.framesBetween(ThreadSampleTracker.ROLE_SERVER, episode.startWindow(), episode.endWindow())));
            episodes.add(entry);
        }
        dropsObject.add("episodes", episodes);
        writer.write(dropsObject);
    }

    private static void addFrameBreakdown(
            JsonObject object,
            double worldRenderPercent,
            double otherRendererPercent,
            double presentPercent,
            double otherFramePercent,
            String dominantComponent
    ) {
        object.addProperty("render_level_percent", worldRenderPercent);
        object.addProperty("other_game_renderer_percent", otherRendererPercent);
        object.addProperty("present_or_gpu_wait_percent", presentPercent);
        object.addProperty("other_frame_percent", otherFramePercent);
        object.addProperty("dominant_component", dominantComponent);
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
            entry.add("ai_components", countedMap(evidence.aiComponents()));
            entry.add("ai_phases", countedMap(evidence.aiPhases()));
            entry.add("chunks", countedMap(evidence.chunks()));
            entry.add("frames", countedMap(evidence.frames()));
            entries.add(entry);
        });
        object.add("entries", entries);
        writer.write(object);
    }

    private void writeAiCostSummaryEvent() {
        JsonObject object = baseEvent("ai_cost_summary");
        JsonArray entries = new JsonArray();
        aiCosts.ranked().stream().limit(50).forEach(evidence -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("key", evidence.key());
            entry.addProperty("suspected_mod", evidence.suspectedModId());
            entry.addProperty("confidence", evidence.confidence());
            entry.addProperty("count", evidence.count());
            entry.addProperty("total_ms", evidence.totalMillis());
            entry.addProperty("max_ms", evidence.maxMillis());
            entry.add("components", countedMap(evidence.components()));
            entry.add("owners", countedMap(evidence.owners()));
            entry.add("phases", countedMap(evidence.phases()));
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
        object.add("server_frames", countedMap(threadSamples.serverFrames(), 40));
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

    private double scannerAdjustedActualTps(long nowNanos, long serverOverheadNanos) {
        long adjustedElapsedNanos = Math.max(1L, nowNanos - startedNanos - serverOverheadNanos);
        return Math.min(20.0D, metrics.tickCount() / (adjustedElapsedNanos / 1_000_000_000.0D));
    }

    private double elapsedWallSeconds(long nowNanos) {
        return Math.max(0.0D, (nowNanos - startedNanos) / 1_000_000_000.0D);
    }

    private static JsonArray countedMap(Map<String, Integer> map) {
        return countedMap(map, 20);
    }

    private static JsonArray countedMap(Map<String, Integer> map, int limit) {
        JsonArray array = new JsonArray();
        map.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .forEach(entry -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("key", entry.getKey());
                    object.addProperty("count", entry.getValue());
                    array.add(object);
                });
        return array;
    }

    private static JsonArray longCountedMap(Map<String, Long> map) {
        JsonArray array = new JsonArray();
        map.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(30)
                .forEach(entry -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("key", entry.getKey());
                    object.addProperty("count", entry.getValue());
                    array.add(object);
                });
        return array;
    }

    private static JsonArray nanosMapAsMillis(Map<String, Long> map) {
        JsonArray array = new JsonArray();
        map.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(30)
                .forEach(entry -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("key", entry.getKey());
                    object.addProperty("milliseconds", entry.getValue() / 1_000_000.0D);
                    array.add(object);
                });
        return array;
    }

    private void writeThreadSample() {
        JsonObject object = baseEvent("thread_sample");
        JsonArray threads = new JsonArray();
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            String role = threadRole(thread);
            if (role == null) {
                continue;
            }
            JsonObject threadJson = new JsonObject();
            threadJson.addProperty("name", thread.getName());
            threadJson.addProperty("role", role);
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
                    double recentFps = clientFrames.recentFps();
                    Thread.sleep(recentFps > 0.0D && recentFps < 120.0D ? PRESSURED_SAMPLER_INTERVAL_MILLIS : SAMPLER_INTERVAL_MILLIS);
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
        long overheadStart = ScannerSelfOverhead.start();
        try {
            long now = System.nanoTime();
            int windowIndex = (int)Math.max(0L, (now - startedNanos) / 1_000_000_000L);
            if (sampledThreads.isEmpty() || now - lastThreadDiscoveryNanos >= THREAD_DISCOVERY_INTERVAL_NANOS) {
                sampledThreads.clear();
                for (Thread thread : Thread.getAllStackTraces().keySet()) {
                    if (threadRole(thread) != null) {
                        sampledThreads.add(thread);
                    }
                }
                lastThreadDiscoveryNanos = now;
            }
            sampledThreads.removeIf(thread -> !thread.isAlive());
            for (Thread thread : sampledThreads) {
                String role = threadRole(thread);
                if (role != null) {
                    threadSamples.add(role, windowIndex, thread.getStackTrace());
                }
            }
        } finally {
            ScannerSelfOverhead.end("thread_sampler", overheadStart);
        }
    }

    private static String threadRole(Thread thread) {
        if (ClientFrameBreakdown.isRenderThread(thread)) {
            return ThreadSampleTracker.ROLE_RENDER;
        }
        String name = thread.getName();
        if ("Server thread".equals(name) || name.contains("Integrated Server")) {
            return ThreadSampleTracker.ROLE_SERVER;
        }
        if (name.contains("Worker")
                || name.contains("Executor")
                || name.contains("ForkJoin")
                || name.contains("Render")
                || name.contains("Flywheel")) {
            return ThreadSampleTracker.ROLE_WORKER;
        }
        return null;
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

    private static boolean isAiCost(String eventType) {
        return switch (eventType) {
            case "ai_goal_cost", "brain_behavior_cost", "brain_sensor_cost", "navigation_cost" -> true;
            default -> false;
        };
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
