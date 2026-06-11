package dev.cutexxgirl.fragmentdoctor.modules.scanner.mixin;

import dev.cutexxgirl.fragmentdoctor.modules.doctor.leafstorm.LeafstormSchedulerReplacement;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.CostRecorder;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.world.ticks.LevelTicks.class)
public abstract class LevelTicksMixin<T> {
    private long fragmentdoctor$gameTime;

    @Shadow
    public abstract void schedule(ScheduledTick<T> tick);

    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void fragmentdoctor$scheduledTickEnqueued(ScheduledTick<T> tick, CallbackInfo ci) {
        LeafstormSchedulerReplacement.ScheduleDecision decision = LeafstormSchedulerReplacement.beforeSchedule(this, tick);
        if (decision.cancelVanilla()) {
            ci.cancel();
            return;
        }
        if (!decision.scannerRecorded() && CostRecorder.active() && tick != null) {
            CostRecorder.recordScheduledTick(tick.type(), tick.pos());
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void fragmentdoctor$leafstormTickStart(long gameTime, int maxAllowedTicks, BiConsumer<BlockPos, T> ticker, CallbackInfo ci) {
        fragmentdoctor$gameTime = gameTime;
    }

    @Inject(method = "runCollectedTicks", at = @At("HEAD"))
    private void fragmentdoctor$drainScheduledLeafTicks(BiConsumer<BlockPos, T> ticker, CallbackInfo ci) {
        LeafstormSchedulerReplacement.drain(this, ticker, fragmentdoctor$gameTime);
    }
}
