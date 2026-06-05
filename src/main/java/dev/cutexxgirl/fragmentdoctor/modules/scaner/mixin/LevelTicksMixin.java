package dev.cutexxgirl.fragmentdoctor.modules.scaner.mixin;

import dev.cutexxgirl.fragmentdoctor.modules.doctor.leafstorm.LeafstormDoctor;
import dev.cutexxgirl.fragmentdoctor.modules.scaner.instrumentation.CostRecorder;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.world.ticks.LevelTicks.class)
public abstract class LevelTicksMixin<T> {
    private long fragmentdoctor$gameTime;
    private int fragmentdoctor$queueDepth;

    @Shadow
    public abstract void schedule(ScheduledTick<T> tick);

    @Shadow
    public abstract int count();

    @Inject(method = "schedule", at = @At("HEAD"))
    private void fragmentdoctor$scheduledTickEnqueued(ScheduledTick<T> tick, CallbackInfo ci) {
        if (CostRecorder.active() && tick != null) {
            CostRecorder.recordScheduledTick(tick.type(), tick.pos());
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void fragmentdoctor$leafstormTickStart(long gameTime, int maxAllowedTicks, BiConsumer<BlockPos, T> ticker, CallbackInfo ci) {
        fragmentdoctor$gameTime = gameTime;
        fragmentdoctor$queueDepth = count();
    }

    @SuppressWarnings("unchecked")
    @Redirect(method = "runCollectedTicks", at = @At(value = "INVOKE", target = "Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V"))
    private void fragmentdoctor$runOrDeferLeafTick(BiConsumer<BlockPos, T> ticker, Object pos, Object type) {
        BlockPos blockPos = (BlockPos)pos;
        LeafstormDoctor.Decision decision = LeafstormDoctor.beforeScheduledTick(this, type, blockPos, fragmentdoctor$queueDepth, fragmentdoctor$gameTime);
        if (!decision.defer()) {
            if (decision.deduplicated()) {
                CostRecorder.recordScheduledTick(type, blockPos, CostRecorder.ORIGIN_DOCTOR_DEDUPLICATED);
                return;
            }
            ticker.accept(blockPos, (T)type);
            return;
        }
        CostRecorder.pushScheduledTickOrigin(CostRecorder.ORIGIN_DOCTOR_RESCHEDULE);
        try {
            schedule(new ScheduledTick<>(
                    (T)type,
                    blockPos,
                    fragmentdoctor$gameTime + decision.delayTicks(),
                    TickPriority.NORMAL,
                    LeafstormDoctor.nextDeferredSubTickOrder()
            ));
        } finally {
            CostRecorder.popScheduledTickOrigin();
        }
    }
}
