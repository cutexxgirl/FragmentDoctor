package dev.cutexxgirl.fragmentdoctor.modules.scaner.mixin;

import dev.cutexxgirl.fragmentdoctor.modules.scaner.instrumentation.CostRecorder;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Unique
    private long fragmentdoctor$clientFrameStart;

    @Inject(method = "runTick", at = @At("HEAD"))
    private void fragmentdoctor$clientFrameStart(boolean renderLevel, CallbackInfo ci) {
        if (CostRecorder.active()) {
            fragmentdoctor$clientFrameStart = System.nanoTime();
        }
    }

    @Inject(method = "runTick", at = @At("TAIL"))
    private void fragmentdoctor$clientFrameEnd(boolean renderLevel, CallbackInfo ci) {
        if (!CostRecorder.active() || fragmentdoctor$clientFrameStart == 0L) {
            return;
        }
        long duration = System.nanoTime() - fragmentdoctor$clientFrameStart;
        fragmentdoctor$clientFrameStart = 0L;
        Minecraft minecraft = (Minecraft)(Object)this;
        String screen = minecraft.screen == null ? "none" : minecraft.screen.getClass().getName();
        CostRecorder.recordClientFrame("run_tick", duration, minecraft.getFps(), screen, minecraft.level != null);
    }
}
