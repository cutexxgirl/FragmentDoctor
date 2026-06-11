package dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation;

import dev.cutexxgirl.fragmentdoctor.modules.scanner.attribution.AttributionResolver;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.attribution.CallerAttribution;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.neoforged.bus.api.EventListener;

public final class EventListenerMetadata {
    private static final Map<EventListener, CallerAttribution> ATTRIBUTIONS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<EventListener, String> READABLE = Collections.synchronizedMap(new WeakHashMap<>());

    private EventListenerMetadata() {
    }

    public static void register(EventListener listener, Method method, Object target) {
        if (listener == null || method == null) {
            return;
        }
        String className = method.getDeclaringClass().getName();
        Class<?>[] parameters = method.getParameterTypes();
        String eventType = parameters.length == 0 ? "unknown_event" : parameters[0].getName();
        String readable = className + "#" + method.getName() + "(" + eventType + ")";
        if (target != null) {
            readable += " target=" + target;
        }
        ATTRIBUTIONS.put(listener, AttributionResolver.fromClassName(className, method.getName(), "event_bus", java.util.List.of(readable)));
        READABLE.put(listener, readable);
    }

    public static CallerAttribution attribution(EventListener listener, String fallbackReadable) {
        CallerAttribution attribution = ATTRIBUTIONS.get(listener);
        if (attribution != null) {
            return attribution;
        }
        return AttributionResolver.fromReadable(fallbackReadable, "event_bus");
    }

    public static String readable(EventListener listener) {
        String readable = READABLE.get(listener);
        return readable == null ? String.valueOf(listener) : readable;
    }
}
