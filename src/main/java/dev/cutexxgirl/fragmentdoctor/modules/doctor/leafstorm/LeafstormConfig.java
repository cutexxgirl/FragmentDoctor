package dev.cutexxgirl.fragmentdoctor.modules.doctor.leafstorm;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public final class LeafstormConfig {
    public static final Values VALUES;
    public static final ModConfigSpec SPEC;

    static {
        Pair<Values, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Values::new);
        VALUES = pair.getLeft();
        SPEC = pair.getRight();
    }

    private LeafstormConfig() {
    }

    public static boolean enabled() {
        return VALUES.enabled.get();
    }

    public static void setEnabled(boolean enabled) {
        VALUES.enabled.set(enabled);
        SPEC.save();
    }

    public static final class Values {
        public final ModConfigSpec.BooleanValue enabled;
        public final ModConfigSpec.DoubleValue softMsptThreshold;
        public final ModConfigSpec.DoubleValue hardMsptThreshold;
        public final ModConfigSpec.DoubleValue emergencyMsptThreshold;
        public final ModConfigSpec.IntValue emergencyQueueDepth;
        public final ModConfigSpec.IntValue softBudget;
        public final ModConfigSpec.IntValue hardBudget;
        public final ModConfigSpec.IntValue emergencyBudget;
        public final ModConfigSpec.IntValue deferDelayTicks;
        public final ModConfigSpec.BooleanValue enableDeduplication;
        public final ModConfigSpec.IntValue maxDeferredDedupKeys;
        public final ModConfigSpec.IntValue dedupGraceTicks;
        public final ModConfigSpec.BooleanValue queueRecoveryAllowsHardBudget;
        public final ModConfigSpec.IntValue logEveryTicks;
        public final ModConfigSpec.BooleanValue consoleLogging;
        public final ModConfigSpec.BooleanValue fileLogging;

        private Values(ModConfigSpec.Builder builder) {
            builder.push("doctor");
            builder.push("leafstorm");
            enabled = builder
                    .comment("Master switch for the scheduled leaf tick limiter. Disabled by default.")
                    .define("enabled", false);
            softMsptThreshold = builder
                    .comment("Start soft limiting when the previous server tick MSPT is at least this value.")
                    .defineInRange("softMsptThreshold", 50.0D, 1.0D, 5000.0D);
            hardMsptThreshold = builder
                    .comment("Use the hard budget when the previous server tick MSPT is at least this value.")
                    .defineInRange("hardMsptThreshold", 100.0D, 1.0D, 5000.0D);
            emergencyMsptThreshold = builder
                    .comment("Use the emergency budget when the previous server tick MSPT is at least this value.")
                    .defineInRange("emergencyMsptThreshold", 200.0D, 1.0D, 5000.0D);
            emergencyQueueDepth = builder
                    .comment("Use emergency queue handling when the scheduled block tick queue reaches this size.")
                    .defineInRange("emergencyQueueDepth", 50_000, 1, 1_000_000);
            softBudget = builder
                    .comment("Leaf scheduled ticks allowed per server tick in soft mode.")
                    .defineInRange("softBudget", 2_000, 1, 65_536);
            hardBudget = builder
                    .comment("Leaf scheduled ticks allowed per server tick in hard mode.")
                    .defineInRange("hardBudget", 500, 1, 65_536);
            emergencyBudget = builder
                    .comment("Leaf scheduled ticks allowed per server tick in emergency mode.")
                    .defineInRange("emergencyBudget", 250, 1, 65_536);
            deferDelayTicks = builder
                    .comment("How many game ticks to delay a leaf tick after the limiter defers it.")
                    .defineInRange("deferDelayTicks", 20, 1, 20 * 60);
            enableDeduplication = builder
                    .comment("Drop duplicate deferred leaf ticks that are already scheduled for a future game tick.")
                    .define("enableDeduplication", true);
            maxDeferredDedupKeys = builder
                    .comment("Maximum number of deferred leaf dedup keys retained by the doctor.")
                    .defineInRange("maxDeferredDedupKeys", 250_000, 1_000, 2_000_000);
            dedupGraceTicks = builder
                    .comment("Small future-tick grace window used when checking deferred leaf duplicate keys.")
                    .defineInRange("dedupGraceTicks", 1, 0, 20);
            queueRecoveryAllowsHardBudget = builder
                    .comment("When the queue is large but clearly shrinking and MSPT is healthy, use hard budget instead of emergency budget.")
                    .define("queueRecoveryAllowsHardBudget", true);
            logEveryTicks = builder
                    .comment("Minimum server ticks between repeated limiter summary log lines.")
                    .defineInRange("logEveryTicks", 100, 1, 20 * 60);
            consoleLogging = builder
                    .comment("Write limiter summaries to the Fragment Doctor logger.")
                    .define("consoleLogging", true);
            fileLogging = builder
                    .comment("Write JSONL limiter summaries under fragmentdoctor/doctor/leafstorm.")
                    .define("fileLogging", true);
            builder.pop();
            builder.pop();
        }
    }
}
