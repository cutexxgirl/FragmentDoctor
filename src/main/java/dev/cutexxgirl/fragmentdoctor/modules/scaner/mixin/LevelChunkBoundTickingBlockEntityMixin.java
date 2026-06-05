package dev.cutexxgirl.fragmentdoctor.modules.scaner.mixin;

import dev.cutexxgirl.fragmentdoctor.modules.scaner.instrumentation.CostRecorder;
import dev.cutexxgirl.fragmentdoctor.modules.scaner.instrumentation.ObservedTarget;
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

    @Inject(method = "tick", at = @At("HEAD"))
    private void fragmentdoctor$blockEntityTickStart(CallbackInfo ci) {
        if (CostRecorder.active() && fragmentdoctor$isServerBlockEntity()) {
            fragmentdoctor$blockEntityTickStart = System.nanoTime();
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void fragmentdoctor$blockEntityTickEnd(CallbackInfo ci) {
        if (CostRecorder.active() && fragmentdoctor$blockEntityTickStart != 0L && fragmentdoctor$isServerBlockEntity()) {
            BlockEntity blockEntity = ((LevelChunkBoundTickingBlockEntityAccessor)this).fragmentdoctor$blockEntity();
            CostRecorder.record("block_entity_cost", "block_entity_tick", ObservedTarget.blockEntity(blockEntity), System.nanoTime() - fragmentdoctor$blockEntityTickStart);
            fragmentdoctor$blockEntityTickStart = 0L;
        }
    }

    @Unique
    private boolean fragmentdoctor$isServerBlockEntity() {
        BlockEntity blockEntity = ((LevelChunkBoundTickingBlockEntityAccessor)this).fragmentdoctor$blockEntity();
        return blockEntity != null && blockEntity.getLevel() instanceof ServerLevel;
    }
}
