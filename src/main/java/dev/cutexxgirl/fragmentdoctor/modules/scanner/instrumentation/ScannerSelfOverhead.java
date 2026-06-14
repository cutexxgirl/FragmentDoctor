package dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class ScannerSelfOverhead {
    private static final ThreadLocal<Long> SERVER_TICK_NANOS = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Long> CLIENT_FRAME_NANOS = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Integer> MEASUREMENT_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ConcurrentHashMap<String, LongAdder> TOTALS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> COUNTS = new ConcurrentHashMap<>();
    private static final LongAdder SERVER_THREAD_TOTAL = new LongAdder();
    private static final LongAdder RENDER_THREAD_TOTAL = new LongAdder();

    private ScannerSelfOverhead() {
    }

    public static long start() {
        MEASUREMENT_DEPTH.set(MEASUREMENT_DEPTH.get() + 1);
        return System.nanoTime();
    }

    public static void reset() {
        TOTALS.clear();
        COUNTS.clear();
        SERVER_THREAD_TOTAL.reset();
        RENDER_THREAD_TOTAL.reset();
        SERVER_TICK_NANOS.remove();
        CLIENT_FRAME_NANOS.remove();
        MEASUREMENT_DEPTH.remove();
    }

    public static void end(String category, long startedNanos) {
        long elapsed = Math.max(0L, System.nanoTime() - startedNanos);
        TOTALS.computeIfAbsent(category, ignored -> new LongAdder()).add(elapsed);
        COUNTS.computeIfAbsent(category, ignored -> new LongAdder()).increment();
        int depth = Math.max(0, MEASUREMENT_DEPTH.get() - 1);
        MEASUREMENT_DEPTH.set(depth);
        if (depth == 0) {
            String threadName = Thread.currentThread().getName();
            if ("Server thread".equals(threadName) || threadName.contains("Integrated Server")) {
                SERVER_TICK_NANOS.set(SERVER_TICK_NANOS.get() + elapsed);
                SERVER_THREAD_TOTAL.add(elapsed);
            }
            if (ClientFrameBreakdown.isRenderThread(Thread.currentThread())) {
                CLIENT_FRAME_NANOS.set(CLIENT_FRAME_NANOS.get() + elapsed);
                RENDER_THREAD_TOTAL.add(elapsed);
            }
        }
    }

    public static void beginServerTick() {
        SERVER_TICK_NANOS.set(0L);
    }

    public static long currentServerTickNanos() {
        return SERVER_TICK_NANOS.get();
    }

    public static void beginClientFrame() {
        CLIENT_FRAME_NANOS.set(0L);
    }

    public static long currentClientFrameNanos() {
        return CLIENT_FRAME_NANOS.get();
    }

    public static Map<String, Long> totalsNanos() {
        Map<String, Long> result = new LinkedHashMap<>();
        TOTALS.forEach((key, value) -> result.put(key, value.sum()));
        return result;
    }

    public static Map<String, Long> counts() {
        Map<String, Long> result = new LinkedHashMap<>();
        COUNTS.forEach((key, value) -> result.put(key, value.sum()));
        return result;
    }

    public static long totalServerThreadNanos() {
        return SERVER_THREAD_TOTAL.sum();
    }

    public static long totalRenderThreadNanos() {
        return RENDER_THREAD_TOTAL.sum();
    }
}
