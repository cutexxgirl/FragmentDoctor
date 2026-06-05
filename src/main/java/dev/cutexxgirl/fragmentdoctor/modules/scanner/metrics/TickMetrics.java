package dev.cutexxgirl.fragmentdoctor.modules.scanner.metrics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TickMetrics {
    private static final int ROLLING_WINDOW = 100;
    private final ArrayDeque<Long> recentTicks = new ArrayDeque<>();
    private final List<Long> allTicks = new ArrayList<>();
    private long maxTickNanos;

    public void addTick(long nanos) {
        allTicks.add(nanos);
        recentTicks.addLast(nanos);
        if (recentTicks.size() > ROLLING_WINDOW) {
            recentTicks.removeFirst();
        }
        maxTickNanos = Math.max(maxTickNanos, nanos);
    }

    public int tickCount() {
        return allTicks.size();
    }

    public double rollingTps() {
        if (recentTicks.isEmpty()) {
            return 20.0D;
        }
        double averageMspt = average(recentTicks) / 1_000_000.0D;
        if (averageMspt <= 0.0D) {
            return 20.0D;
        }
        return Math.min(20.0D, 1000.0D / averageMspt);
    }

    public double averageMspt() {
        if (allTicks.isEmpty()) {
            return 0.0D;
        }
        return average(allTicks) / 1_000_000.0D;
    }

    public double maxMspt() {
        return maxTickNanos / 1_000_000.0D;
    }

    public double p95Mspt() {
        return percentile(0.95D);
    }

    public double p99Mspt() {
        return percentile(0.99D);
    }

    private double percentile(double percentile) {
        if (allTicks.isEmpty()) {
            return 0.0D;
        }
        List<Long> sorted = new ArrayList<>(allTicks);
        sorted.sort(Comparator.naturalOrder());
        int index = (int)Math.ceil(percentile * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index) / 1_000_000.0D;
    }

    private static double average(Iterable<Long> values) {
        long total = 0L;
        int count = 0;
        for (long value : values) {
            total += value;
            count++;
        }
        return count == 0 ? 0.0D : (double)total / count;
    }
}
