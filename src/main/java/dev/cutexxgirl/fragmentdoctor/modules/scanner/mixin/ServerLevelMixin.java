package dev.cutexxgirl.fragmentdoctor.modules.scanner.mixin;

import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.CostRecorder;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.ObservedTarget;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Unique
    private long fragmentdoctor$levelTickStart;
    @Unique
    private long fragmentdoctor$chunkTickStart;
    @Unique
    private long fragmentdoctor$entityTickStart;

    @Inject(method = "tick", at = @At("HEAD"))
    private void fragmentdoctor$levelTickStart(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (CostRecorder.active()) {
            fragmentdoctor$levelTickStart = System.nanoTime();
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void fragmentdoctor$levelTickEnd(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (CostRecorder.active() && fragmentdoctor$levelTickStart != 0L) {
            ServerLevel level = (ServerLevel)(Object)this;
            CostRecorder.record("section_cost", "level_tick", ObservedTarget.level(level), System.nanoTime() - fragmentdoctor$levelTickStart);
            fragmentdoctor$levelTickStart = 0L;
        }
    }

    @Inject(method = "tickChunk", at = @At("HEAD"))
    private void fragmentdoctor$chunkTickStart(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        if (CostRecorder.active()) {
            fragmentdoctor$chunkTickStart = System.nanoTime();
        }
    }

    @Inject(method = "tickChunk", at = @At("TAIL"))
    private void fragmentdoctor$chunkTickEnd(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        if (CostRecorder.active() && fragmentdoctor$chunkTickStart != 0L) {
            ServerLevel level = (ServerLevel)(Object)this;
            CostRecorder.record("section_cost", "chunk_tick", ObservedTarget.chunk(level, chunk), System.nanoTime() - fragmentdoctor$chunkTickStart);
            fragmentdoctor$chunkTickStart = 0L;
        }
    }

    @Inject(method = "tickNonPassenger", at = @At("HEAD"))
    private void fragmentdoctor$entityTickStart(Entity entity, CallbackInfo ci) {
        if (CostRecorder.active()) {
            fragmentdoctor$entityTickStart = System.nanoTime();
        }
    }

    @Inject(method = "tickNonPassenger", at = @At("TAIL"))
    private void fragmentdoctor$entityTickEnd(Entity entity, CallbackInfo ci) {
        if (CostRecorder.active() && fragmentdoctor$entityTickStart != 0L) {
            ServerLevel level = (ServerLevel)(Object)this;
            CostRecorder.record("entity_cost", "entity_tick", ObservedTarget.entity(level, entity), System.nanoTime() - fragmentdoctor$entityTickStart);
            fragmentdoctor$entityTickStart = 0L;
        }
    }
}
