package dev.cutexxgirl.fragmentdoctor.modules.scaner.report;

import dev.cutexxgirl.fragmentdoctor.modules.scaner.attribution.CallerAttribution;
import dev.cutexxgirl.fragmentdoctor.modules.scaner.instrumentation.ObservedTarget;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ScheduledTickTracker {
    private final Map<String, ScheduleEvidence> entries = new LinkedHashMap<>();

    public void add(ObservedTarget target, CallerAttribution caller, String origin) {
        String normalizedOrigin = origin == null || origin.isBlank() ? "vanilla_schedule" : origin;
        String fallback = target.ownerModId();
        String key = normalizedOrigin + " | " + caller.suspectKey(fallback) + " -> " + target.kind() + ":" + target.id();
        String modId = caller.suspectModId() == null || caller.suspectModId().isBlank() ? fallback : caller.suspectModId();
        if (modId == null || modId.isBlank()) {
            modId = "unknown";
        }
        String finalModId = modId;
        entries.computeIfAbsent(key, ignored -> new ScheduleEvidence(key, normalizedOrigin, finalModId)).add(target, caller);
    }

    public List<ScheduleEvidence> ranked() {
        return entries.values().stream()
                .sorted(Comparator.comparingInt(ScheduleEvidence::count).reversed())
                .toList();
    }
}
