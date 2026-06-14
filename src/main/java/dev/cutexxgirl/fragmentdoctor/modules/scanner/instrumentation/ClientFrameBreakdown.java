package dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation;

public final class ClientFrameBreakdown {
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);
    private static volatile long renderThreadId = -1L;

    private ClientFrameBreakdown() {
    }

    public static void beginFrame() {
        renderThreadId = Thread.currentThread().threadId();
        ScannerSelfOverhead.beginClientFrame();
        State state = STATE.get();
        state.active = true;
        state.gameRendererNanos = 0L;
        state.renderLevelNanos = 0L;
        state.presentNanos = 0L;
        state.scannerBetweenFramesNanos = state.pendingScannerTailNanos;
        state.pendingScannerTailNanos = 0L;
    }

    public static void recordGameRenderer(long durationNanos) {
        State state = STATE.get();
        if (state.active) {
            state.gameRendererNanos += Math.max(0L, durationNanos);
        }
    }

    public static void recordRenderLevel(long durationNanos) {
        State state = STATE.get();
        if (state.active) {
            state.renderLevelNanos += Math.max(0L, durationNanos);
        }
    }

    public static void recordPresent(long durationNanos) {
        State state = STATE.get();
        if (state.active) {
            state.presentNanos += Math.max(0L, durationNanos);
        }
    }

    public static Snapshot finishFrame() {
        State state = STATE.get();
        if (!state.active) {
            return Snapshot.EMPTY;
        }
        state.active = false;
        return new Snapshot(
                state.gameRendererNanos,
                state.renderLevelNanos,
                state.presentNanos,
                ScannerSelfOverhead.currentClientFrameNanos(),
                state.scannerBetweenFramesNanos
        );
    }

    public static void recordScannerTail(long durationNanos) {
        State state = STATE.get();
        state.pendingScannerTailNanos += Math.max(0L, durationNanos);
    }

    public static boolean isRenderThread(Thread thread) {
        return thread != null && thread.threadId() == renderThreadId;
    }

    public record Snapshot(
            long gameRendererNanos,
            long renderLevelNanos,
            long presentNanos,
            long scannerOverheadNanos,
            long scannerBetweenFramesNanos
    ) {
        public static final Snapshot EMPTY = new Snapshot(0L, 0L, 0L, 0L, 0L);
    }

    private static final class State {
        private boolean active;
        private long gameRendererNanos;
        private long renderLevelNanos;
        private long presentNanos;
        private long scannerBetweenFramesNanos;
        private long pendingScannerTailNanos;
    }
}
