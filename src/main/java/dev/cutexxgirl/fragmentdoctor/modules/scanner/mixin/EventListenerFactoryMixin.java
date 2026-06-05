package dev.cutexxgirl.fragmentdoctor.modules.scanner.mixin;

import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.EventListenerMetadata;
import java.lang.reflect.Method;
import net.neoforged.bus.api.EventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.neoforged.bus.EventListenerFactory", remap = false)
public abstract class EventListenerFactoryMixin {
    @Inject(method = "create", at = @At("RETURN"), remap = false)
    private static void fragmentdoctor$rememberListenerMetadata(Method callback, Object target, CallbackInfoReturnable<EventListener> cir) {
        EventListenerMetadata.register(cir.getReturnValue(), callback, target);
    }
}
