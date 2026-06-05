package dev.cutexxgirl.fragmentdoctor.modules.scaner.mixin;

import dev.cutexxgirl.fragmentdoctor.modules.scaner.instrumentation.CostRecorder;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public abstract class WindowMixin {
    @Shadow
    private String errorSection;

    @Inject(method = "defaultErrorCallback", at = @At("HEAD"))
    private void fragmentdoctor$recordGlError(int error, long description, CallbackInfo ci) {
        if (!CostRecorder.active() || !fragmentdoctor$hasClientWorld()) {
            return;
        }
        CostRecorder.recordGlError(error, MemoryUtil.memUTF8(description), errorSection);
    }

    private static boolean fragmentdoctor$hasClientWorld() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        return level != null && player != null;
    }
}
