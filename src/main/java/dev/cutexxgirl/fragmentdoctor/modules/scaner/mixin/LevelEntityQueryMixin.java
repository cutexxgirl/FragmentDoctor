package dev.cutexxgirl.fragmentdoctor.modules.scaner.mixin;

import dev.cutexxgirl.fragmentdoctor.modules.scaner.instrumentation.CostRecorder;
import java.util.List;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelEntityQueryMixin {
    @Unique
    private long fragmentdoctor$entityQueryStart;
    @Unique
    private long fragmentdoctor$typedEntityQueryStart;
    @Unique
    private int fragmentdoctor$typedEntityQueryInitialSize;

    @Inject(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;", at = @At("HEAD"))
    private void fragmentdoctor$entityQueryStart(@Nullable Entity entity, AABB boundingBox, Predicate<? super Entity> predicate, CallbackInfoReturnable<List<Entity>> cir) {
        if (CostRecorder.active()) {
            fragmentdoctor$entityQueryStart = System.nanoTime();
        }
    }

    @Inject(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;", at = @At("RETURN"))
    private void fragmentdoctor$entityQueryEnd(@Nullable Entity entity, AABB boundingBox, Predicate<? super Entity> predicate, CallbackInfoReturnable<List<Entity>> cir) {
        if (CostRecorder.active() && fragmentdoctor$entityQueryStart != 0L) {
            CostRecorder.recordEntityQuery((Level)(Object)this, boundingBox, cir.getReturnValue().size(), "get_entities", System.nanoTime() - fragmentdoctor$entityQueryStart);
            fragmentdoctor$entityQueryStart = 0L;
        }
    }

    @Inject(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;Ljava/util/List;I)V", at = @At("HEAD"))
    private <T extends Entity> void fragmentdoctor$typedEntityQueryStart(EntityTypeTest<Entity, T> entityTypeTest, AABB bounds, Predicate<? super T> predicate, List<? super T> output, int maxResults, CallbackInfo ci) {
        if (CostRecorder.active()) {
            fragmentdoctor$typedEntityQueryStart = System.nanoTime();
            fragmentdoctor$typedEntityQueryInitialSize = output.size();
        }
    }

    @Inject(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;Ljava/util/List;I)V", at = @At("TAIL"))
    private <T extends Entity> void fragmentdoctor$typedEntityQueryEnd(EntityTypeTest<Entity, T> entityTypeTest, AABB bounds, Predicate<? super T> predicate, List<? super T> output, int maxResults, CallbackInfo ci) {
        if (CostRecorder.active() && fragmentdoctor$typedEntityQueryStart != 0L) {
            int resultCount = Math.max(0, output.size() - fragmentdoctor$typedEntityQueryInitialSize);
            CostRecorder.recordEntityQuery((Level)(Object)this, bounds, resultCount, "get_entities_typed", System.nanoTime() - fragmentdoctor$typedEntityQueryStart);
            fragmentdoctor$typedEntityQueryStart = 0L;
        }
    }
}
