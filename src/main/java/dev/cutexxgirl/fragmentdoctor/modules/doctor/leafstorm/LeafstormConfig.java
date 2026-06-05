package dev.cutexxgirl.fragmentdoctor.modules.doctor.leafstorm;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import dev.cutexxgirl.fragmentdoctor.FragmentDoctor;
import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;
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
        return VALUES.schedulerEnabled.get();
    }

    public static void setEnabled(boolean enabled) {
        refreshMutableValuesFromDisk();
        VALUES.schedulerEnabled.set(enabled);
        if (enabled) {
            VALUES.schedulerShadowMode.set(false);
        }
        SPEC.save();
    }

    public static void setCreateSmootherEnabled(boolean enabled) {
        refreshMutableValuesFromDisk();
        VALUES.createSmootherEnabled.set(enabled);
        SPEC.save();
    }

    private static void refreshMutableValuesFromDisk() {
        Path path = FMLPaths.CONFIGDIR.get().resolve("fragmentdoctor-common.toml");
        if (!path.toFile().isFile()) {
            return;
        }
        try (CommentedFileConfig config = CommentedFileConfig.builder(path).sync().build()) {
            config.load();
            refreshBoolean(config, "doctor.leafstorm.scheduler.enabled", VALUES.schedulerEnabled);
            refreshBoolean(config, "doctor.leafstorm.scheduler.shadowMode", VALUES.schedulerShadowMode);
            refreshBoolean(config, "doctor.leafstorm.scheduler.interceptVanillaLeafSchedules", VALUES.schedulerInterceptVanillaLeafSchedules);
            refreshBoolean(config, "doctor.leafstorm.createSmoother.enabled", VALUES.createSmootherEnabled);
        } catch (RuntimeException exception) {
            FragmentDoctor.LOGGER.warn("Failed to refresh Fragment Doctor config from disk before saving {}", path, exception);
        }
    }

    private static void refreshBoolean(CommentedFileConfig config, String path, ModConfigSpec.BooleanValue value) {
        Object current = config.get(path);
        if (current instanceof Boolean booleanValue) {
            value.set(booleanValue);
        }
    }

    public static final class Values {
        public final ModConfigSpec.BooleanValue consoleLogging;
        public final ModConfigSpec.BooleanValue fileLogging;
        public final ModConfigSpec.BooleanValue schedulerEnabled;
        public final ModConfigSpec.BooleanValue schedulerShadowMode;
        public final ModConfigSpec.BooleanValue schedulerInterceptVanillaLeafSchedules;
        public final ModConfigSpec.IntValue schedulerGlobalBudget;
        public final ModConfigSpec.IntValue schedulerScanBudget;
        public final ModConfigSpec.IntValue schedulerPerChunkBudget;
        public final ModConfigSpec.DoubleValue schedulerMaxDrainMillisPerTick;
        public final ModConfigSpec.DoubleValue schedulerHealthyMsptThreshold;
        public final ModConfigSpec.DoubleValue schedulerSoftMsptThreshold;
        public final ModConfigSpec.DoubleValue schedulerHardMsptThreshold;
        public final ModConfigSpec.IntValue schedulerMaxJobs;
        public final ModConfigSpec.IntValue schedulerJobTtlTicks;
        public final ModConfigSpec.IntValue schedulerHotChunkScheduleThreshold;
        public final ModConfigSpec.IntValue schedulerHotChunkDelayTicks;
        public final ModConfigSpec.IntValue schedulerHotChunkMaxDelayTicks;
        public final ModConfigSpec.IntValue schedulerLogEveryTicks;
        public final ModConfigSpec.BooleanValue createSmootherEnabled;
        public final ModConfigSpec.IntValue createSmootherBlockEntityBudget;
        public final ModConfigSpec.IntValue createSmootherEntityBudget;
        public final ModConfigSpec.IntValue createSmootherWarmupTicks;
        public final ModConfigSpec.IntValue createSmootherMaxConsecutiveSkips;
        public final ModConfigSpec.DoubleValue createSmootherMsptThreshold;
        public final ModConfigSpec.IntValue createSmootherLogEveryTicks;

        private Values(ModConfigSpec.Builder builder) {
            builder.push("doctor");
            builder.push("leafstorm");
            consoleLogging = builder
                    .comment("Write leafstorm scheduler summaries to the Fragment Doctor logger.")
                    .define("consoleLogging", true);
            fileLogging = builder
                    .comment("Write JSONL leafstorm scheduler summaries under fragmentdoctor/doctor/leafstorm.")
                    .define("fileLogging", true);
            builder.push("scheduler");
            schedulerEnabled = builder
                    .comment("Enable the replacement scheduler for leaf scheduled ticks.")
                    .define("enabled", false);
            schedulerShadowMode = builder
                    .comment("When true, record what would be intercepted but leave vanilla scheduling untouched.")
                    .define("shadowMode", false);
            schedulerInterceptVanillaLeafSchedules = builder
                    .comment("Intercept LeavesBlock scheduled ticks at LevelTicks.schedule when the replacement scheduler is enabled.")
                    .define("interceptVanillaLeafSchedules", true);
            schedulerGlobalBudget = builder
                    .comment("Normal global leaf scheduler jobs executed per server tick.")
                    .defineInRange("globalBudget", 128, 0, 65_536);
            schedulerScanBudget = builder
                    .comment("Maximum scheduler heap poll/drop/skip operations per server tick. This is separate from execution budget.")
                    .defineInRange("scanBudget", 1024, 1, 262_144);
            schedulerPerChunkBudget = builder
                    .comment("Maximum replacement scheduler leaf jobs executed from one chunk per server tick.")
                    .defineInRange("perChunkBudget", 4, 1, 65_536);
            schedulerMaxDrainMillisPerTick = builder
                    .comment("Soft wall-clock budget, in milliseconds, for replacement scheduler drain work during one server tick.")
                    .defineInRange("maxDrainMillisPerTick", 2.0D, 0.0D, 1000.0D);
            schedulerHealthyMsptThreshold = builder
                    .comment("Below this previous-tick MSPT, the scheduler may use recovery budget.")
                    .defineInRange("healthyMsptThreshold", 30.0D, 1.0D, 5000.0D);
            schedulerSoftMsptThreshold = builder
                    .comment("At or above this previous-tick MSPT, the scheduler slows down.")
                    .defineInRange("softMsptThreshold", 45.0D, 1.0D, 5000.0D);
            schedulerHardMsptThreshold = builder
                    .comment("At or above this previous-tick MSPT, the scheduler pauses or drains only a tiny amount.")
                    .defineInRange("hardMsptThreshold", 70.0D, 1.0D, 5000.0D);
            schedulerMaxJobs = builder
                    .comment("Maximum canonical leaf jobs retained across all replacement scheduler queues.")
                    .defineInRange("maxJobs", 500_000, 1_000, 2_000_000);
            schedulerJobTtlTicks = builder
                    .comment("Drop replacement scheduler jobs older than this many game ticks.")
                    .defineInRange("jobTtlTicks", 20 * 60, 20, 20 * 60 * 30);
            schedulerHotChunkScheduleThreshold = builder
                    .comment("If one chunk tries to schedule at least this many leaf ticks in one server tick, debounce later jobs from that chunk.")
                    .defineInRange("hotChunkScheduleThreshold", 256, 1, 65_536);
            schedulerHotChunkDelayTicks = builder
                    .comment("Base delay applied to new or repeated jobs from a hot chunk.")
                    .defineInRange("hotChunkDelayTicks", 20, 1, 20 * 60);
            schedulerHotChunkMaxDelayTicks = builder
                    .comment("Maximum adaptive delay applied to extremely hot chunks.")
                    .defineInRange("hotChunkMaxDelayTicks", 20 * 10, 1, 20 * 60 * 10);
            schedulerLogEveryTicks = builder
                    .comment("Minimum server ticks between repeated replacement scheduler summary log lines.")
                    .defineInRange("logEveryTicks", 100, 1, 20 * 60);
            builder.pop();
            builder.push("createSmoother");
            createSmootherEnabled = builder
                    .comment("Smooth newly loaded Create block entities and contraption entities by skipping some early ticks under MSPT pressure.")
                    .define("enabled", false);
            createSmootherBlockEntityBudget = builder
                    .comment("Maximum newly loaded Create block entity ticks allowed per server tick while smoothing.")
                    .defineInRange("blockEntityBudget", 32, 0, 65_536);
            createSmootherEntityBudget = builder
                    .comment("Maximum newly loaded Create entity ticks allowed per server tick while smoothing.")
                    .defineInRange("entityBudget", 8, 0, 65_536);
            createSmootherWarmupTicks = builder
                    .comment("Only Create objects first seen within this many server ticks are smoothed.")
                    .defineInRange("warmupTicks", 20 * 10, 1, 20 * 60 * 10);
            createSmootherMaxConsecutiveSkips = builder
                    .comment("Force one real tick after this many skipped ticks for the same Create object.")
                    .defineInRange("maxConsecutiveSkips", 4, 0, 200);
            createSmootherMsptThreshold = builder
                    .comment("Only smooth Create ticks when previous server tick MSPT is at least this value.")
                    .defineInRange("msptThreshold", 35.0D, 1.0D, 5000.0D);
            createSmootherLogEveryTicks = builder
                    .comment("Minimum server ticks between repeated Create smoother summary log lines.")
                    .defineInRange("logEveryTicks", 100, 1, 20 * 60);
            builder.pop();
            builder.pop();
            builder.pop();
        }
    }
}
