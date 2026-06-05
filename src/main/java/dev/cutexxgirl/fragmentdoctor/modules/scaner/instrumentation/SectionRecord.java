package dev.cutexxgirl.fragmentdoctor.modules.scaner.instrumentation;

import dev.cutexxgirl.fragmentdoctor.modules.scaner.attribution.CallerAttribution;

public record SectionRecord(String eventType, String section, ObservedTarget target, CallerAttribution caller, long durationNanos) {
    public double durationMillis() {
        return durationNanos / 1_000_000.0D;
    }
}
