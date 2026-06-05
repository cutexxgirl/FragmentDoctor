package dev.cutexxgirl.fragmentdoctor.modules.scanner;

import dev.cutexxgirl.fragmentdoctor.modules.scanner.session.ScannerSessionManager;

public final class ScannerRuntime {
    private static final ScannerSessionManager SESSIONS = new ScannerSessionManager();

    private ScannerRuntime() {
    }

    public static ScannerSessionManager sessions() {
        return SESSIONS;
    }
}
