package dev.cutexxgirl.fragmentdoctor.modules.scanner.report;

import java.util.LinkedHashMap;
import java.util.Map;

public record PendingTickSnapshot(
        String dimension,
        String queueKind,
        int totalCount,
        int containerCount,
        int sampledCount,
        boolean completeTypeSample,
        String status,
        Map<String, Integer> sampledTypes,
        Map<String, Integer> exactChunks,
        Map<String, Integer> sampledDueBuckets
) {
    public PendingTickSnapshot {
        status = status == null || status.isBlank() ? "ok" : status;
        sampledTypes = new LinkedHashMap<>(sampledTypes);
        exactChunks = new LinkedHashMap<>(exactChunks);
        sampledDueBuckets = new LinkedHashMap<>(sampledDueBuckets);
    }

    public double sampleCoverage() {
        return totalCount <= 0 ? 1.0D : Math.min(1.0D, sampledCount / (double)totalCount);
    }
}
