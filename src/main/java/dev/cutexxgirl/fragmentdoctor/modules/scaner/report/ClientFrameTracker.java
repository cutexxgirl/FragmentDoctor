package dev.cutexxgirl.fragmentdoctor.modules.scaner.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ClientFrameTracker {
    private static final long FRAME_SPIKE_NANOS = 33_000_000L;
    private static final long MICRO_FREEZE_NANOS = 100_000_000L;

    private final List<Long> frames = new ArrayList<>();
    private final Map<String, Integer> spikeFrames = new LinkedHashMap<>();
    private int spikeCount;
    private int microFreezeCount;
    private long maxNanos;

    public synchronized boolean addFrame(String section, long durationNanos, List<String> stack) {
        frames.add(durationNanos);
        maxNanos = Math.max(maxNanos, durationNanos);
        if (durationNanos >= FRAME_SPIKE_NANOS) {
            spikeCount++;
            if (durationNanos >= MICRO_FREEZE_NANOS) {
                microFreezeCount++;
            }
            stack.stream().limit(12).forEach(frame -> spikeFrames.merge(frame, 1, Integer::sum));
            spikeFrames.merge("section:" + section, 1, Integer::sum);
            return true;
        }
        return false;
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

    public synchronized double averageFrameMs() {
        if (frames.isEmpty()) {
            return 0.0D;
        }
        long total = 0L;
        for (long frame : frames) {
            total += frame;
        }
        return total / (double)frames.size() / 1_000_000.0D;
    }

    public synchronized double estimatedAverageFps() {
        double averageMs = averageFrameMs();
        return averageMs <= 0.0D ? 0.0D : 1000.0D / averageMs;
    }

    public synchronized double maxFrameMs() {
        return maxNanos / 1_000_000.0D;
    }

    public synchronized double p95FrameMs() {
        return percentile(0.95D);
    }

    public synchronized double p99FrameMs() {
        return percentile(0.99D);
    }

    public synchronized Map<String, Integer> spikeFrames() {
        return new LinkedHashMap<>(spikeFrames);
    }

    private double percentile(double percentile) {
        if (frames.isEmpty()) {
            return 0.0D;
        }
        List<Long> sorted = new ArrayList<>(frames);
        sorted.sort(Comparator.naturalOrder());
        int index = (int)Math.ceil(percentile * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index) / 1_000_000.0D;
    }
}
