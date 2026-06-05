package dev.cutexxgirl.fragmentdoctor.modules.scaner.mixin;

import dev.cutexxgirl.fragmentdoctor.modules.scaner.instrumentation.CostRecorder;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WrappedGoal.class)
public abstract class WrappedGoalMixin {
    @Unique
    private long fragmentdoctor$canUseStart;
    @Unique
    private long fragmentdoctor$canContinueStart;
    @Unique
    private long fragmentdoctor$startStart;
    @Unique
    private long fragmentdoctor$stopStart;
    @Unique
    private long fragmentdoctor$tickStart;

    @Inject(method = "canUse", at = @At("HEAD"))
    private void fragmentdoctor$canUseStart(CallbackInfoReturnable<Boolean> cir) {
        if (CostRecorder.active()) {
            fragmentdoctor$canUseStart = System.nanoTime();
        }
    }

    @Inject(method = "canUse", at = @At("RETURN"))
    private void fragmentdoctor$canUseEnd(CallbackInfoReturnable<Boolean> cir) {
        if (CostRecorder.active() && fragmentdoctor$canUseStart != 0L) {
            CostRecorder.recordGoal((WrappedGoal)(Object)this, "goal_can_use", System.nanoTime() - fragmentdoctor$canUseStart);
            fragmentdoctor$canUseStart = 0L;
        }
    }

    @Inject(method = "canContinueToUse", at = @At("HEAD"))
    private void fragmentdoctor$canContinueStart(CallbackInfoReturnable<Boolean> cir) {
        if (CostRecorder.active()) {
            fragmentdoctor$canContinueStart = System.nanoTime();
        }
    }

    @Inject(method = "canContinueToUse", at = @At("RETURN"))
    private void fragmentdoctor$canContinueEnd(CallbackInfoReturnable<Boolean> cir) {
        if (CostRecorder.active() && fragmentdoctor$canContinueStart != 0L) {
            CostRecorder.recordGoal((WrappedGoal)(Object)this, "goal_can_continue", System.nanoTime() - fragmentdoctor$canContinueStart);
            fragmentdoctor$canContinueStart = 0L;
        }
    }

    @Inject(method = "start", at = @At("HEAD"))
    private void fragmentdoctor$startStart(CallbackInfo ci) {
        if (CostRecorder.active()) {
            fragmentdoctor$startStart = System.nanoTime();
        }
    }

    @Inject(method = "start", at = @At("TAIL"))
    private void fragmentdoctor$startEnd(CallbackInfo ci) {
        if (CostRecorder.active() && fragmentdoctor$startStart != 0L) {
            CostRecorder.recordGoal((WrappedGoal)(Object)this, "goal_start", System.nanoTime() - fragmentdoctor$startStart);
            fragmentdoctor$startStart = 0L;
        }
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void fragmentdoctor$stopStart(CallbackInfo ci) {
        if (CostRecorder.active()) {
            fragmentdoctor$stopStart = System.nanoTime();
        }
    }

    @Inject(method = "stop", at = @At("TAIL"))
    private void fragmentdoctor$stopEnd(CallbackInfo ci) {
        if (CostRecorder.active() && fragmentdoctor$stopStart != 0L) {
            CostRecorder.recordGoal((WrappedGoal)(Object)this, "goal_stop", System.nanoTime() - fragmentdoctor$stopStart);
            fragmentdoctor$stopStart = 0L;
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void fragmentdoctor$tickStart(CallbackInfo ci) {
        if (CostRecorder.active()) {
            fragmentdoctor$tickStart = System.nanoTime();
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void fragmentdoctor$tickEnd(CallbackInfo ci) {
        if (CostRecorder.active() && fragmentdoctor$tickStart != 0L) {
            CostRecorder.recordGoal((WrappedGoal)(Object)this, "goal_tick", System.nanoTime() - fragmentdoctor$tickStart);
            fragmentdoctor$tickStart = 0L;
        }
    }
}
