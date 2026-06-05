package dev.cutexxgirl.fragmentdoctor.modules.scaner.report;

import dev.cutexxgirl.fragmentdoctor.modules.scaner.attribution.CallerAttribution;
import java.util.LinkedHashMap;
import java.util.Map;

public final class InputPollEvidence {
    private final String key;
    private final String suspectedModId;
    private final int keyCode;
    private int count;
    private final Map<String, Integer> frames = new LinkedHashMap<>();
    private final Map<String, Integer> callerMethods = new LinkedHashMap<>();

    public InputPollEvidence(String key, String suspectedModId, int keyCode) {
        this.key = key;
        this.suspectedModId = suspectedModId;
        this.keyCode = keyCode;
    }

    public void add(CallerAttribution caller) {
        count++;
        increment(callerMethods, caller.className() + "#" + caller.methodName());
        caller.stack().stream().limit(12).forEach(frame -> increment(frames, frame));
    }

    public String key() {
        return key;
    }

    public String suspectedModId() {
        return suspectedModId;
    }

    public int keyCode() {
        return keyCode;
    }

    public int count() {
        return count;
    }

    public Map<String, Integer> frames() {
        return frames;
    }

    public Map<String, Integer> callerMethods() {
        return callerMethods;
    }

    private static void increment(Map<String, Integer> map, String key) {
        map.merge(key, 1, Integer::sum);
    }
}
