package dev.cutexxgirl.fragmentdoctor.modules.scaner.mixin;

import dev.cutexxgirl.fragmentdoctor.modules.scaner.instrumentation.CostRecorder;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PathNavigation.class)
public abstract class PathNavigationMixin {
    @Shadow
    @Final
    protected Mob mob;

    @Unique
    private long fragmentdoctor$navigationStart;

    @Inject(method = "tick", at = @At("HEAD"))
    private void fragmentdoctor$navigationStart(CallbackInfo ci) {
        if (CostRecorder.active()) {
            fragmentdoctor$navigationStart = System.nanoTime();
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void fragmentdoctor$navigationEnd(CallbackInfo ci) {
        if (CostRecorder.active() && fragmentdoctor$navigationStart != 0L) {
            CostRecorder.recordNavigation((PathNavigation)(Object)this, mob, System.nanoTime() - fragmentdoctor$navigationStart);
            fragmentdoctor$navigationStart = 0L;
        }
    }
}
