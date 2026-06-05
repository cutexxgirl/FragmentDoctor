package dev.cutexxgirl.fragmentdoctor.modules.scaner.report;

import dev.cutexxgirl.fragmentdoctor.modules.scaner.attribution.CallerAttribution;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GlErrorEvidence {
    private final int error;
    private final String description;
    private final String section;
    private final String callerKey;
    private final String suspectedModId;
    private final Map<String, Integer> frames = new LinkedHashMap<>();
    private int count;

    public GlErrorEvidence(int error, String description, String section, String callerKey, String suspectedModId) {
        this.error = error;
        this.description = description;
        this.section = section;
        this.callerKey = callerKey;
        this.suspectedModId = suspectedModId;
    }

    public void add(CallerAttribution caller) {
        count++;
        if (caller != null) {
            caller.stack().stream().limit(12).forEach(frame -> frames.merge(frame, 1, Integer::sum));
        }
    }

    public String key() {
        return error + ":" + description + "@" + section + " <- " + callerKey;
    }

    public String callerKey() {
        return callerKey;
    }

    public String suspectedModId() {
        return suspectedModId;
    }

    public int error() {
        return error;
    }

    public String description() {
        return description;
    }

    public String section() {
        return section;
    }

    public int count() {
        return count;
    }

    public Map<String, Integer> frames() {
        return frames;
    }
}
