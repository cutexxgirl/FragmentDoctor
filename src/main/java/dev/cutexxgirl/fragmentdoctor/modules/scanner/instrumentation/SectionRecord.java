package dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation;

import dev.cutexxgirl.fragmentdoctor.modules.scanner.attribution.CallerAttribution;

public record SectionRecord(String eventType, String section, ObservedTarget target, CallerAttribution caller, long durationNanos) {
    public double durationMillis() {
        return durationNanos / 1_000_000.0D;
    }
}
