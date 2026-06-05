package dev.cutexxgirl.fragmentdoctor.modules.scanner.mixin;

import dev.cutexxgirl.fragmentdoctor.modules.doctor.create.CreateSmoother;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.CostRecorder;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.ObservedTarget;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class LevelChunkBoundTickingBlockEntityMixin {
    @Unique
    private long fragmentdoctor$blockEntityTickStart;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void fragmentdoctor$blockEntityTickStart(CallbackInfo ci) {
        BlockEntity blockEntity = ((LevelChunkBoundTickingBlockEntityAccessor)this).fragmentdoctor$blockEntity();
        if (CreateSmoother.shouldSkipBlockEntityTick(blockEntity)) {
            ci.cancel();
            return;
        }
        if (CostRecorder.active() && fragmentdoctor$isServerBlockEntity(blockEntity)) {
            fragmentdoctor$blockEntityTickStart = System.nanoTime();
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void fragmentdoctor$blockEntityTickEnd(CallbackInfo ci) {
        BlockEntity blockEntity = ((LevelChunkBoundTickingBlockEntityAccessor)this).fragmentdoctor$blockEntity();
        if (CostRecorder.active() && fragmentdoctor$blockEntityTickStart != 0L && fragmentdoctor$isServerBlockEntity(blockEntity)) {
            long duration = System.nanoTime() - fragmentdoctor$blockEntityTickStart;
            fragmentdoctor$blockEntityTickStart = 0L;
            if (!CostRecorder.shouldRecord("block_entity_cost", "block_entity_tick", duration)) {
                return;
            }
            CostRecorder.record("block_entity_cost", "block_entity_tick", ObservedTarget.blockEntity(blockEntity), duration);
        }
    }

    @Unique
    private boolean fragmentdoctor$isServerBlockEntity(BlockEntity blockEntity) {
        return blockEntity != null && blockEntity.getLevel() instanceof ServerLevel;
    }
}
