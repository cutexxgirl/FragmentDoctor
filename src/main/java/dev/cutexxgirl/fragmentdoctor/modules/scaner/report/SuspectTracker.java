package dev.cutexxgirl.fragmentdoctor.modules.scaner.report;

import dev.cutexxgirl.fragmentdoctor.modules.scaner.instrumentation.SectionRecord;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SuspectTracker {
    private final Map<String, SuspectEvidence> suspects = new LinkedHashMap<>();

    public synchronized void add(SectionRecord record) {
        String fallback = record.target().ownerModId();
        String key = record.caller().suspectKey(fallback);
        String modId = record.caller().suspectModId() == null ? fallback : record.caller().suspectModId();
        if (modId == null || modId.isBlank()) {
            modId = "unknown";
        }
        String finalModId = modId;
        suspects.computeIfAbsent(key, ignored -> new SuspectEvidence(key, finalModId)).add(record);
    }

    public synchronized List<SuspectEvidence> ranked() {
        return suspects.values().stream()
                .sorted(Comparator.comparingLong(SuspectEvidence::totalNanos).reversed())
                .toList();
    }
}
