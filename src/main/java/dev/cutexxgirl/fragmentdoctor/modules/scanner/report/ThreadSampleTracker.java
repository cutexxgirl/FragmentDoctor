package dev.cutexxgirl.fragmentdoctor.modules.scanner.report;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

public final class ThreadSampleTracker {
    public static final String ROLE_RENDER = "render";
    public static final String ROLE_SERVER = "server";
    public static final String ROLE_WORKER = "worker";

    private final Map<String, Integer> renderFrames = new LinkedHashMap<>();
    private final Map<String, Integer> serverFrames = new LinkedHashMap<>();
    private final Map<String, Integer> workerFrames = new LinkedHashMap<>();
    private final NavigableMap<Integer, Bucket> timeline = new TreeMap<>();
    private int renderSamples;
    private int serverSamples;
    private int workerSamples;

    public synchronized void add(String role, int windowIndex, StackTraceElement[] frames) {
        Map<String, Integer> target;
        if (ROLE_RENDER.equals(role)) {
            renderSamples++;
            target = renderFrames;
        } else if (ROLE_SERVER.equals(role)) {
            serverSamples++;
            target = serverFrames;
        } else {
            workerSamples++;
            target = workerFrames;
        }
        addFrames(target, frames);
        timeline.computeIfAbsent(windowIndex, ignored -> new Bucket()).add(role, frames);
    }

    public synchronized int renderSamples() {
        return renderSamples;
    }

    public synchronized int serverSamples() {
        return serverSamples;
    }

    public synchronized int workerSamples() {
        return workerSamples;
    }

    public synchronized Map<String, Integer> renderFrames() {
        return new LinkedHashMap<>(renderFrames);
    }

    public synchronized Map<String, Integer> serverFrames() {
        return new LinkedHashMap<>(serverFrames);
    }

    public synchronized Map<String, Integer> workerFrames() {
        return new LinkedHashMap<>(workerFrames);
    }

    public synchronized Map<String, Integer> framesBetween(String role, int startWindow, int endWindow) {
        Map<String, Integer> result = new LinkedHashMap<>();
        timeline.subMap(startWindow, true, endWindow, true).values()
                .forEach(bucket -> merge(result, bucket.frames(role)));
        return result;
    }

    private static void merge(Map<String, Integer> target, Map<String, Integer> source) {
        source.forEach((frame, count) -> target.merge(frame, count, Integer::sum));
    }

    private static void addFrames(Map<String, Integer> target, StackTraceElement[] frames) {
        int added = 0;
        for (StackTraceElement frame : frames) {
            String className = frame.getClassName();
            if (className.startsWith("java.lang.Thread")
                    || className.startsWith("jdk.internal.")
                    || className.startsWith("java.util.concurrent.locks.")
                    || className.startsWith("dev.cutexxgirl.fragmentdoctor.modules.scanner.")) {
                continue;
            }
            target.merge(className + "#" + frame.getMethodName() + ":" + frame.getLineNumber(), 1, Integer::sum);
            added++;
            if (added >= 12) {
                break;
            }
        }
    }

    private static final class Bucket {
        private final Map<String, Integer> render = new LinkedHashMap<>();
        private final Map<String, Integer> server = new LinkedHashMap<>();
        private final Map<String, Integer> worker = new LinkedHashMap<>();

        private void add(String role, StackTraceElement[] frames) {
            addFrames(frames(role), frames);
        }

        private Map<String, Integer> frames(String role) {
            return switch (role) {
                case ROLE_RENDER -> render;
                case ROLE_SERVER -> server;
                default -> worker;
            };
        }
    }
}
