package dev.cutexxgirl.fragmentdoctor.modules.scaner.session;

import dev.cutexxgirl.fragmentdoctor.FragmentDoctor;
import dev.cutexxgirl.fragmentdoctor.modules.scaner.attribution.CallerAttribution;
import dev.cutexxgirl.fragmentdoctor.modules.scaner.instrumentation.ObservedTarget;
import dev.cutexxgirl.fragmentdoctor.modules.scaner.instrumentation.SectionRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;

public final class ScanerSessionManager {
    private static final int MAX_RETAINED_SESSIONS = 20;
    private ScanerSession activeSession;
    private Path lastSummaryPath;

    public synchronized StartResult start(MinecraftServer server, int seconds) {
        if (activeSession != null && activeSession.isActive()) {
            return new StartResult(false, "scaner is already running: " + activeSession.sessionId(), activeSession.sessionId(), null);
        }
        int clampedSeconds = Math.max(5, Math.min(seconds, 600));
        try {
            Path directory = server.getServerDirectory().resolve("fragmentdoctor").resolve("scaner");
            Files.createDirectories(directory);
            rotateOldSessions(directory);
            activeSession = ScanerSession.start(directory, clampedSeconds);
            return new StartResult(true, "scaner started for " + clampedSeconds + "s", activeSession.sessionId(), activeSession.logPath());
        } catch (IOException exception) {
            FragmentDoctor.LOGGER.error("Failed to start scaner session", exception);
            return new StartResult(false, "failed to start scaner: " + exception.getMessage(), null, null);
        }
    }

    public synchronized StopResult stop(MinecraftServer server, String reason) {
        if (activeSession == null || !activeSession.isActive()) {
            return new StopResult(false, "scaner is not running", lastSummaryPath);
        }
        lastSummaryPath = activeSession.finish(server, reason);
        return new StopResult(true, "scaner stopped", lastSummaryPath);
    }

    public synchronized Status status() {
        if (activeSession == null || !activeSession.isActive()) {
            return new Status(false, "idle", 0, 0, 0.0D, 0, lastSummaryPath);
        }
        return new Status(true, activeSession.sessionId(), activeSession.elapsedTicks(), activeSession.durationTicks(), activeSession.elapsedWallSeconds(), activeSession.durationSeconds(), lastSummaryPath);
    }

    public synchronized Optional<Path> lastSummaryPath() {
        return Optional.ofNullable(lastSummaryPath);
    }

    public void onServerTickStart(MinecraftServer server) {
        ScanerSession session = active();
        if (session != null) {
            session.onServerTickStart(server);
        }
    }

    public void onServerTickEnd(MinecraftServer server) {
        ScanerSession session = active();
        if (session != null) {
            Path summary = session.onServerTickEnd(server);
            if (summary != null) {
                synchronized (this) {
                    lastSummaryPath = summary;
                }
            }
        }
    }

    public void recordSection(SectionRecord record) {
        ScanerSession session = active();
        if (session != null) {
            session.recordSection(record);
        }
    }

    public void recordScheduledTick(ObservedTarget target, CallerAttribution caller, String origin) {
        ScanerSession session = active();
        if (session != null) {
            session.recordScheduledTick(target, caller, origin);
        }
    }

    public void recordGlError(int error, String description, String section, CallerAttribution caller) {
        ScanerSession session = active();
        if (session != null) {
            session.recordGlError(error, description, section, caller);
        }
    }

    public void recordInvalidKeyPoll(int keyCode, CallerAttribution caller) {
        ScanerSession session = active();
        if (session != null) {
            session.recordInvalidKeyPoll(keyCode, caller);
        }
    }

    public void recordClientFrame(String section, long durationNanos, int reportedFps, String screenName, boolean inWorld, java.util.List<String> stack) {
        ScanerSession session = active();
        if (session != null) {
            session.recordClientFrame(section, durationNanos, reportedFps, screenName, inWorld, stack);
        }
    }

    public boolean isActive() {
        ScanerSession session = active();
        return session != null;
    }

    private synchronized ScanerSession active() {
        return activeSession != null && activeSession.isActive() ? activeSession : null;
    }

    private static void rotateOldSessions(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            var sessionFiles = files
                    .filter(path -> path.getFileName().toString().startsWith("scan-"))
                    .sorted(Comparator.comparing(path -> path.toFile().lastModified(), Comparator.reverseOrder()))
                    .toList();
            int maxFiles = MAX_RETAINED_SESSIONS * 2;
            for (int index = maxFiles; index < sessionFiles.size(); index++) {
                Files.deleteIfExists(sessionFiles.get(index));
            }
        }
    }

    public record StartResult(boolean success, String message, String sessionId, Path logPath) {
    }

    public record StopResult(boolean success, String message, Path summaryPath) {
    }

    public record Status(boolean active, String sessionId, int elapsedTicks, int durationTicks, double elapsedSeconds, int durationSeconds, Path lastSummaryPath) {
    }
}
