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
    private static final long RENDER_SECTION_THRESHOLD_NANOS = 8_000_000L;
    private static final long SECTION_THRESHOLD_NANOS = 2_000_000L;
    private static final long ENTITY_THRESHOLD_NANOS = 1_000_000L;
    private static final long BLOCK_ENTITY_THRESHOLD_NANOS = 1_000_000L;
    private static final long LEVEL_TICK_THRESHOLD_NANOS = 50_000_000L;
    private static final long CHUNK_TICK_THRESHOLD_NANOS = 5_000_000L;
    private static final long CLIENT_FRAME_STACK_THRESHOLD_NANOS = 33_000_000L;
    private static final int EARLY_ATTRIBUTION_SAMPLES = 4;
    private static final int ATTRIBUTION_SAMPLE_INTERVAL = 128;
    private static final int SCHEDULED_TICK_STACK_SAMPLE_INTERVAL = 4096;
    private static final ThreadLocal<Mob> CURRENT_AI_OWNER = new ThreadLocal<>();
    private static final ThreadLocal<AiContext> CURRENT_AI_CONTEXT = new ThreadLocal<>();
    private static final ThreadLocal<String> SCHEDULE_ORIGIN = new ThreadLocal<>();
    private static final ConcurrentHashMap<String, AttributionSample> ATTRIBUTION_SAMPLES = new ConcurrentHashMap<>();

    private CostRecorder() {
    }

    public static boolean active() {
        return ScannerRuntime.sessions().isActive();
    }

    public static void record(String eventType, String section, ObservedTarget target, long durationNanos) {
        if (!active() || !shouldRecord(eventType, section, durationNanos)) {
            return;
        }
        long overheadStart = ScannerSelfOverhead.start();
        try {
            ScannerRuntime.sessions().recordSection(new SectionRecord(
                    eventType,
                    section,
                    target,
                    AttributionResolver.capture(target.ownerModId()),
                    durationNanos
            ));
        } finally {
            ScannerSelfOverhead.end("record_section", overheadStart);
        }
    }

    public static boolean shouldRecord(String eventType, String section, long durationNanos) {
        if ("level_tick".equals(section)) {
            return durationNanos >= LEVEL_TICK_THRESHOLD_NANOS;
        }
        if ("chunk_tick".equals(section)) {
            return durationNanos >= CHUNK_TICK_THRESHOLD_NANOS;
        }
        return switch (eventType) {
            case "entity_cost" -> durationNanos >= ENTITY_THRESHOLD_NANOS;
            case "block_entity_cost" -> durationNanos >= BLOCK_ENTITY_THRESHOLD_NANOS;
            case "ai_goal_cost", "brain_behavior_cost", "brain_sensor_cost", "navigation_cost" -> durationNanos >= AI_THRESHOLD_NANOS;
            case "entity_query_cost" -> durationNanos >= ENTITY_QUERY_THRESHOLD_NANOS;
            case "event_listener_cost" -> durationNanos >= EVENT_LISTENER_THRESHOLD_NANOS;
            case "render_cost" -> durationNanos >= RENDER_SECTION_THRESHOLD_NANOS;
            default -> durationNanos >= SECTION_THRESHOLD_NANOS;
        };
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
        long overheadStart = ScannerSelfOverhead.start();
        try {
            ObservedTarget target = ObservedTarget.scheduledTick(type, pos);
            String normalizedOrigin = origin == null || origin.isBlank() ? ORIGIN_VANILLA_SCHEDULE : origin;
            ScannerRuntime.sessions().recordScheduledTick(target, scheduledTickAttribution(target, normalizedOrigin), normalizedOrigin);
        } finally {
            ScannerSelfOverhead.end("scheduled_tick_capture", overheadStart);
        }
    }

    public static void recordGlError(int error, String description, String section) {
        if (!active()) {
            return;
        }
        long overheadStart = ScannerSelfOverhead.start();
        try {
            String key = "gl:" + error + ":" + section;
            ScannerRuntime.sessions().recordGlError(error, description, section, cachedAttribution(key, "client_gl", ATTRIBUTION_SAMPLE_INTERVAL));
        } finally {
            ScannerSelfOverhead.end("gl_error_capture", overheadStart);
        }
    }

    public static void recordInvalidKeyPoll(long window, int keyCode) {
        if (!active()) {
            return;
        }
        long overheadStart = ScannerSelfOverhead.start();
        try {
            String key = "input:key:" + keyCode;
            ScannerRuntime.sessions().recordInvalidKeyPoll(keyCode, cachedAttribution(key, "client_input", ATTRIBUTION_SAMPLE_INTERVAL));
        } finally {
            ScannerSelfOverhead.end("input_capture", overheadStart);
        }
    }

    public static void pushAiOwner(Mob owner) {
        if (active()) {
            CURRENT_AI_OWNER.set(owner);
        }
    }

    public static void popAiOwner(Mob owner) {
        if (CURRENT_AI_OWNER.get() == owner) {
            CURRENT_AI_CONTEXT.remove();
            CURRENT_AI_OWNER.remove();
        }
    }

    public static void pushGoalContext(WrappedGoal goal, String phase) {
        if (!active()) {
            return;
        }
        Mob owner = CURRENT_AI_OWNER.get();
        if (owner == null || goal == null) {
            return;
        }
        CURRENT_AI_CONTEXT.set(AiContext.goal(goal.getGoal().getClass().getName(), phase, goal.getPriority(), owner));
    }

    public static void popGoalContext(WrappedGoal goal) {
        CURRENT_AI_CONTEXT.remove();
    }

    public static void pushBrainContext(Object component, LivingEntity owner, String phase) {
        if (!active() || component == null || owner == null) {
            return;
        }
        CURRENT_AI_CONTEXT.set(AiContext.brain(component.getClass().getName(), phase, owner));
    }

    public static void popBrainContext(Object component) {
        CURRENT_AI_CONTEXT.remove();
    }

    public static void recordGoal(WrappedGoal goal, String phase, long durationNanos) {
        Mob owner = CURRENT_AI_OWNER.get();
        if (!active() || owner == null || durationNanos < AI_THRESHOLD_NANOS) {
            return;
        }
        long overheadStart = ScannerSelfOverhead.start();
        try {
            String goalClass = goal.getGoal().getClass().getName();
            ScannerRuntime.sessions().recordSection(new SectionRecord(
                    "ai_goal_cost",
                    phase,
                    ObservedTarget.goal(goal, owner, phase),
                    AttributionResolver.fromClassName(goalClass, phase, ownerModId(owner), compactStack()),
                    durationNanos
            ));
        } finally {
            ScannerSelfOverhead.end("ai_goal_capture", overheadStart);
        }
    }

    public static void recordBrainBehavior(BehaviorControl<?> behavior, LivingEntity owner, String phase, long durationNanos) {
        if (!active() || durationNanos < AI_THRESHOLD_NANOS) {
            return;
        }
        long overheadStart = ScannerSelfOverhead.start();
        try {
            String behaviorClass = behavior.getClass().getName();
            ScannerRuntime.sessions().recordSection(new SectionRecord(
                    "brain_behavior_cost",
                    phase,
                    ObservedTarget.brainBehavior(behavior, owner, phase),
                    AttributionResolver.fromClassName(behaviorClass, phase, ownerModId(owner), compactStack()),
                    durationNanos
            ));
        } finally {
            ScannerSelfOverhead.end("brain_behavior_capture", overheadStart);
        }
    }

    public static void recordBrainSensor(Sensor<?> sensor, LivingEntity owner, long durationNanos) {
        if (!active() || durationNanos < AI_THRESHOLD_NANOS) {
            return;
        }
        long overheadStart = ScannerSelfOverhead.start();
        try {
            String sensorClass = sensor.getClass().getName();
            ScannerRuntime.sessions().recordSection(new SectionRecord(
                    "brain_sensor_cost",
                    "sensor_tick",
                    ObservedTarget.brainSensor(sensor, owner),
                    AttributionResolver.fromClassName(sensorClass, "tick", ownerModId(owner), compactStack()),
                    durationNanos
            ));
        } finally {
            ScannerSelfOverhead.end("brain_sensor_capture", overheadStart);
        }
    }

    public static void recordNavigation(PathNavigation navigation, Mob owner, long durationNanos) {
        if (!active() || durationNanos < AI_THRESHOLD_NANOS) {
            return;
        }
        long overheadStart = ScannerSelfOverhead.start();
        try {
            String navigationClass = navigation.getClass().getName();
            ScannerRuntime.sessions().recordSection(new SectionRecord(
                    "navigation_cost",
                    "navigation_tick",
                    ObservedTarget.navigation(navigation, owner, currentAiContextDetail()),
                    AttributionResolver.fromClassName(navigationClass, "tick", ownerModId(owner), compactStack()),
                    durationNanos
            ));
        } finally {
            ScannerSelfOverhead.end("navigation_capture", overheadStart);
        }
    }

    public static void recordEntityQuery(Level level, AABB bounds, int resultCount, String queryKind, long durationNanos) {
        if (!active() || durationNanos < ENTITY_QUERY_THRESHOLD_NANOS) {
            return;
        }
        long overheadStart = ScannerSelfOverhead.start();
        try {
            Mob aiOwner = CURRENT_AI_OWNER.get();
            ResourceLocation aiOwnerType = aiOwner == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(aiOwner.getType());
            String context = aiOwner == null ? null : "ai_owner=" + aiOwnerType + ", uuid=" + aiOwner.getUUID() + ", pos=" + aiOwner.blockPosition();
            String aiContext = currentAiContextDetail();
            if (aiContext != null && !aiContext.isBlank()) {
                context = context == null || context.isBlank() ? aiContext : context + ", " + aiContext;
            }
            ScannerRuntime.sessions().recordSection(new SectionRecord(
                    "entity_query_cost",
                    queryKind,
                    ObservedTarget.entityQuery(level, bounds, resultCount, queryKind, context),
                    currentAiContextAttribution("entity_query"),
                    durationNanos
            ));
        } finally {
            ScannerSelfOverhead.end("entity_query_capture", overheadStart);
        }
    }

    public static void recordEventListener(Event event, EventListener listener, long durationNanos) {
        if (!active() || durationNanos < EVENT_LISTENER_THRESHOLD_NANOS) {
            return;
        }
        long overheadStart = ScannerSelfOverhead.start();
        try {
            String readable = listener == null ? "unknown" : EventListenerMetadata.readable(listener);
            ScannerRuntime.sessions().recordSection(new SectionRecord(
                    "event_listener_cost",
                    event == null ? "unknown_event" : event.getClass().getName(),
                    ObservedTarget.eventListener(event, listener),
                    EventListenerMetadata.attribution(listener, readable),
                    durationNanos
            ));
        } finally {
            ScannerSelfOverhead.end("event_listener_capture", overheadStart);
        }
    }

    public static void recordRenderSection(String section, long durationNanos) {
        if (!active() || durationNanos < RENDER_SECTION_THRESHOLD_NANOS) {
            return;
        }
        long overheadStart = ScannerSelfOverhead.start();
        try {
            ScannerRuntime.sessions().recordSection(new SectionRecord(
                    "render_cost",
                    section,
                    ObservedTarget.client(section, "client render section"),
                    AttributionResolver.capture("client_render"),
                    durationNanos
            ));
        } finally {
            ScannerSelfOverhead.end("render_section_capture", overheadStart);
        }
    }

    public static void recordClientFrame(
            String section,
            long durationNanos,
            int reportedFps,
            String screenName,
            boolean inWorld,
            ClientFrameBreakdown.Snapshot breakdown,
            ClientFrameContext context
    ) {
        if (!active()) {
            return;
        }
        long overheadStart = ScannerSelfOverhead.start();
        try {
            List<String> stack = durationNanos >= CLIENT_FRAME_STACK_THRESHOLD_NANOS ? compactStack() : List.of();
            ScannerRuntime.sessions().recordClientFrame(section, durationNanos, reportedFps, screenName, inWorld, stack, breakdown, context);
        } finally {
            ScannerSelfOverhead.end("client_frame_capture", overheadStart);
        }
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

    private static String currentAiContextDetail() {
        AiContext context = CURRENT_AI_CONTEXT.get();
        return context == null ? null : context.detail();
    }

    private static CallerAttribution currentAiContextAttribution(String fallbackModId) {
        AiContext context = CURRENT_AI_CONTEXT.get();
        if (context == null) {
            return AttributionResolver.capture(fallbackModId);
        }
        return AttributionResolver.fromClassName(context.componentClass(), context.phase(), context.ownerModId(), compactStack());
    }

    private static final class AttributionSample {
        private final AtomicInteger count = new AtomicInteger();
        private volatile CallerAttribution last;
    }

    private record AiContext(String kind, String componentClass, String phase, int priority, String ownerType, String ownerModId, String ownerUuid, String ownerPos) {
        private static AiContext goal(String goalClass, String phase, int priority, Mob owner) {
            ResourceLocation ownerType = ownerType(owner);
            return new AiContext("goal", goalClass, phase, priority, ownerType.toString(), ownerType.getNamespace(), owner.getUUID().toString(), pos(owner));
        }

        private static AiContext brain(String componentClass, String phase, LivingEntity owner) {
            ResourceLocation ownerType = ownerType(owner);
            return new AiContext("brain", componentClass, phase, -1, ownerType.toString(), ownerType.getNamespace(), owner.getUUID().toString(), pos(owner));
        }

        private String detail() {
            String priorityDetail = priority < 0 ? "" : ", ai_priority=" + priority;
            return "ai_context=" + kind
                    + ", ai_component=" + componentClass
                    + ", ai_phase=" + phase
                    + priorityDetail
                    + ", ai_context_owner=" + ownerType
                    + ", ai_context_uuid=" + ownerUuid
                    + ", ai_context_pos=" + ownerPos;
        }

        private static ResourceLocation ownerType(LivingEntity owner) {
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(owner.getType());
            return key == null ? ResourceLocation.withDefaultNamespace("unknown") : key;
        }

        private static String pos(LivingEntity owner) {
            BlockPos pos = owner.blockPosition();
            return pos.getX() + "," + pos.getY() + "," + pos.getZ();
        }
    }
}
