package dev.cutexxgirl.fragmentdoctor.modules.scanner.report;

import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.SectionRecord;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AiCostEvidence {
    private final String key;
    private final String suspectedModId;
    private long totalNanos;
    private long maxNanos;
    private int count;
    private final Map<String, Integer> components = new LinkedHashMap<>();
    private final Map<String, Integer> owners = new LinkedHashMap<>();
    private final Map<String, Integer> phases = new LinkedHashMap<>();
    private final Map<String, Integer> chunks = new LinkedHashMap<>();
    private final Map<String, Integer> frames = new LinkedHashMap<>();

    public AiCostEvidence(String key, String suspectedModId) {
        this.key = key;
        this.suspectedModId = suspectedModId;
    }

    public void add(SectionRecord record) {
        count++;
        totalNanos += record.durationNanos();
        maxNanos = Math.max(maxNanos, record.durationNanos());
        increment(components, record.target().kind() + ":" + record.target().id());
        increment(phases, record.section());
        if (record.target().chunk() != null) {
            increment(chunks, record.target().dimension() + ":" + record.target().chunk());
        }
        String owner = parseDetailValue(record.target().detail(), "entity=");
        if (owner == null) {
            owner = parseDetailValue(record.target().detail(), "ai_context_owner=");
        }
        if (owner != null) {
            increment(owners, owner);
        }
        record.caller().stack().stream().limit(10).forEach(frame -> increment(frames, frame));
    }

    public String key() {
        return key;
    }

    public String suspectedModId() {
        return suspectedModId;
    }

    public int count() {
        return count;
    }

    public double totalMillis() {
        return totalNanos / 1_000_000.0D;
    }

    public double maxMillis() {
        return maxNanos / 1_000_000.0D;
    }

    public double confidence() {
        double timeScore = Math.min(0.45D, totalMillis() / 250.0D);
        double frequencyScore = Math.min(0.30D, count / 25.0D);
        double identityScore = "unknown".equals(suspectedModId) ? 0.0D : 0.15D;
        return Math.min(0.97D, timeScore + frequencyScore + identityScore);
    }

    public Map<String, Integer> components() {
        return components;
    }

    public Map<String, Integer> owners() {
        return owners;
    }

    public Map<String, Integer> phases() {
        return phases;
    }

    public Map<String, Integer> chunks() {
        return chunks;
    }

    public Map<String, Integer> frames() {
        return frames;
    }

    private static String parseDetailValue(String detail, String prefix) {
        if (detail == null) {
            return null;
        }
        int index = detail.indexOf(prefix);
        if (index < 0) {
            return null;
        }
        int start = index + prefix.length();
        int end = detail.indexOf(", ", start);
        if (end < 0) {
            end = detail.length();
        }
        String value = detail.substring(start, end).trim();
        return value.isEmpty() ? null : value;
    }

    private static void increment(Map<String, Integer> map, String key) {
        map.merge(key, 1, Integer::sum);
    }
}
