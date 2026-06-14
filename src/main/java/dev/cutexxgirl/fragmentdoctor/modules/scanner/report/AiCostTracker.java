package dev.cutexxgirl.fragmentdoctor.modules.scanner.report;

import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.SectionRecord;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AiCostTracker {
    private final Map<String, AiCostEvidence> costs = new LinkedHashMap<>();

    public synchronized void add(SectionRecord record) {
        String fallback = record.target().ownerModId();
        String key = record.caller().suspectKey(fallback);
        String modId = record.caller().suspectModId() == null ? fallback : record.caller().suspectModId();
        if (modId == null || modId.isBlank()) {
            modId = "unknown";
        }
        String finalModId = modId;
        costs.computeIfAbsent(key, ignored -> new AiCostEvidence(key, finalModId)).add(record);
    }

    public synchronized List<AiCostEvidence> ranked() {
        return costs.values().stream()
                .sorted(Comparator.comparingDouble(AiCostEvidence::confidence).reversed()
                        .thenComparing(Comparator.comparingDouble(AiCostEvidence::totalMillis).reversed()))
                .toList();
    }
}
