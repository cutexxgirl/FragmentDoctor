package dev.cutexxgirl.fragmentdoctor.modules.scaner.report;

import dev.cutexxgirl.fragmentdoctor.modules.scaner.instrumentation.SectionRecord;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EntityQueryEvidence {
    private static final Pattern DETAIL = Pattern.compile("aabb=([0-9.]+)x([0-9.]+)x([0-9.]+), results=([0-9]+)");

    private final String key;
    private final String suspectedModId;
    private long totalNanos;
    private long maxNanos;
    private int count;
    private int maxResults;
    private double maxVolume;
    private final Map<String, Integer> chunks = new LinkedHashMap<>();
    private final Map<String, Integer> queryShapes = new LinkedHashMap<>();
    private final Map<String, Integer> aiOwners = new LinkedHashMap<>();
    private final Map<String, Integer> frames = new LinkedHashMap<>();

    public EntityQueryEvidence(String key, String suspectedModId) {
        this.key = key;
        this.suspectedModId = suspectedModId;
    }

    public void add(SectionRecord record) {
        count++;
        totalNanos += record.durationNanos();
        maxNanos = Math.max(maxNanos, record.durationNanos());
        if (record.target().chunk() != null) {
            increment(chunks, record.target().dimension() + ":" + record.target().chunk());
        }
        QueryShape shape = parseShape(record.target().detail());
        if (shape != null) {
            maxResults = Math.max(maxResults, shape.results());
            maxVolume = Math.max(maxVolume, shape.volume());
            increment(queryShapes, shape.label());
        } else {
            increment(queryShapes, record.target().detail());
        }
        String aiOwner = parseAiOwner(record.target().detail());
        if (aiOwner != null) {
            increment(aiOwners, aiOwner);
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

    public int maxResults() {
        return maxResults;
    }

    public double maxVolume() {
        return maxVolume;
    }

    public double confidence() {
        double timeScore = Math.min(0.35D, totalMillis() / 300.0D);
        double frequencyScore = Math.min(0.30D, count / 40.0D);
        double radiusScore = Math.min(0.20D, maxVolume / 32768.0D);
        double identityScore = "unknown".equals(suspectedModId) ? 0.0D : 0.10D;
        return Math.min(0.97D, timeScore + frequencyScore + radiusScore + identityScore);
    }

    public Map<String, Integer> chunks() {
        return chunks;
    }

    public Map<String, Integer> queryShapes() {
        return queryShapes;
    }

    public Map<String, Integer> aiOwners() {
        return aiOwners;
    }

    public Map<String, Integer> frames() {
        return frames;
    }

    private static QueryShape parseShape(String detail) {
        if (detail == null) {
            return null;
        }
        Matcher matcher = DETAIL.matcher(detail);
        if (!matcher.find()) {
            return null;
        }
        double x = Double.parseDouble(matcher.group(1));
        double y = Double.parseDouble(matcher.group(2));
        double z = Double.parseDouble(matcher.group(3));
        int results = Integer.parseInt(matcher.group(4));
        return new QueryShape(x, y, z, results);
    }

    private static String parseAiOwner(String detail) {
        if (detail == null) {
            return null;
        }
        int index = detail.indexOf("ai_owner=");
        if (index < 0) {
            return null;
        }
        int end = detail.indexOf(", uuid=", index);
        if (end < 0) {
            end = detail.length();
        }
        return detail.substring(index + "ai_owner=".length(), end);
    }

    private static void increment(Map<String, Integer> map, String key) {
        map.merge(key, 1, Integer::sum);
    }

    private record QueryShape(double x, double y, double z, int results) {
        double volume() {
            return x * y * z;
        }

        String label() {
            return String.format(Locale.ROOT, "%.1fx%.1fx%.1f results<=%d", x, y, z, results);
        }
    }
}
