package dev.cutexxgirl.fragmentdoctor.modules.scanner.report;

import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.ClientFrameBreakdown;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.ClientFrameContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class ClientFrameTracker {
    private static final long FRAME_SPIKE_NANOS = 33_000_000L;
    private static final long MICRO_FREEZE_NANOS = 100_000_000L;
    private static final long WINDOW_NANOS = 1_000_000_000L;
    private static final double MIN_VALID_WINDOW_SECONDS = 0.25D;

    private final List<Long> frames = new ArrayList<>();
    private final List<Long> adjustedFrames = new ArrayList<>();
    private final Map<String, Integer> spikeFrames = new LinkedHashMap<>();
    private final Map<Integer, WindowAccumulator> windows = new TreeMap<>();
    private final List<ServerSpike> serverSpikes = new ArrayList<>();
    private int spikeCount;
    private int microFreezeCount;
    private int framesOver8Ms;
    private int framesOver16Ms;
    private long maxNanos;
    private long maxAdjustedNanos;

    public synchronized boolean addFrame(
            String section,
            long elapsedNanos,
            long durationNanos,
            int reportedFps,
            List<String> stack,
            ClientFrameBreakdown.Snapshot breakdown,
            ClientFrameContext context
    ) {
        long adjustedDurationNanos = Math.max(0L, durationNanos - breakdown.scannerOverheadNanos());
        frames.add(durationNanos);
        adjustedFrames.add(adjustedDurationNanos);
        maxNanos = Math.max(maxNanos, durationNanos);
        maxAdjustedNanos = Math.max(maxAdjustedNanos, adjustedDurationNanos);
        if (adjustedDurationNanos >= 8_333_333L) {
            framesOver8Ms++;
        }
        if (adjustedDurationNanos >= 16_666_667L) {
            framesOver16Ms++;
        }

        int windowIndex = (int)Math.max(0L, elapsedNanos / WINDOW_NANOS);
        windows.computeIfAbsent(windowIndex, WindowAccumulator::new)
                .add(elapsedNanos, adjustedDurationNanos, reportedFps, breakdown, context);

        if (adjustedDurationNanos >= FRAME_SPIKE_NANOS) {
            spikeCount++;
            if (adjustedDurationNanos >= MICRO_FREEZE_NANOS) {
                microFreezeCount++;
            }
            stack.stream().limit(12).forEach(frame -> spikeFrames.merge(frame, 1, Integer::sum));
            spikeFrames.merge("section:" + section, 1, Integer::sum);
            return true;
        }
        return false;
    }

    public synchronized void addServerSpike(long elapsedNanos, double durationMillis, long gcMillis) {
        serverSpikes.add(new ServerSpike(elapsedNanos / 1_000_000_000.0D, durationMillis, gcMillis));
    }

    public synchronized int frameCount() {
        return frames.size();
    }

    public synchronized int spikeCount() {
        return spikeCount;
    }

    public synchronized int microFreezeCount() {
        return microFreezeCount;
    }

    public synchronized int framesOver8Ms() {
        return framesOver8Ms;
    }

    public synchronized int framesOver16Ms() {
        return framesOver16Ms;
    }

    public synchronized double averageFrameMs() {
        if (frames.isEmpty()) {
            return 0.0D;
        }
        return sum(frames) / (double)frames.size() / 1_000_000.0D;
    }

    public synchronized double estimatedAverageFps() {
        double averageMs = averageFrameMs();
        return averageMs <= 0.0D ? 0.0D : 1000.0D / averageMs;
    }

    public synchronized double adjustedAverageFrameMs() {
        return adjustedFrames.isEmpty() ? 0.0D : sum(adjustedFrames) / (double)adjustedFrames.size() / 1_000_000.0D;
    }

    public synchronized double adjustedEstimatedAverageFps() {
        double averageMs = adjustedAverageFrameMs();
        return averageMs <= 0.0D ? 0.0D : 1000.0D / averageMs;
    }

    public synchronized double maxFrameMs() {
        return maxNanos / 1_000_000.0D;
    }

    public synchronized double adjustedMaxFrameMs() {
        return maxAdjustedNanos / 1_000_000.0D;
    }

    public synchronized double p95FrameMs() {
        return percentileNanos(frames, 0.95D) / 1_000_000.0D;
    }

    public synchronized double p99FrameMs() {
        return percentileNanos(frames, 0.99D) / 1_000_000.0D;
    }

    public synchronized double adjustedP95FrameMs() {
        return percentileNanos(adjustedFrames, 0.95D) / 1_000_000.0D;
    }

    public synchronized double adjustedP99FrameMs() {
        return percentileNanos(adjustedFrames, 0.99D) / 1_000_000.0D;
    }

    public synchronized double totalScannerOverheadMs() {
        return Math.max(0L, sum(frames) - sum(adjustedFrames)) / 1_000_000.0D;
    }

    public synchronized double averageScannerOverheadMs() {
        return frames.isEmpty() ? 0.0D : totalScannerOverheadMs() / frames.size();
    }

    public synchronized double maxScannerOverheadMs() {
        long max = 0L;
        for (int index = 0; index < Math.min(frames.size(), adjustedFrames.size()); index++) {
            max = Math.max(max, frames.get(index) - adjustedFrames.get(index));
        }
        return max / 1_000_000.0D;
    }

    public synchronized double p10WindowFps() {
        return windowFpsPercentile(0.10D);
    }

    public synchronized double medianWindowFps() {
        return windowFpsPercentile(0.50D);
    }

    public synchronized double p90WindowFps() {
        return windowFpsPercentile(0.90D);
    }

    public synchronized double adjustedMedianWindowFps() {
        return adjustedWindowFpsPercentile(0.50D);
    }

    public synchronized double baselineWindowFps() {
        return windowFpsPercentile(0.75D);
    }

    public synchronized double dropThresholdFps() {
        double baseline = baselineWindowFps();
        return baseline <= 0.0D ? 70.0D : Math.max(70.0D, baseline * 0.60D);
    }

    public synchronized double recentFps() {
        if (windows.isEmpty()) {
            return 0.0D;
        }
        WindowAccumulator latest = windows.get(windows.keySet().stream().max(Integer::compareTo).orElse(0));
        return latest == null || latest.observedSeconds() < 0.10D ? 0.0D : latest.fps();
    }

    public synchronized Map<String, Integer> spikeFrames() {
        return new LinkedHashMap<>(spikeFrames);
    }

    public synchronized List<FrameWindow> frameWindows() {
        return validWindows().stream().map(WindowAccumulator::snapshot).toList();
    }

    public synchronized List<FpsDropEpisode> dropEpisodes() {
        List<WindowAccumulator> valid = validWindows();
        if (valid.isEmpty()) {
            return List.of();
        }
        double threshold = dropThresholdFps();
        List<FpsDropEpisode> episodes = new ArrayList<>();
        List<WindowAccumulator> current = new ArrayList<>();
        int previousIndex = Integer.MIN_VALUE;
        for (WindowAccumulator window : valid) {
            boolean degraded = window.fps() < threshold;
            boolean adjacent = previousIndex != Integer.MIN_VALUE && window.index == previousIndex + 1;
            if (!degraded || (!current.isEmpty() && !adjacent)) {
                addEpisode(episodes, current, threshold);
                current.clear();
            }
            if (degraded) {
                current.add(window);
            }
            previousIndex = window.index;
        }
        addEpisode(episodes, current, threshold);
        return episodes;
    }

    private void addEpisode(List<FpsDropEpisode> episodes, List<WindowAccumulator> episodeWindows, double threshold) {
        if (episodeWindows.isEmpty()) {
            return;
        }
        long totalNanos = 0L;
        long gameRendererNanos = 0L;
        long renderLevelNanos = 0L;
        long presentNanos = 0L;
        long scannerBetweenFramesNanos = 0L;
        double observedSeconds = 0.0D;
        double adjustedObservedSeconds = 0.0D;
        int frameCount = 0;
        int minReportedFps = Integer.MAX_VALUE;
        int maxReportedFps = 0;
        double minWindowFps = Double.MAX_VALUE;
        int width = 0;
        int height = 0;
        int renderDistance = 0;
        int maxClientEntityCount = 0;
        double playerTravelDistance = 0.0D;
        List<Long> episodeFrames = new ArrayList<>();
        for (WindowAccumulator window : episodeWindows) {
            totalNanos += window.totalNanos;
            gameRendererNanos += window.gameRendererNanos;
            renderLevelNanos += window.renderLevelNanos;
            presentNanos += window.presentNanos;
            scannerBetweenFramesNanos += window.scannerBetweenFramesNanos;
            observedSeconds += window.observedSeconds();
            adjustedObservedSeconds += window.adjustedObservedSeconds();
            frameCount += window.frameCount;
            minReportedFps = Math.min(minReportedFps, window.minReportedFps);
            maxReportedFps = Math.max(maxReportedFps, window.maxReportedFps);
            minWindowFps = Math.min(minWindowFps, window.fps());
            width = window.width;
            height = window.height;
            renderDistance = window.renderDistance;
            maxClientEntityCount = Math.max(maxClientEntityCount, window.maxClientEntityCount);
            playerTravelDistance += window.playerTravelDistance();
            episodeFrames.addAll(window.frames);
        }
        if (observedSeconds < 0.5D) {
            return;
        }

        int firstIndex = episodeWindows.getFirst().index;
        int lastIndex = episodeWindows.getLast().index;
        double startSeconds = firstIndex;
        double endSeconds = lastIndex + 1.0D;
        long correlatedGcMillis = 0L;
        int correlatedServerSpikes = 0;
        double maxServerSpikeMillis = 0.0D;
        for (ServerSpike spike : serverSpikes) {
            if (spike.elapsedSeconds >= startSeconds && spike.elapsedSeconds < endSeconds) {
                correlatedServerSpikes++;
                correlatedGcMillis += spike.gcMillis;
                maxServerSpikeMillis = Math.max(maxServerSpikeMillis, spike.durationMillis);
            }
        }

        BreakdownPercentages percentages = breakdown(totalNanos, gameRendererNanos, renderLevelNanos, presentNanos);
        episodes.add(new FpsDropEpisode(
                firstIndex,
                lastIndex,
                startSeconds,
                endSeconds,
                observedSeconds,
                threshold,
                frameCount / observedSeconds,
                adjustedObservedSeconds <= 0.0D ? 0.0D : frameCount / adjustedObservedSeconds,
                minWindowFps,
                percentileNanos(episodeFrames, 0.95D) / 1_000_000.0D,
                episodeFrames.stream().mapToLong(Long::longValue).max().orElse(0L) / 1_000_000.0D,
                minReportedFps == Integer.MAX_VALUE ? 0 : minReportedFps,
                maxReportedFps,
                percentages.worldRenderPercent,
                percentages.otherRendererPercent,
                percentages.presentPercent,
                percentages.otherFramePercent,
                percentages.dominantComponent,
                percentage(scannerBetweenFramesNanos, Math.round(observedSeconds * 1_000_000_000.0D)),
                width,
                height,
                renderDistance,
                maxClientEntityCount,
                playerTravelDistance,
                correlatedServerSpikes,
                maxServerSpikeMillis,
                correlatedGcMillis
        ));
    }

    private List<WindowAccumulator> validWindows() {
        return windows.values().stream()
                .filter(window -> window.observedSeconds() >= MIN_VALID_WINDOW_SECONDS)
                .toList();
    }

    private double windowFpsPercentile(double percentile) {
        List<Double> values = validWindows().stream()
                .map(WindowAccumulator::fps)
                .sorted()
                .toList();
        if (values.isEmpty()) {
            return 0.0D;
        }
        int index = (int)Math.ceil(percentile * values.size()) - 1;
        index = Math.max(0, Math.min(index, values.size() - 1));
        return values.get(index);
    }

    private double adjustedWindowFpsPercentile(double percentile) {
        List<Double> values = validWindows().stream()
                .map(WindowAccumulator::adjustedFps)
                .sorted()
                .toList();
        if (values.isEmpty()) {
            return 0.0D;
        }
        int index = (int)Math.ceil(percentile * values.size()) - 1;
        index = Math.max(0, Math.min(index, values.size() - 1));
        return values.get(index);
    }

    private static BreakdownPercentages breakdown(long totalNanos, long gameRendererNanos, long renderLevelNanos, long presentNanos) {
        long world = Math.min(totalNanos, renderLevelNanos);
        long rendererOther = Math.max(0L, Math.min(totalNanos - world, gameRendererNanos - world));
        long present = Math.max(0L, Math.min(totalNanos - world - rendererOther, presentNanos));
        long other = Math.max(0L, totalNanos - world - rendererOther - present);
        String dominant = "other_frame";
        long dominantNanos = other;
        if (world > dominantNanos) {
            dominant = "render_level";
            dominantNanos = world;
        }
        if (rendererOther > dominantNanos) {
            dominant = "other_game_renderer";
            dominantNanos = rendererOther;
        }
        if (present > dominantNanos) {
            dominant = "present_or_gpu_wait";
        }
        return new BreakdownPercentages(
                percentage(world, totalNanos),
                percentage(rendererOther, totalNanos),
                percentage(present, totalNanos),
                percentage(other, totalNanos),
                dominant
        );
    }

    private static double percentage(long value, long total) {
        return total <= 0L ? 0.0D : value * 100.0D / total;
    }

    private static long sum(List<Long> values) {
        long total = 0L;
        for (long value : values) {
            total += value;
        }
        return total;
    }

    private static long percentileNanos(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int index = (int)Math.ceil(percentile * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    public record FrameWindow(
            int index,
            double startSeconds,
            double endSeconds,
            int frameCount,
            double fps,
            double scannerAdjustedFps,
            double averageFrameMillis,
            double p95FrameMillis,
            double maxFrameMillis,
            int minReportedFps,
            int maxReportedFps,
            double worldRenderPercent,
            double otherRendererPercent,
            double presentPercent,
            double otherFramePercent,
            String dominantComponent,
            double scannerBetweenFramesPercent,
            int width,
            int height,
            int renderDistance,
            int maxClientEntityCount,
            double playerTravelDistance
    ) {
    }

    public record FpsDropEpisode(
            int startWindow,
            int endWindow,
            double startSeconds,
            double endSeconds,
            double observedSeconds,
            double thresholdFps,
            double averageFps,
            double scannerAdjustedAverageFps,
            double minimumWindowFps,
            double p95FrameMillis,
            double maxFrameMillis,
            int minReportedFps,
            int maxReportedFps,
            double worldRenderPercent,
            double otherRendererPercent,
            double presentPercent,
            double otherFramePercent,
            String dominantComponent,
            double scannerBetweenFramesPercent,
            int width,
            int height,
            int renderDistance,
            int maxClientEntityCount,
            double playerTravelDistance,
            int correlatedServerSpikes,
            double maxServerSpikeMillis,
            long correlatedGcMillis
    ) {
    }

    private record ServerSpike(double elapsedSeconds, double durationMillis, long gcMillis) {
    }

    private record BreakdownPercentages(
            double worldRenderPercent,
            double otherRendererPercent,
            double presentPercent,
            double otherFramePercent,
            String dominantComponent
    ) {
    }

    private static final class WindowAccumulator {
        private final int index;
        private final List<Long> frames = new ArrayList<>();
        private long totalNanos;
        private long gameRendererNanos;
        private long renderLevelNanos;
        private long presentNanos;
        private long scannerBetweenFramesNanos;
        private int frameCount;
        private int minReportedFps = Integer.MAX_VALUE;
        private int maxReportedFps;
        private int width;
        private int height;
        private int renderDistance;
        private int maxClientEntityCount;
        private long firstElapsedNanos = Long.MIN_VALUE;
        private long lastElapsedNanos = Long.MIN_VALUE;
        private long lastFrameNanos;
        private boolean hasPlayerPosition;
        private double firstPlayerX;
        private double firstPlayerY;
        private double firstPlayerZ;
        private double lastPlayerX;
        private double lastPlayerY;
        private double lastPlayerZ;

        private WindowAccumulator(int index) {
            this.index = index;
        }

        private void add(
                long elapsedNanos,
                long durationNanos,
                int reportedFps,
                ClientFrameBreakdown.Snapshot breakdown,
                ClientFrameContext context
        ) {
            frames.add(durationNanos);
            totalNanos += durationNanos;
            if (firstElapsedNanos == Long.MIN_VALUE) {
                firstElapsedNanos = elapsedNanos;
            }
            lastElapsedNanos = elapsedNanos;
            lastFrameNanos = durationNanos;
            frameCount++;
            gameRendererNanos += breakdown.gameRendererNanos();
            renderLevelNanos += breakdown.renderLevelNanos();
            presentNanos += breakdown.presentNanos();
            scannerBetweenFramesNanos += breakdown.scannerBetweenFramesNanos();
            if (reportedFps > 0) {
                minReportedFps = Math.min(minReportedFps, reportedFps);
                maxReportedFps = Math.max(maxReportedFps, reportedFps);
            }
            width = context.width();
            height = context.height();
            renderDistance = context.renderDistance();
            maxClientEntityCount = Math.max(maxClientEntityCount, context.clientEntityCount());
            if (context.width() > 0 && context.height() > 0) {
                if (!hasPlayerPosition) {
                    firstPlayerX = context.playerX();
                    firstPlayerY = context.playerY();
                    firstPlayerZ = context.playerZ();
                    hasPlayerPosition = true;
                }
                lastPlayerX = context.playerX();
                lastPlayerY = context.playerY();
                lastPlayerZ = context.playerZ();
            }
        }

        private double observedSeconds() {
            if (firstElapsedNanos == Long.MIN_VALUE) {
                return 0.0D;
            }
            long wallNanos = Math.max(lastFrameNanos, lastElapsedNanos - firstElapsedNanos + lastFrameNanos);
            return Math.min(WINDOW_NANOS, wallNanos) / 1_000_000_000.0D;
        }

        private double fps() {
            double seconds = observedSeconds();
            return seconds <= 0.0D ? 0.0D : frameCount / seconds;
        }

        private double adjustedObservedSeconds() {
            long rawObservedNanos = Math.round(observedSeconds() * 1_000_000_000.0D);
            long adjustedObservedNanos = Math.max(totalNanos, rawObservedNanos - scannerBetweenFramesNanos);
            return adjustedObservedNanos / 1_000_000_000.0D;
        }

        private double adjustedFps() {
            double seconds = adjustedObservedSeconds();
            return seconds <= 0.0D ? 0.0D : frameCount / seconds;
        }

        private FrameWindow snapshot() {
            BreakdownPercentages percentages = breakdown(totalNanos, gameRendererNanos, renderLevelNanos, presentNanos);
            long observedNanos = Math.round(observedSeconds() * 1_000_000_000.0D);
            return new FrameWindow(
                    index,
                    index,
                    index + 1.0D,
                    frameCount,
                    fps(),
                    adjustedFps(),
                    frameCount == 0 ? 0.0D : totalNanos / (double)frameCount / 1_000_000.0D,
                    percentileNanos(frames, 0.95D) / 1_000_000.0D,
                    frames.stream().mapToLong(Long::longValue).max().orElse(0L) / 1_000_000.0D,
                    minReportedFps == Integer.MAX_VALUE ? 0 : minReportedFps,
                    maxReportedFps,
                    percentages.worldRenderPercent,
                    percentages.otherRendererPercent,
                    percentages.presentPercent,
                    percentages.otherFramePercent,
                    percentages.dominantComponent,
                    percentage(scannerBetweenFramesNanos, observedNanos),
                    width,
                    height,
                    renderDistance,
                    maxClientEntityCount,
                    playerTravelDistance()
            );
        }

        private double playerTravelDistance() {
            if (!hasPlayerPosition) {
                return 0.0D;
            }
            double dx = lastPlayerX - firstPlayerX;
            double dy = lastPlayerY - firstPlayerY;
            double dz = lastPlayerZ - firstPlayerZ;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }
}
