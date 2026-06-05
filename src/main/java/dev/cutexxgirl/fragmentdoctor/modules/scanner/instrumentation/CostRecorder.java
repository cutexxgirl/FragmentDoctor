package dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation;

import dev.cutexxgirl.fragmentdoctor.modules.scanner.ScannerRuntime;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.attribution.AttributionResolver;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.attribution.CallerAttribution;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventListener;

public final class CostRecorder {
    public static final String ORIGIN_VANILLA_SCHEDULE = "vanilla_schedule";
    public static final String ORIGIN_VANILLA_LEAF_INTERCEPTED = "vanilla_leaf_intercepted";
    private static final long AI_THRESHOLD_NANOS = 250_000L;
    private static final long ENTITY_QUERY_THRESHOLD_NANOS = 250_000L;
    private static final long EVENT_LISTENER_THRESHOLD_NANOS = 500_000L;
    private static final long RENDER_SECTION_THRESHOLD_NANOS = 2_000_000L;
    private static final long CLIENT_FRAME_STACK_THRESHOLD_NANOS = 33_000_000L;
    private static final int EARLY_ATTRIBUTION_SAMPLES = 4;
    private static final int ATTRIBUTION_SAMPLE_INTERVAL = 128;
    private static final int SCHEDULED_TICK_STACK_SAMPLE_INTERVAL = 4096;
    private static final ThreadLocal<Mob> CURRENT_AI_OWNER = new ThreadLocal<>();
    private static final ThreadLocal<String> SCHEDULE_ORIGIN = new ThreadLocal<>();
    private static final ConcurrentHashMap<String, AttributionSample> ATTRIBUTION_SAMPLES = new ConcurrentHashMap<>();

    private CostRecorder() {
    }

    public static boolean active() {
        return ScannerRuntime.sessions().isActive();
    }

    public static void record(String eventType, String section, ObservedTarget target, long durationNanos) {
        if (!active()) {
            return;
        }
        ScannerRuntime.sessions().recordSection(new SectionRecord(
                eventType,
                section,
                target,
                AttributionResolver.capture(target.ownerModId()),
                durationNanos
        ));
    }

    public static void pushScheduledTickOrigin(String origin) {
        SCHEDULE_ORIGIN.set(origin);
    }

    public static void popScheduledTickOrigin() {
        SCHEDULE_ORIGIN.remove();
    }

    public static String scheduledTickOrigin() {
        String origin = SCHEDULE_ORIGIN.get();
        return origin == null || origin.isBlank() ? ORIGIN_VANILLA_SCHEDULE : origin;
    }

    public static void recordScheduledTick(Object type, BlockPos pos) {
        recordScheduledTick(type, pos, scheduledTickOrigin());
    }

    public static void recordScheduledTick(Object type, BlockPos pos, String origin) {
        if (!active()) {
            return;
        }
        ObservedTarget target = ObservedTarget.scheduledTick(type, pos);
        String normalizedOrigin = origin == null || origin.isBlank() ? ORIGIN_VANILLA_SCHEDULE : origin;
        ScannerRuntime.sessions().recordScheduledTick(target, scheduledTickAttribution(target, normalizedOrigin), normalizedOrigin);
    }

    public static void recordGlError(int error, String description, String section) {
        if (!active()) {
            return;
        }
        String key = "gl:" + error + ":" + section;
        ScannerRuntime.sessions().recordGlError(error, description, section, cachedAttribution(key, "client_gl", ATTRIBUTION_SAMPLE_INTERVAL));
    }

    public static void recordInvalidKeyPoll(long window, int keyCode) {
        if (!active()) {
            return;
        }
        String key = "input:key:" + keyCode;
        ScannerRuntime.sessions().recordInvalidKeyPoll(keyCode, cachedAttribution(key, "client_input", ATTRIBUTION_SAMPLE_INTERVAL));
    }

    public static void pushAiOwner(Mob owner) {
        if (active()) {
            CURRENT_AI_OWNER.set(owner);
        }
    }

    public static void popAiOwner(Mob owner) {
        if (active() && CURRENT_AI_OWNER.get() == owner) {
            CURRENT_AI_OWNER.remove();
        }
    }

    public static void recordGoal(WrappedGoal goal, String phase, long durationNanos) {
        Mob owner = CURRENT_AI_OWNER.get();
        if (!active() || owner == null || durationNanos < AI_THRESHOLD_NANOS) {
            return;
        }
        String goalClass = goal.getGoal().getClass().getName();
        ScannerRuntime.sessions().recordSection(new SectionRecord(
                "ai_goal_cost",
                phase,
                ObservedTarget.goal(goal, owner, phase),
                AttributionResolver.fromClassName(goalClass, phase, ownerModId(owner), compactStack()),
                durationNanos
        ));
    }

    public static void recordBrainBehavior(BehaviorControl<?> behavior, LivingEntity owner, String phase, long durationNanos) {
        if (!active() || durationNanos < AI_THRESHOLD_NANOS) {
            return;
        }
        String behaviorClass = behavior.getClass().getName();
        ScannerRuntime.sessions().recordSection(new SectionRecord(
                "brain_behavior_cost",
                phase,
                ObservedTarget.brainBehavior(behavior, owner, phase),
                AttributionResolver.fromClassName(behaviorClass, phase, ownerModId(owner), compactStack()),
                durationNanos
        ));
    }

    public static void recordBrainSensor(Sensor<?> sensor, LivingEntity owner, long durationNanos) {
        if (!active() || durationNanos < AI_THRESHOLD_NANOS) {
            return;
        }
        String sensorClass = sensor.getClass().getName();
        ScannerRuntime.sessions().recordSection(new SectionRecord(
                "brain_sensor_cost",
                "sensor_tick",
                ObservedTarget.brainSensor(sensor, owner),
                AttributionResolver.fromClassName(sensorClass, "tick", ownerModId(owner), compactStack()),
                durationNanos
        ));
    }

    public static void recordNavigation(PathNavigation navigation, Mob owner, long durationNanos) {
        if (!active() || durationNanos < AI_THRESHOLD_NANOS) {
            return;
        }
        String navigationClass = navigation.getClass().getName();
        ScannerRuntime.sessions().recordSection(new SectionRecord(
                "navigation_cost",
                "navigation_tick",
                ObservedTarget.navigation(navigation, owner),
                AttributionResolver.fromClassName(navigationClass, "tick", ownerModId(owner), compactStack()),
                durationNanos
        ));
    }

    public static void recordEntityQuery(Level level, AABB bounds, int resultCount, String queryKind, long durationNanos) {
        if (!active() || durationNanos < ENTITY_QUERY_THRESHOLD_NANOS) {
            return;
        }
        Mob aiOwner = CURRENT_AI_OWNER.get();
        ResourceLocation aiOwnerType = aiOwner == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(aiOwner.getType());
        String context = aiOwner == null ? null : "ai_owner=" + aiOwnerType + ", uuid=" + aiOwner.getUUID() + ", pos=" + aiOwner.blockPosition();
        ScannerRuntime.sessions().recordSection(new SectionRecord(
                "entity_query_cost",
                queryKind,
                ObservedTarget.entityQuery(level, bounds, resultCount, queryKind, context),
                AttributionResolver.capture("entity_query"),
                durationNanos
        ));
    }

    public static void recordEventListener(Event event, EventListener listener, long durationNanos) {
        if (!active() || durationNanos < EVENT_LISTENER_THRESHOLD_NANOS) {
            return;
        }
        String readable = listener == null ? "unknown" : EventListenerMetadata.readable(listener);
        ScannerRuntime.sessions().recordSection(new SectionRecord(
                "event_listener_cost",
                event == null ? "unknown_event" : event.getClass().getName(),
                ObservedTarget.eventListener(event, listener),
                EventListenerMetadata.attribution(listener, readable),
                durationNanos
        ));
    }

    public static void recordRenderSection(String section, long durationNanos) {
        if (!active() || durationNanos < RENDER_SECTION_THRESHOLD_NANOS) {
            return;
        }
        ScannerRuntime.sessions().recordSection(new SectionRecord(
                "render_cost",
                section,
                ObservedTarget.client(section, "client render section"),
                AttributionResolver.capture("client_render"),
                durationNanos
        ));
    }

    public static void recordClientFrame(String section, long durationNanos, int reportedFps, String screenName, boolean inWorld) {
        if (!active()) {
            return;
        }
        List<String> stack = durationNanos >= CLIENT_FRAME_STACK_THRESHOLD_NANOS ? compactStack() : List.of();
        ScannerRuntime.sessions().recordClientFrame(section, durationNanos, reportedFps, screenName, inWorld, stack);
    }

    private static CallerAttribution scheduledTickAttribution(ObservedTarget target, String origin) {
        String key = "scheduled:" + origin + ":" + target.kind() + ":" + target.id();
        AttributionSample sample = ATTRIBUTION_SAMPLES.computeIfAbsent(key, ignored -> new AttributionSample());
        int count = sample.count.incrementAndGet();
        List<String> stack = List.of();
        if (sample.last == null || count <= EARLY_ATTRIBUTION_SAMPLES || count % SCHEDULED_TICK_STACK_SAMPLE_INTERVAL == 0) {
            CallerAttribution captured = AttributionResolver.capture(target.ownerModId());
            sample.last = captured;
            stack = captured.stack();
        }
        return new CallerAttribution(target.ownerModId(), "scheduled_tick", origin, stack);
    }

    private static CallerAttribution cachedAttribution(String key, String fallbackModId, int sampleInterval) {
        AttributionSample sample = ATTRIBUTION_SAMPLES.computeIfAbsent(key, ignored -> new AttributionSample());
        int count = sample.count.incrementAndGet();
        CallerAttribution current = sample.last;
        if (current == null || count <= EARLY_ATTRIBUTION_SAMPLES || count % sampleInterval == 0) {
            current = AttributionResolver.capture(fallbackModId);
            sample.last = current;
        }
        return current;
    }

    private static List<String> compactStack() {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        List<String> stack = new ArrayList<>();
        for (StackTraceElement frame : trace) {
            String className = frame.getClassName();
            if (className.startsWith("java.lang.Thread") || className.startsWith("dev.cutexxgirl.fragmentdoctor.modules.scanner.")) {
                continue;
            }
            stack.add(className + "#" + frame.getMethodName() + ":" + frame.getLineNumber());
            if (stack.size() >= 24) {
                break;
            }
        }
        return stack;
    }

    private static String ownerModId(LivingEntity owner) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(owner.getType());
        return key == null ? "unknown" : key.getNamespace();
    }

    private static final class AttributionSample {
        private final AtomicInteger count = new AtomicInteger();
        private volatile CallerAttribution last;
    }
}
