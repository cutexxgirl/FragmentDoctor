package dev.cutexxgirl.fragmentdoctor.modules.scanner.report;

import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.ScannerSelfOverhead;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.metrics.TickMetrics;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

public final class ScanSummaryWriter {
    private ScanSummaryWriter() {
    }

    public static void write(
            Path path,
            String sessionId,
            Instant startedAt,
            Instant endedAt,
            int requestedSeconds,
            TickMetrics metrics,
            SuspectTracker suspects,
            ScheduledTickTracker scheduledTicks,
            java.util.List<PendingTickSnapshot> pendingTickSnapshots,
            GlErrorTracker glErrors,
            InputPollTracker inputPolls,
            ClientFrameTracker clientFrames,
            EntityQueryTracker entityQueries,
            AiCostTracker aiCosts,
            ThreadSampleTracker threadSamples,
            long activeServerOverheadNanos,
            long activeRenderOverheadNanos,
            Path logPath
    ) throws IOException {
        double elapsedSeconds = Math.max(0.001D, Duration.between(startedAt, endedAt).toMillis() / 1000.0D);
        double actualTps = metrics.tickCount() / elapsedSeconds;
        double adjustedElapsedSeconds = Math.max(0.001D, elapsedSeconds - activeServerOverheadNanos / 1_000_000_000.0D);
        double adjustedActualTps = Math.min(20.0D, metrics.tickCount() / adjustedElapsedSeconds);
        StringBuilder builder = new StringBuilder();
        builder.append("# Fragment Doctor Scanner summary\n\n");
        builder.append("- session: `").append(sessionId).append("`\n");
        builder.append("- started_at: `").append(startedAt).append("`\n");
        builder.append("- ended_at: `").append(endedAt).append("`\n");
        builder.append("- requested_seconds: `").append(requestedSeconds).append("`\n");
        builder.append(String.format("- elapsed_wall_seconds: `%.3f`\n", elapsedSeconds));
        builder.append("- raw_log: `").append(logPath.getFileName()).append("`\n");
        builder.append("- ticks: `").append(metrics.tickCount()).append("`\n");
        builder.append(String.format("- actual_tps_wall_clock: `%.2f`\n", actualTps));
        builder.append(String.format("- scanner_adjusted_actual_tps_wall_clock: `%.2f`\n", adjustedActualTps));
        builder.append(String.format("- rolling_tps: `%.2f`\n", metrics.rollingTps()));
        builder.append(String.format("- avg_mspt: `%.3f`\n", metrics.averageMspt()));
        builder.append(String.format("- max_mspt: `%.3f`\n", metrics.maxMspt()));
        builder.append(String.format("- p95_mspt: `%.3f`\n", metrics.p95Mspt()));
        builder.append(String.format("- p99_mspt: `%.3f`\n", metrics.p99Mspt()));
        builder.append(String.format("- scanner_adjusted_avg_mspt: `%.3f`\n", metrics.adjustedAverageMspt()));
        builder.append(String.format("- scanner_adjusted_max_mspt: `%.3f`\n", metrics.adjustedMaxMspt()));
        builder.append(String.format("- scanner_adjusted_p95_mspt: `%.3f`\n", metrics.adjustedP95Mspt()));
        builder.append(String.format("- scanner_adjusted_p99_mspt: `%.3f`\n\n", metrics.adjustedP99Mspt()));

        builder.append("## Scanner overhead guardrails\n\n");
        builder.append(String.format("- measured_server_thread_overhead_total_ms: `%.3f`\n", metrics.totalScannerOverheadMs()));
        builder.append(String.format("- measured_server_thread_overhead_avg_per_tick_ms: `%.4f`\n", metrics.averageScannerOverheadMs()));
        builder.append(String.format("- measured_server_thread_overhead_max_per_tick_ms: `%.4f`\n", metrics.maxScannerOverheadMs()));
        builder.append(String.format("- measured_render_thread_overhead_total_ms: `%.3f`\n", clientFrames.totalScannerOverheadMs()));
        builder.append(String.format("- measured_render_thread_overhead_avg_per_frame_ms: `%.4f`\n", clientFrames.averageScannerOverheadMs()));
        builder.append(String.format("- measured_render_thread_overhead_max_per_frame_ms: `%.4f`\n", clientFrames.maxScannerOverheadMs()));
        builder.append(String.format("- active_window_server_main_thread_overhead_total_ms: `%.3f`\n", activeServerOverheadNanos / 1_000_000.0D));
        builder.append(String.format("- active_window_render_main_thread_overhead_total_ms: `%.3f`\n", activeRenderOverheadNanos / 1_000_000.0D));
        builder.append("- inclusive_overhead_by_category_ms: ").append(topMillis(ScannerSelfOverhead.totalsNanos(), 20)).append("\n");
        builder.append("- overhead_call_counts: ").append(topLong(ScannerSelfOverhead.counts(), 20)).append("\n");
        builder.append("- Raw MSPT/frame metrics include Scanner work executed inside the measured tick/frame. `scanner_adjusted_*` subtracts directly measured Scanner work on that same main thread.\n");
        builder.append("- Category timings are inclusive and may overlap when one Scanner operation calls another; overlap is removed before subtracting Scanner time from a tick or frame.\n");
        builder.append("- Background sampler and JSON writer time is reported separately in JSONL because it competes for CPU but cannot be safely subtracted from a specific tick or frame.\n");
        builder.append("- Adjusted values are conservative: nanosecond probes, mixin dispatch, allocations before measurement, cache effects, and cross-thread contention remain in the adjusted result.\n");
        builder.append("- New scheduled tick enqueue counts are exact. Pending queue type composition is a bounded per-chunk sample; pending counts and top chunk sizes are exact.\n");
        builder.append("- GL/key spam attribution captures early stacks and then periodically resamples the same spam source, so a changed culprit can still surface without recording a stack for every log line.\n");
        builder.append("- Live thread sampling caches relevant thread references, refreshes discovery every five seconds, and increases to 10 Hz while recent FPS is below 120; per-spike full thread dumps are rate-limited.\n\n");

        builder.append("## Client FPS / frame pacing\n\n");
        if (clientFrames.frameCount() == 0) {
            builder.append("No client frames were captured. This usually means the scan ran on a dedicated server or the client mixins were not loaded.\n\n");
        } else {
            builder.append("- frames: `").append(clientFrames.frameCount()).append("`\n");
            builder.append(String.format("- estimated_average_fps: `%.2f`\n", clientFrames.estimatedAverageFps()));
            builder.append(String.format("- avg_frame_ms: `%.3f`\n", clientFrames.averageFrameMs()));
            builder.append(String.format("- max_frame_ms: `%.3f`\n", clientFrames.maxFrameMs()));
            builder.append(String.format("- p95_frame_ms: `%.3f`\n", clientFrames.p95FrameMs()));
            builder.append(String.format("- p99_frame_ms: `%.3f`\n", clientFrames.p99FrameMs()));
            builder.append(String.format("- scanner_adjusted_average_fps: `%.2f`\n", clientFrames.adjustedEstimatedAverageFps()));
            builder.append(String.format("- scanner_adjusted_avg_frame_ms: `%.3f`\n", clientFrames.adjustedAverageFrameMs()));
            builder.append(String.format("- scanner_adjusted_max_frame_ms: `%.3f`\n", clientFrames.adjustedMaxFrameMs()));
            builder.append(String.format("- scanner_adjusted_p95_frame_ms: `%.3f`\n", clientFrames.adjustedP95FrameMs()));
            builder.append(String.format("- scanner_adjusted_p99_frame_ms: `%.3f`\n", clientFrames.adjustedP99FrameMs()));
            builder.append(String.format("- one_second_fps_p10: `%.2f`\n", clientFrames.p10WindowFps()));
            builder.append(String.format("- one_second_fps_median: `%.2f`\n", clientFrames.medianWindowFps()));
            builder.append(String.format("- scanner_adjusted_one_second_fps_median: `%.2f`\n", clientFrames.adjustedMedianWindowFps()));
            builder.append(String.format("- one_second_fps_p90: `%.2f`\n", clientFrames.p90WindowFps()));
            builder.append(String.format("- inferred_normal_fps_p75: `%.2f`\n", clientFrames.baselineWindowFps()));
            builder.append(String.format("- sustained_drop_threshold_fps: `%.2f`\n", clientFrames.dropThresholdFps()));
            builder.append("- frames_over_8_33ms_below_120fps: `").append(clientFrames.framesOver8Ms()).append("`\n");
            builder.append("- frames_over_16_67ms_below_60fps: `").append(clientFrames.framesOver16Ms()).append("`\n");
            builder.append("- frame_spikes_over_33ms: `").append(clientFrames.spikeCount()).append("`\n");
            builder.append("- render_microfreezes_over_100ms: `").append(clientFrames.microFreezeCount()).append("`\n");
            builder.append("- top_render_stack_frames: ").append(top(clientFrames.spikeFrames(), 10)).append("\n\n");

            var dropEpisodes = clientFrames.dropEpisodes();
            builder.append("### Sustained FPS drops\n\n");
            if (dropEpisodes.isEmpty()) {
                builder.append("No sustained one-second window fell below the adaptive threshold.\n\n");
            } else {
                int episodeIndex = 1;
                for (ClientFrameTracker.FpsDropEpisode episode : dropEpisodes.stream().limit(10).toList()) {
                    builder.append("#### ").append(episodeIndex++).append(". `")
                            .append(String.format("%.1f", episode.startSeconds())).append("s-")
                            .append(String.format("%.1f", episode.endSeconds())).append("s`\n");
                    builder.append(String.format("- observed_seconds: `%.3f`\n", episode.observedSeconds()));
                    builder.append(String.format("- average_fps: `%.2f`\n", episode.averageFps()));
                    builder.append(String.format("- scanner_adjusted_average_fps: `%.2f`\n", episode.scannerAdjustedAverageFps()));
                    builder.append(String.format("- minimum_one_second_fps: `%.2f`\n", episode.minimumWindowFps()));
                    builder.append(String.format("- p95_frame_ms: `%.3f`\n", episode.p95FrameMillis()));
                    builder.append(String.format("- max_frame_ms: `%.3f`\n", episode.maxFrameMillis()));
                    builder.append("- reported_fps_range: `").append(episode.minReportedFps()).append("-").append(episode.maxReportedFps()).append("`\n");
                    builder.append("- render_context: `").append(episode.width()).append("x").append(episode.height())
                            .append("`, render_distance `").append(episode.renderDistance())
                            .append("`, max_client_entities `").append(episode.maxClientEntityCount()).append("`\n");
                    builder.append(String.format("- player_travel_distance: `%.2f`\n", episode.playerTravelDistance()));
                    builder.append("- dominant_frame_component: `").append(episode.dominantComponent()).append("`\n");
                    builder.append(String.format("- scanner_between_frames_percent: `%.2f%%`\n", episode.scannerBetweenFramesPercent()));
                    builder.append(String.format("- frame_breakdown: render_level `%.1f%%`, other_renderer `%.1f%%`, present_or_gpu_wait `%.1f%%`, other_frame `%.1f%%`\n",
                            episode.worldRenderPercent(), episode.otherRendererPercent(), episode.presentPercent(), episode.otherFramePercent()));
                    builder.append("- correlated_server_spikes: `").append(episode.correlatedServerSpikes()).append("`\n");
                    builder.append(String.format("- max_correlated_server_spike_ms: `%.3f`\n", episode.maxServerSpikeMillis()));
                    builder.append("- correlated_gc_ms: `").append(episode.correlatedGcMillis()).append("`\n");
                    builder.append("- render_thread_during_drop: ")
                            .append(top(threadSamples.framesBetween(ThreadSampleTracker.ROLE_RENDER, episode.startWindow(), episode.endWindow()), 10)).append("\n");
                    builder.append("- worker_threads_during_drop: ")
                            .append(top(threadSamples.framesBetween(ThreadSampleTracker.ROLE_WORKER, episode.startWindow(), episode.endWindow()), 10)).append("\n");
                    builder.append("- server_thread_during_drop: ")
                            .append(top(threadSamples.framesBetween(ThreadSampleTracker.ROLE_SERVER, episode.startWindow(), episode.endWindow()), 10)).append("\n\n");
                }
            }
        }

        builder.append("## Ranked suspects\n\n");
        var ranked = suspects.ranked();
        if (ranked.isEmpty()) {
            builder.append("No expensive sections crossed the Scanner thresholds. Increase duration or lower thresholds in code for deeper capture.\n");
        } else {
            int index = 1;
            for (SuspectEvidence evidence : ranked.stream().limit(20).toList()) {
                builder.append("### ").append(index++).append(". `").append(evidence.key()).append("`\n");
                builder.append(String.format("- confidence: `%.2f`\n", evidence.confidence()));
                builder.append("- suspected_mod: `").append(evidence.suspectedModId()).append("`\n");
                builder.append("- evidence_count: `").append(evidence.evidenceCount()).append("`\n");
                builder.append(String.format("- total_time_ms: `%.3f`\n", evidence.totalMillis()));
                builder.append(String.format("- max_spike_ms: `%.3f`\n", evidence.maxMillis()));
                builder.append("- involved_objects: ").append(top(evidence.targets(), 6)).append("\n");
                builder.append("- involved_chunks: ").append(top(evidence.chunks(), 6)).append("\n");
                builder.append("- top_stack_frames: ").append(top(evidence.frames(), 6)).append("\n");
                builder.append("- interpretation: expensive target evidence exists, but caller stack should be treated as the stronger cause signal when it points outside vanilla/Fragment Doctor.\n\n");
            }
        }

        builder.append("## Live thread sampler\n\n");
        builder.append("- render_samples: `").append(threadSamples.renderSamples()).append("`\n");
        builder.append("- server_samples: `").append(threadSamples.serverSamples()).append("`\n");
        builder.append("- worker_samples: `").append(threadSamples.workerSamples()).append("`\n");
        builder.append("- top_render_frames: ").append(top(threadSamples.renderFrames(), 12)).append("\n");
        builder.append("- top_server_frames: ").append(top(threadSamples.serverFrames(), 32)).append("\n");
        builder.append("- top_worker_frames: ").append(top(threadSamples.workerFrames(), 12)).append("\n\n");

        builder.append("## AI cost breakdown\n\n");
        var aiRanked = aiCosts.ranked();
        if (aiRanked.isEmpty()) {
            builder.append("No expensive AI goals, brain sensors, brain behaviors, or navigation ticks crossed the Scanner threshold.\n\n");
        } else {
            builder.append("This section groups AI work by the component that actually ran. Use it before adding optimizations: a mob entity being expensive is less useful than knowing which goal/behavior/search made it expensive.\n\n");
            int index = 1;
            for (AiCostEvidence evidence : aiRanked.stream().limit(20).toList()) {
                builder.append("### ").append(index++).append(". `").append(evidence.key()).append("`\n");
                builder.append(String.format("- confidence: `%.2f`\n", evidence.confidence()));
                builder.append("- suspected_mod: `").append(evidence.suspectedModId()).append("`\n");
                builder.append("- evidence_count: `").append(evidence.count()).append("`\n");
                builder.append(String.format("- total_time_ms: `%.3f`\n", evidence.totalMillis()));
                builder.append(String.format("- max_spike_ms: `%.3f`\n", evidence.maxMillis()));
                builder.append("- components: ").append(top(evidence.components(), 6)).append("\n");
                builder.append("- owners: ").append(top(evidence.owners(), 6)).append("\n");
                builder.append("- phases: ").append(top(evidence.phases(), 6)).append("\n");
                builder.append("- involved_chunks: ").append(top(evidence.chunks(), 6)).append("\n");
                builder.append("- top_stack_frames: ").append(top(evidence.frames(), 8)).append("\n\n");
            }
        }

        builder.append("## Large entity/radius searches\n\n");
        var entityQueryRanked = entityQueries.ranked();
        if (entityQueryRanked.isEmpty()) {
            builder.append("No expensive entity radius searches crossed the Scanner threshold.\n\n");
        } else {
            builder.append("These entries are strong evidence for code repeatedly scanning entities in an area. If the expensive target is a mob/villager, this caller is usually more important than the victim entity itself.\n\n");
            int index = 1;
            for (EntityQueryEvidence evidence : entityQueryRanked.stream().limit(15).toList()) {
                builder.append("### ").append(index++).append(". `").append(evidence.key()).append("`\n");
                builder.append(String.format("- confidence: `%.2f`\n", evidence.confidence()));
                builder.append("- suspected_mod: `").append(evidence.suspectedModId()).append("`\n");
                builder.append("- query_count: `").append(evidence.count()).append("`\n");
                builder.append(String.format("- total_time_ms: `%.3f`\n", evidence.totalMillis()));
                builder.append(String.format("- max_query_ms: `%.3f`\n", evidence.maxMillis()));
                builder.append("- max_results: `").append(evidence.maxResults()).append("`\n");
                builder.append(String.format("- max_aabb_volume: `%.1f`\n", evidence.maxVolume()));
                builder.append("- query_shapes: ").append(top(evidence.queryShapes(), 6)).append("\n");
                builder.append("- ai_owners: ").append(top(evidence.aiOwners(), 6)).append("\n");
                builder.append("- ai_components: ").append(top(evidence.aiComponents(), 6)).append("\n");
                builder.append("- ai_phases: ").append(top(evidence.aiPhases(), 6)).append("\n");
                builder.append("- involved_chunks: ").append(top(evidence.chunks(), 6)).append("\n");
                builder.append("- top_stack_frames: ").append(top(evidence.frames(), 8)).append("\n");
                builder.append("- interpretation: entity/object cost here is likely downstream; the caller doing the radius query is the primary suspect.\n\n");
            }
        }

        builder.append("## Scheduled tick enqueue sources\n\n");
        var scheduledRanked = scheduledTicks.ranked();
        if (scheduledRanked.isEmpty()) {
            builder.append("No scheduled tick enqueue sources were captured during the active scan window.\n\n");
        } else {
            builder.append("Counts here are the strongest signal. Stack frames are sampled evidence, not one stack per enqueue.\n\n");
            int index = 1;
            for (ScheduleEvidence evidence : scheduledRanked.stream().limit(20).toList()) {
                builder.append("### ").append(index++).append(". `").append(evidence.key()).append("`\n");
                builder.append("- origin: `").append(evidence.origin()).append("`\n");
                builder.append("- suspected_mod: `").append(evidence.suspectedModId()).append("`\n");
                builder.append("- enqueue_count: `").append(evidence.count()).append("`\n");
                builder.append("- targets: ").append(top(evidence.targets(), 6)).append("\n");
                builder.append("- chunks: ").append(top(evidence.chunks(), 6)).append("\n");
                builder.append("- top_stack_frames: ").append(top(evidence.frames(), 6)).append("\n\n");
            }
        }

        builder.append("## Pending scheduled tick queue contents\n\n");
        var nonEmptyPending = pendingTickSnapshots.stream()
                .filter(snapshot -> snapshot.totalCount() > 0)
                .sorted(Comparator.comparingInt(PendingTickSnapshot::totalCount).reversed())
                .toList();
        if (nonEmptyPending.isEmpty()) {
            builder.append("No pending block or fluid ticks were present when the scan ended.\n\n");
        } else {
            builder.append("`total_count` and chunk sizes are exact. Type and due-time distributions are sampled evenly across loaded chunk containers to avoid making the diagnostic itself freeze the server.\n\n");
            int index = 1;
            for (PendingTickSnapshot snapshot : nonEmptyPending) {
                builder.append("### ").append(index++).append(". `")
                        .append(snapshot.dimension()).append(":").append(snapshot.queueKind()).append("`\n");
                builder.append("- total_count_exact: `").append(snapshot.totalCount()).append("`\n");
                builder.append("- non_empty_chunk_containers: `").append(snapshot.containerCount()).append("`\n");
                builder.append("- sampled_ticks: `").append(snapshot.sampledCount()).append("`\n");
                builder.append("- inspection_status: `").append(snapshot.status()).append("`\n");
                builder.append(String.format("- type_sample_coverage: `%.2f%%`\n", snapshot.sampleCoverage() * 100.0D));
                builder.append("- complete_type_sample: `").append(snapshot.completeTypeSample()).append("`\n");
                builder.append("- sampled_types: ").append(top(snapshot.sampledTypes(), 15)).append("\n");
                builder.append("- exact_top_chunks: ").append(top(snapshot.exactChunks(), 15)).append("\n");
                builder.append("- sampled_due_times: ").append(top(snapshot.sampledDueBuckets(), 8)).append("\n\n");
            }
        }

        builder.append("\n## Invalid key polling sources\n\n");
        var inputRanked = inputPolls.ranked();
        if (inputRanked.isEmpty()) {
            builder.append("No `InputConstants.isKeyDown(..., -1)` calls were captured during the active scan window.\n\n");
        } else {
            builder.append("These entries point to code that asked GLFW for key `-1`; that usually causes `GLFW error 65539: Invalid key -1` spam. The Scanner only reports it and does not suppress the call.\n\n");
            int index = 1;
            for (InputPollEvidence evidence : inputRanked.stream().limit(20).toList()) {
                builder.append("### ").append(index++).append(". `").append(evidence.key()).append("`\n");
                builder.append("- suspected_mod: `").append(evidence.suspectedModId()).append("`\n");
                builder.append("- key_code: `").append(evidence.keyCode()).append("`\n");
                builder.append("- poll_count: `").append(evidence.count()).append("`\n");
                builder.append("- caller_methods: ").append(top(evidence.callerMethods(), 6)).append("\n");
                builder.append("- top_stack_frames: ").append(top(evidence.frames(), 8)).append("\n\n");
            }
        }

        builder.append("## Client GL error spam\n\n");
        var glRanked = glErrors.ranked();
        if (glRanked.isEmpty()) {
            builder.append("No GLFW/OpenGL errors were observed while the scan was active.\n");
        } else {
            builder.append("For `65539: Invalid key -1`, compare this with the invalid key polling section above; the polling caller is usually the real source of the spam.\n\n");
            for (GlErrorEvidence evidence : glRanked.stream().limit(10).toList()) {
                builder.append("- `").append(evidence.error()).append(": ").append(evidence.description()).append("`");
                builder.append(" in `").append(evidence.section()).append("` x").append(evidence.count());
                builder.append(", suspected_mod: `").append(evidence.suspectedModId()).append("`");
                builder.append(", caller: `").append(evidence.callerKey()).append("`");
                builder.append(", top_stack_frames: ").append(top(evidence.frames(), 6)).append("\n");
            }
        }

        Files.createDirectories(path.getParent());
        Files.writeString(path, builder.toString(), StandardCharsets.UTF_8);
    }

    private static String top(Map<String, Integer> values, int limit) {
        if (values.isEmpty()) {
            return "`none`";
        }
        return values.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(entry -> "`" + entry.getKey() + "` x" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private static String topLong(Map<String, Long> values, int limit) {
        if (values.isEmpty()) {
            return "`none`";
        }
        return values.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(entry -> "`" + entry.getKey() + "` x" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private static String topMillis(Map<String, Long> values, int limit) {
        if (values.isEmpty()) {
            return "`none`";
        }
        return values.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(entry -> String.format("`%s` %.3f ms", entry.getKey(), entry.getValue() / 1_000_000.0D))
                .collect(Collectors.joining(", "));
    }
}
