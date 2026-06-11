package dev.cutexxgirl.fragmentdoctor.modules.scanner.report;

import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.SectionRecord;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SuspectEvidence {
    private final String key;
    private final String suspectedModId;
    private long totalNanos;
    private long maxNanos;
    private int evidenceCount;
    private final Map<String, Integer> targets = new LinkedHashMap<>();
    private final Map<String, Integer> chunks = new LinkedHashMap<>();
    private final Map<String, Integer> frames = new LinkedHashMap<>();

    public SuspectEvidence(String key, String suspectedModId) {
        this.key = key;
        this.suspectedModId = suspectedModId;
    }

    public void add(SectionRecord record) {
        evidenceCount++;
        totalNanos += record.durationNanos();
        maxNanos = Math.max(maxNanos, record.durationNanos());
        increment(targets, record.target().kind() + ":" + record.target().id());
        if (record.target().chunk() != null) {
            increment(chunks, record.target().dimension() + ":" + record.target().chunk());
        }
        record.caller().stack().stream().limit(8).forEach(frame -> increment(frames, frame));
    }

    public String key() {
        return key;
    }

    public String suspectedModId() {
        return suspectedModId;
    }

    public int evidenceCount() {
        return evidenceCount;
    }

    public long totalNanos() {
        return totalNanos;
    }

    public double totalMillis() {
        return totalNanos / 1_000_000.0D;
    }

    public double maxMillis() {
        return maxNanos / 1_000_000.0D;
    }

    public double confidence() {
        double timeScore = Math.min(0.55D, totalMillis() / 500.0D);
        double evidenceScore = Math.min(0.35D, evidenceCount / 20.0D);
        double identityScore = "unknown".equals(suspectedModId) ? 0.0D : 0.10D;
        return Math.min(0.95D, timeScore + evidenceScore + identityScore);
    }

    public Map<String, Integer> targets() {
        return targets;
    }

    public Map<String, Integer> chunks() {
        return chunks;
    }

    public Map<String, Integer> frames() {
        return frames;
    }

    private static void increment(Map<String, Integer> map, String key) {
        map.merge(key, 1, Integer::sum);
    }
}
