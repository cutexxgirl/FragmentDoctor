package dev.cutexxgirl.fragmentdoctor.modules.scanner.report;

import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.SectionRecord;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EntityQueryTracker {
    private final Map<String, EntityQueryEvidence> queries = new LinkedHashMap<>();

    public synchronized void add(SectionRecord record) {
        String fallback = record.target().ownerModId();
        String key = record.caller().suspectKey(fallback);
        String modId = record.caller().suspectModId() == null ? fallback : record.caller().suspectModId();
        if (modId == null || modId.isBlank()) {
            modId = "unknown";
        }
        String finalModId = modId;
        queries.computeIfAbsent(key, ignored -> new EntityQueryEvidence(key, finalModId)).add(record);
    }

    public synchronized List<EntityQueryEvidence> ranked() {
        return queries.values().stream()
                .sorted(Comparator.comparingDouble(EntityQueryEvidence::confidence).reversed()
                        .thenComparing(Comparator.comparingDouble(EntityQueryEvidence::totalMillis).reversed()))
                .toList();
    }
}
