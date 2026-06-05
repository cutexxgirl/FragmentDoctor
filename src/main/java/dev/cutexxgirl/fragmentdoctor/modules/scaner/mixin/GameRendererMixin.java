package dev.cutexxgirl.fragmentdoctor.modules.scaner.mixin;

import dev.cutexxgirl.fragmentdoctor.modules.scaner.instrumentation.CostRecorder;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Unique
    private long fragmentdoctor$renderStart;
    @Unique
    private long fragmentdoctor$renderLevelStart;

    @Inject(method = "render", at = @At("HEAD"))
    private void fragmentdoctor$renderStart(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        if (CostRecorder.active()) {
            fragmentdoctor$renderStart = System.nanoTime();
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void fragmentdoctor$renderEnd(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        if (CostRecorder.active() && fragmentdoctor$renderStart != 0L) {
            CostRecorder.recordRenderSection("game_renderer_render", System.nanoTime() - fragmentdoctor$renderStart);
            fragmentdoctor$renderStart = 0L;
        }
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void fragmentdoctor$renderLevelStart(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (CostRecorder.active()) {
            fragmentdoctor$renderLevelStart = System.nanoTime();
        }
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void fragmentdoctor$renderLevelEnd(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (CostRecorder.active() && fragmentdoctor$renderLevelStart != 0L) {
            CostRecorder.recordRenderSection("render_level", System.nanoTime() - fragmentdoctor$renderLevelStart);
            fragmentdoctor$renderLevelStart = 0L;
        }
    }
}
