package dev.cutexxgirl.fragmentdoctor.modules.scaner.mixin;

import dev.cutexxgirl.fragmentdoctor.modules.scaner.instrumentation.CostRecorder;
import net.neoforged.bus.EventBus;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = EventBus.class, remap = false)
public abstract class EventBusMixin {
    @Redirect(
            method = "post(Lnet/neoforged/bus/api/Event;[Lnet/neoforged/bus/api/EventListener;)Lnet/neoforged/bus/api/Event;",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/bus/api/EventListener;invoke(Lnet/neoforged/bus/api/Event;)V", remap = false),
            remap = false
    )
    private void fragmentdoctor$profileEventListener(EventListener listener, Event event) {
        if (!CostRecorder.active()) {
            listener.invoke(event);
            return;
        }
        long start = System.nanoTime();
        try {
            listener.invoke(event);
        } finally {
            CostRecorder.recordEventListener(event, listener, System.nanoTime() - start);
        }
    }
}
