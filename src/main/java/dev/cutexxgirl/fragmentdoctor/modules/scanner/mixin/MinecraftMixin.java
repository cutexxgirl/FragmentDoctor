package dev.cutexxgirl.fragmentdoctor.modules.scanner.mixin;

import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.CostRecorder;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.ClientFrameBreakdown;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.ClientFrameContext;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.ScannerSelfOverhead;
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
            ClientFrameBreakdown.beginFrame();
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
        ClientFrameBreakdown.Snapshot breakdown = ClientFrameBreakdown.finishFrame();
        long scannerTailStart = ScannerSelfOverhead.start();
        try {
            long contextOverheadStart = ScannerSelfOverhead.start();
            ClientFrameContext context;
            try {
                context = minecraft.player == null ? ClientFrameContext.EMPTY : new ClientFrameContext(
                        minecraft.getWindow().getWidth(),
                        minecraft.getWindow().getHeight(),
                        minecraft.options.getEffectiveRenderDistance(),
                        minecraft.level == null ? 0 : minecraft.level.getEntityCount(),
                        minecraft.player.getX(),
                        minecraft.player.getY(),
                        minecraft.player.getZ()
                );
            } finally {
                ScannerSelfOverhead.end("client_frame_context", contextOverheadStart);
            }
            CostRecorder.recordClientFrame("run_tick", duration, minecraft.getFps(), screen, minecraft.level != null, breakdown, context);
        } finally {
            ScannerSelfOverhead.end("client_frame_tail", scannerTailStart);
            ClientFrameBreakdown.recordScannerTail(System.nanoTime() - scannerTailStart);
        }
    }
}
