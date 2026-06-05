package dev.cutexxgirl.fragmentdoctor.modules.scanner.log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class JsonlLogWriter implements Closeable {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int QUEUE_CAPACITY = 8192;
    private static final int FLUSH_BATCH_SIZE = 256;

    private final Path path;
    private final BufferedWriter writer;
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicLong droppedEvents = new AtomicLong();
    private final AtomicReference<IOException> failure = new AtomicReference<>();
    private final Thread worker;

    public JsonlLogWriter(Path path) throws IOException {
        this.path = path;
        Files.createDirectories(path.getParent());
        this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        this.worker = new Thread(this::drainLoop, "FragmentDoctor scanner JSONL writer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public Path path() {
        return path;
    }

    public void write(JsonObject object) {
        IOException currentFailure = failure.get();
        if (currentFailure != null) {
            throw new IllegalStateException("Failed to write Fragment Doctor scanner log", currentFailure);
        }
        if (closing.get()) {
            return;
        }
        String line = GSON.toJson(object);
        if (!queue.offer(line)) {
            droppedEvents.incrementAndGet();
        }
    }

    public long droppedEvents() {
        return droppedEvents.get();
    }

    private void drainLoop() {
        int pendingFlush = 0;
        try {
            while (!closing.get() || !queue.isEmpty()) {
                String line = queue.poll(250L, TimeUnit.MILLISECONDS);
                if (line == null) {
                    if (pendingFlush > 0) {
                        writer.flush();
                        pendingFlush = 0;
                    }
                    continue;
                }
                writer.write(line);
                writer.newLine();
                pendingFlush++;
                if (pendingFlush >= FLUSH_BATCH_SIZE) {
                    writer.flush();
                    pendingFlush = 0;
                }
            }
            writer.flush();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            failure.compareAndSet(null, exception);
        }
    }

    @Override
    public void close() throws IOException {
        closing.set(true);
        try {
            worker.join(5000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            worker.interrupt();
        }
        IOException currentFailure = failure.get();
        if (currentFailure != null) {
            try {
                writer.close();
            } catch (IOException closeException) {
                currentFailure.addSuppressed(closeException);
            }
            throw currentFailure;
        }
        writer.close();
    }
}
