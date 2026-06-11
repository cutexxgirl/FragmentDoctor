package dev.cutexxgirl.fragmentdoctor.modules.scanner.attribution;

import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class AttributionResolver {
    private static final int MAX_STACK_FRAMES = 24;
    private static final Pattern CLASS_NAME = Pattern.compile("([a-zA-Z_$][\\w$]*\\.)+[a-zA-Z_$][\\w$]*");
    private static final Map<String, String> PACKAGE_HINTS = createPackageHints();

    private AttributionResolver() {
    }

    public static CallerAttribution capture(String fallbackModId) {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        List<String> compactStack = new ArrayList<>();
        StackTraceElement firstExternal = null;

        for (StackTraceElement frame : trace) {
            String className = frame.getClassName();
            if (compactStack.size() < MAX_STACK_FRAMES && shouldKeepFrame(className)) {
                compactStack.add(className + "#" + frame.getMethodName() + ":" + frame.getLineNumber());
            }
            if (firstExternal == null && isLikelyModFrame(className)) {
                firstExternal = frame;
            }
        }

        if (firstExternal == null) {
            return new CallerAttribution(fallbackModId, "unknown", "unknown", compactStack);
        }

        String className = firstExternal.getClassName();
        String modId = inferModId(className, fallbackModId);
        return new CallerAttribution(modId, className, firstExternal.getMethodName(), compactStack);
    }

    public static CallerAttribution fromClassName(String className, String methodName, String fallbackModId, List<String> evidence) {
        String safeClass = className == null || className.isBlank() ? "unknown" : className;
        String safeMethod = methodName == null || methodName.isBlank() ? "unknown" : methodName;
        return new CallerAttribution(inferModId(safeClass, fallbackModId), safeClass, safeMethod, evidence);
    }

    public static CallerAttribution fromReadable(String readable, String fallbackModId) {
        String safeReadable = readable == null ? "unknown" : readable;
        String className = "unknown";
        String methodName = "unknown";
        var matcher = CLASS_NAME.matcher(safeReadable);
        while (matcher.find()) {
            String candidate = matcher.group();
            if (isLikelyModFrame(candidate)) {
                className = candidate;
                break;
            }
        }
        int hashIndex = safeReadable.indexOf('#');
        int descriptorStart = hashIndex >= 0 ? safeReadable.indexOf('(', hashIndex) : -1;
        if (hashIndex >= 0 && descriptorStart > hashIndex) {
            methodName = safeReadable.substring(hashIndex + 1, descriptorStart);
        } else {
            int methodStart = safeReadable.lastIndexOf(' ');
            descriptorStart = safeReadable.indexOf('(', Math.max(0, methodStart));
            if (methodStart >= 0 && descriptorStart > methodStart) {
                methodName = safeReadable.substring(methodStart + 1, descriptorStart);
            }
        }
        return fromClassName(className, methodName, fallbackModId, List.of(safeReadable));
    }

    private static boolean shouldKeepFrame(String className) {
        return !className.startsWith("java.lang.Thread")
                && !className.startsWith("dev.cutexxgirl.fragmentdoctor.modules.scanner.");
    }

    private static boolean isLikelyModFrame(String className) {
        return shouldKeepFrame(className)
                && !className.startsWith("dev.cutexxgirl.fragmentdoctor")
                && !className.startsWith("net.minecraft.")
                && !className.startsWith("com.mojang.")
                && !className.startsWith("net.neoforged.")
                && !className.startsWith("org.spongepowered.")
                && !className.startsWith("org.lwjgl.")
                && !className.startsWith("com.llamalad7.mixinextras.")
                && !className.startsWith("java.")
                && !className.startsWith("jdk.");
    }

    private static String inferModId(String className, String fallbackModId) {
        String lower = className.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> hint : PACKAGE_HINTS.entrySet()) {
            if (lower.startsWith(hint.getKey())) {
                return hint.getValue();
            }
        }
        String codeSourceModId = inferFromCodeSource(className);
        if (codeSourceModId != null) {
            return codeSourceModId;
        }
        int firstDot = lower.indexOf('.');
        if (firstDot > 1) {
            String root = lower.substring(0, firstDot);
            if (!root.equals("com") && !root.equals("org") && !root.equals("net") && !root.equals("io") && !root.equals("dev")) {
                return sanitize(root);
            }
        }
        return fallbackModId;
    }

    private static String inferFromCodeSource(String className) {
        String loadableClassName = className;
        int lambdaIndex = loadableClassName.indexOf("$$Lambda");
        if (lambdaIndex > 0) {
            loadableClassName = loadableClassName.substring(0, lambdaIndex);
        }
        try {
            Class<?> type = Class.forName(loadableClassName, false, Thread.currentThread().getContextClassLoader());
            if (type.getProtectionDomain() == null || type.getProtectionDomain().getCodeSource() == null) {
                return null;
            }
            URL location = type.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            return inferFromLocationName(Path.of(location.toURI()).getFileName().toString());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String inferFromLocationName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String base = fileName.toLowerCase(Locale.ROOT);
        if (base.endsWith(".jar")) {
            base = base.substring(0, base.length() - 4);
        }
        if (base.isBlank() || base.equals("main") || base.equals("minecraft") || base.startsWith("neoforge-")) {
            return null;
        }
        String[] parts = base.split("[-_+ ]+");
        List<String> modParts = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (part.matches("(?:mc)?\\d.*") || part.equals("forge") || part.equals("fabric") || part.equals("neoforge") || part.equals("common")) {
                break;
            }
            modParts.add(part);
        }
        if (modParts.isEmpty()) {
            return null;
        }
        return sanitize(String.join("_", modParts));
    }

    private static Map<String, String> createPackageHints() {
        Map<String, String> hints = new LinkedHashMap<>();
        hints.put("com.bawnorton.neruina.", "neruina");
        hints.put("com.performant.", "perf");
        hints.put("observable.", "observable");
        hints.put("com.ishland.c2me.", "c2me");
        hints.put("net.caffeinemc.mods.sodium.", "sodium");
        hints.put("net.irisshaders.iris.", "iris");
        hints.put("me.decce.ixeris.", "ixeris");
        hints.put("dev.emi.emi.", "emi");
        hints.put("com.simibubi.create.", "create");
        hints.put("punchy.", "punchy");
        hints.put("com.cyberday1.neoorigins.", "neoorigins");
        hints.put("net.mehvahdjukaar.polytone.", "polytone");
        hints.put("net.countered.terrainslabs.", "terrain_slabs");
        hints.put("net.mehvahdjukaar.supplementaries.", "supplementaries");
        hints.put("net.mehvahdjukaar.moonlight.", "moonlight");
        hints.put("io.redspace.ironsspellbooks.", "irons_spellbooks");
        hints.put("io.redspace.ironslib.", "irons_lib");
        hints.put("com.squoshi.irons_spells_js.", "irons_spells_js");
        hints.put("net.acetheeldritchking.aces_spell_utils.", "aces_spell_utils");
        hints.put("net.acetheeldritchking.cataclysm_spellbooks.", "cataclysm_spellbooks");
        hints.put("com.kneelawk.extra_mod_integrations.", "extra_mod_integrations");
        hints.put("icyllis.modernui.", "modernui");
        hints.put("net.conczin.immersive_optimization.", "immersive_optimization");
        hints.put("com.github.alexthe666.", "alexsmobs_or_iceandfire");
        hints.put("vazkii.quark.", "quark");
        hints.put("squeek.appleskin.", "appleskin");
        hints.put("xaero.", "xaero");
        hints.put("vectorwing.farmersdelight.", "farmersdelight");
        hints.put("com.teamabnormals.", "blueprint_or_abnormals");
        return hints;
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-z0-9_\\-.]", "_");
    }
}
