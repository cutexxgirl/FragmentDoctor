package dev.cutexxgirl.fragmentdoctor.modules.scanner.mixin;

import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.CostRecorder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.sensing.Sensor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Brain.class)
public abstract class BrainMixin {
    @WrapOperation(
            method = "tickSensors",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/sensing/Sensor;tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;)V")
    )
    private void fragmentdoctor$profileSensor(Sensor<LivingEntity> sensor, ServerLevel level, LivingEntity entity, Operation<Void> original) {
        if (!CostRecorder.active()) {
            original.call(sensor, level, entity);
            return;
        }
        long start = System.nanoTime();
        try {
            original.call(sensor, level, entity);
        } finally {
            CostRecorder.recordBrainSensor(sensor, entity, System.nanoTime() - start);
        }
    }

    @WrapOperation(
            method = "startEachNonRunningBehavior",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/behavior/BehaviorControl;tryStart(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;J)Z")
    )
    private boolean fragmentdoctor$profileBehaviorStart(BehaviorControl<LivingEntity> behavior, ServerLevel level, LivingEntity entity, long gameTime, Operation<Boolean> original) {
        if (!CostRecorder.active()) {
            return original.call(behavior, level, entity, gameTime);
        }
        long start = System.nanoTime();
        try {
            return original.call(behavior, level, entity, gameTime);
        } finally {
            CostRecorder.recordBrainBehavior(behavior, entity, "behavior_try_start", System.nanoTime() - start);
        }
    }

    @WrapOperation(
            method = "tickEachRunningBehavior",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/behavior/BehaviorControl;tickOrStop(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;J)V")
    )
    private void fragmentdoctor$profileBehaviorTick(BehaviorControl<LivingEntity> behavior, ServerLevel level, LivingEntity entity, long gameTime, Operation<Void> original) {
        if (!CostRecorder.active()) {
            original.call(behavior, level, entity, gameTime);
            return;
        }
        long start = System.nanoTime();
        try {
            original.call(behavior, level, entity, gameTime);
        } finally {
            CostRecorder.recordBrainBehavior(behavior, entity, "behavior_tick_or_stop", System.nanoTime() - start);
        }
    }
}
