package dev.cutexxgirl.fragmentdoctor.modules.scaner.log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class JsonlLogWriter implements Closeable {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path path;
    private final BufferedWriter writer;

    public JsonlLogWriter(Path path) throws IOException {
        this.path = path;
        Files.createDirectories(path.getParent());
        this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
    }

    public Path path() {
        return path;
    }

    public synchronized void write(JsonObject object) {
        try {
            writer.write(GSON.toJson(object));
            writer.newLine();
            writer.flush();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write Fragment Doctor scaner log", exception);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
    }
}
