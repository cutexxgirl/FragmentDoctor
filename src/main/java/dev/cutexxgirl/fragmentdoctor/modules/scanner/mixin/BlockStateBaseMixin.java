package dev.cutexxgirl.fragmentdoctor.modules.scanner.mixin;

import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.CostRecorder;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.ObservedTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase")
public abstract class BlockStateBaseMixin {
    @Unique
    private long fragmentdoctor$blockTickStart;

    @Inject(method = "tick", at = @At("HEAD"))
    private void fragmentdoctor$blockTickStart(ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (CostRecorder.active()) {
            fragmentdoctor$blockTickStart = System.nanoTime();
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void fragmentdoctor$blockTickEnd(ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (CostRecorder.active() && fragmentdoctor$blockTickStart != 0L) {
            BlockState state = (BlockState)(Object)this;
            CostRecorder.record("section_cost", "block_tick", ObservedTarget.blockTick(level, state, pos), System.nanoTime() - fragmentdoctor$blockTickStart);
            fragmentdoctor$blockTickStart = 0L;
        }
    }
}
