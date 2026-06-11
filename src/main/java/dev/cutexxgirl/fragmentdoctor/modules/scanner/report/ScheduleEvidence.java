package dev.cutexxgirl.fragmentdoctor.modules.scanner.report;

import dev.cutexxgirl.fragmentdoctor.modules.scanner.attribution.CallerAttribution;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.ObservedTarget;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ScheduleEvidence {
    private final String key;
    private final String origin;
    private final String suspectedModId;
    private int count;
    private final Map<String, Integer> targets = new LinkedHashMap<>();
    private final Map<String, Integer> chunks = new LinkedHashMap<>();
    private final Map<String, Integer> frames = new LinkedHashMap<>();

    public ScheduleEvidence(String key, String origin, String suspectedModId) {
        this.key = key;
        this.origin = origin;
        this.suspectedModId = suspectedModId;
    }

    public void add(ObservedTarget target, CallerAttribution caller) {
        count++;
        increment(targets, target.kind() + ":" + target.id());
        if (target.chunk() != null) {
            increment(chunks, target.chunk());
        }
        caller.stack().stream().limit(8).forEach(frame -> increment(frames, frame));
    }

    public String key() {
        return key;
    }

    public String origin() {
        return origin;
    }

    public String suspectedModId() {
        return suspectedModId;
    }

    public int count() {
        return count;
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
