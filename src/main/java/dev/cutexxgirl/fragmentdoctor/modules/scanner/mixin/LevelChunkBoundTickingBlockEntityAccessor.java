package dev.cutexxgirl.fragmentdoctor.modules.scanner.mixin;

import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public interface LevelChunkBoundTickingBlockEntityAccessor {
    @Accessor("blockEntity")
    BlockEntity fragmentdoctor$blockEntity();
}
