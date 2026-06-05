package dev.cutexxgirl.fragmentdoctor.modules.scanner.report;

import dev.cutexxgirl.fragmentdoctor.modules.scanner.attribution.CallerAttribution;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GlErrorTracker {
    private final Map<String, GlErrorEvidence> errors = new LinkedHashMap<>();

    public synchronized void add(int error, String description, String section, CallerAttribution caller) {
        String safeDescription = description == null ? "unknown" : description;
        String safeSection = section == null ? "unknown" : section;
        String callerKey = caller == null ? "unknown" : caller.suspectKey("client_gl");
        String suspectedModId = caller == null || caller.suspectModId() == null ? "client_gl" : caller.suspectModId();
        String key = error + ":" + safeDescription + "@" + safeSection + "<-" + callerKey;
        errors.computeIfAbsent(key, ignored -> new GlErrorEvidence(error, safeDescription, safeSection, callerKey, suspectedModId)).add(caller);
    }

    public synchronized List<GlErrorEvidence> ranked() {
        return errors.values().stream()
                .sorted(Comparator.comparingInt(GlErrorEvidence::count).reversed())
                .toList();
    }
}
