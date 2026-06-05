package dev.cutexxgirl.fragmentdoctor.modules.scaner;

import dev.cutexxgirl.fragmentdoctor.modules.scaner.session.ScanerSessionManager;

public final class ScanerRuntime {
    private static final ScanerSessionManager SESSIONS = new ScanerSessionManager();

    private ScanerRuntime() {
    }

    public static ScanerSessionManager sessions() {
        return SESSIONS;
    }
}
