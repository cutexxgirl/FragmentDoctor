package dev.cutexxgirl.fragmentdoctor.modules.scanner.mixin;

import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.CostRecorder;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MobMixin {
    @Inject(method = "serverAiStep", at = @At("HEAD"))
    private void fragmentdoctor$pushAiOwner(CallbackInfo ci) {
        CostRecorder.pushAiOwner((Mob)(Object)this);
    }

    @Inject(method = "serverAiStep", at = @At("TAIL"))
    private void fragmentdoctor$popAiOwner(CallbackInfo ci) {
        CostRecorder.popAiOwner((Mob)(Object)this);
    }
}
