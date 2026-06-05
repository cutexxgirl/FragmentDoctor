package dev.cutexxgirl.fragmentdoctor.modules.scaner.report;

import dev.cutexxgirl.fragmentdoctor.modules.scaner.metrics.TickMetrics;
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
            GlErrorTracker glErrors,
            InputPollTracker inputPolls,
            ClientFrameTracker clientFrames,
            EntityQueryTracker entityQueries,
            ThreadSampleTracker threadSamples,
            Path logPath
    ) throws IOException {
        double elapsedSeconds = Math.max(0.001D, Duration.between(startedAt, endedAt).toMillis() / 1000.0D);
        double actualTps = metrics.tickCount() / elapsedSeconds;
        StringBuilder builder = new StringBuilder();
        builder.append("# Fragment Doctor scaner summary\n\n");
        builder.append("- session: `").append(sessionId).append("`\n");
        builder.append("- started_at: `").append(startedAt).append("`\n");
        builder.append("- ended_at: `").append(endedAt).append("`\n");
        builder.append("- requested_seconds: `").append(requestedSeconds).append("`\n");
        builder.append(String.format("- elapsed_wall_seconds: `%.3f`\n", elapsedSeconds));
        builder.append("- raw_log: `").append(logPath.getFileName()).append("`\n");
        builder.append("- ticks: `").append(metrics.tickCount()).append("`\n");
        builder.append(String.format("- actual_tps_wall_clock: `%.2f`\n", actualTps));
        builder.append(String.format("- rolling_tps: `%.2f`\n", metrics.rollingTps()));
        builder.append(String.format("- avg_mspt: `%.3f`\n", metrics.averageMspt()));
        builder.append(String.format("- max_mspt: `%.3f`\n", metrics.maxMspt()));
        builder.append(String.format("- p95_mspt: `%.3f`\n", metrics.p95Mspt()));
        builder.append(String.format("- p99_mspt: `%.3f`\n\n", metrics.p99Mspt()));

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
            builder.append("- frame_spikes_over_33ms: `").append(clientFrames.spikeCount()).append("`\n");
            builder.append("- render_microfreezes_over_100ms: `").append(clientFrames.microFreezeCount()).append("`\n");
            builder.append("- top_render_stack_frames: ").append(top(clientFrames.spikeFrames(), 10)).append("\n\n");
        }

        builder.append("## Ranked suspects\n\n");
        var ranked = suspects.ranked();
        if (ranked.isEmpty()) {
            builder.append("No expensive sections crossed the scaner thresholds. Increase duration or lower thresholds in code for deeper capture.\n");
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
        builder.append("- top_server_frames: ").append(top(threadSamples.serverFrames(), 12)).append("\n");
        builder.append("- top_worker_frames: ").append(top(threadSamples.workerFrames(), 12)).append("\n\n");

        builder.append("## Large entity/radius searches\n\n");
        var entityQueryRanked = entityQueries.ranked();
        if (entityQueryRanked.isEmpty()) {
            builder.append("No expensive entity radius searches crossed the scaner threshold.\n\n");
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

        builder.append("\n## Invalid key polling sources\n\n");
        var inputRanked = inputPolls.ranked();
        if (inputRanked.isEmpty()) {
            builder.append("No `InputConstants.isKeyDown(..., -1)` calls were captured during the active scan window.\n\n");
        } else {
            builder.append("These entries point to code that asked GLFW for key `-1`; that usually causes `GLFW error 65539: Invalid key -1` spam. The scaner only reports it and does not suppress the call.\n\n");
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
}
