package dev.cutexxgirl.fragmentdoctor.modules.scanner.report;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ThreadSampleTracker {
    private final Map<String, Integer> renderFrames = new LinkedHashMap<>();
    private final Map<String, Integer> serverFrames = new LinkedHashMap<>();
    private final Map<String, Integer> workerFrames = new LinkedHashMap<>();
    private int renderSamples;
    private int serverSamples;
    private int workerSamples;

    public synchronized void add(String threadName, StackTraceElement[] frames) {
        if (threadName.contains("Render")) {
            renderSamples++;
            addFrames(renderFrames, frames);
        } else if (threadName.contains("Server")) {
            serverSamples++;
            addFrames(serverFrames, frames);
        } else if (threadName.contains("Worker")) {
            workerSamples++;
            addFrames(workerFrames, frames);
        }
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
}
