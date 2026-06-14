package dev.cutexxgirl.fragmentdoctor.modules.scanner.mixin;

import java.util.List;
import java.util.Queue;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelChunkTicks.class)
public interface LevelChunkTicksAccessor<T> {
    @Accessor("tickQueue")
    Queue<ScheduledTick<T>> fragmentdoctor$getTickQueue();

    @Accessor("pendingTicks")
    List<SavedTick<T>> fragmentdoctor$getPendingTicks();
}
