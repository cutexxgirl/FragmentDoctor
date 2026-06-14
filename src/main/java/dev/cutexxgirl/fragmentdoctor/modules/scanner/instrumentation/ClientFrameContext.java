package dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation;

public record ClientFrameContext(
        int width,
        int height,
        int renderDistance,
        int clientEntityCount,
        double playerX,
        double playerY,
        double playerZ
) {
    public static final ClientFrameContext EMPTY = new ClientFrameContext(0, 0, 0, 0, 0.0D, 0.0D, 0.0D);
}
