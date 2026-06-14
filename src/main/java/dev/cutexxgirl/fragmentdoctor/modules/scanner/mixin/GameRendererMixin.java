package dev.cutexxgirl.fragmentdoctor.modules.scanner.mixin;

import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.CostRecorder;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.ClientFrameBreakdown;
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
            long duration = System.nanoTime() - fragmentdoctor$renderStart;
            ClientFrameBreakdown.recordGameRenderer(duration);
            CostRecorder.recordRenderSection("game_renderer_render", duration);
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
            long duration = System.nanoTime() - fragmentdoctor$renderLevelStart;
            ClientFrameBreakdown.recordRenderLevel(duration);
            CostRecorder.recordRenderSection("render_level", duration);
            fragmentdoctor$renderLevelStart = 0L;
        }
    }
}
