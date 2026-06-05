package dev.cutexxgirl.fragmentdoctor.modules.scanner.report;

import dev.cutexxgirl.fragmentdoctor.modules.scanner.attribution.CallerAttribution;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InputPollTracker {
    private final Map<String, InputPollEvidence> entries = new LinkedHashMap<>();

    public synchronized void add(int keyCode, CallerAttribution caller) {
        String modId = caller.suspectModId();
        if (modId == null || modId.isBlank()) {
            modId = "unknown";
        }
        String key = modId + ":" + caller.className() + " -> key:" + keyCode;
        String finalModId = modId;
        entries.computeIfAbsent(key, ignored -> new InputPollEvidence(key, finalModId, keyCode)).add(caller);
    }

    public synchronized List<InputPollEvidence> ranked() {
        return entries.values().stream()
                .sorted(Comparator.comparingInt(InputPollEvidence::count).reversed())
                .toList();
    }
}
