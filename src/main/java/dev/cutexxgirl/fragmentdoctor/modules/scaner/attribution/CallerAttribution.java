package dev.cutexxgirl.fragmentdoctor.modules.scaner.attribution;

import java.util.List;

public record CallerAttribution(String suspectModId, String className, String methodName, List<String> stack) {
    public String suspectKey(String fallbackModId) {
        String mod = suspectModId == null || suspectModId.isBlank() ? fallbackModId : suspectModId;
        if (mod == null || mod.isBlank()) {
            mod = "unknown";
        }
        if (className == null || className.isBlank()) {
            return mod;
        }
        return mod + ":" + className;
    }
}
