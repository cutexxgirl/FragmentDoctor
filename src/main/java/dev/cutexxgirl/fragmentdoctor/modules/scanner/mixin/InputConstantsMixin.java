package dev.cutexxgirl.fragmentdoctor.modules.scanner.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.instrumentation.CostRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InputConstants.class)
public abstract class InputConstantsMixin {
    @Inject(method = "isKeyDown", at = @At("HEAD"))
    private static void fragmentdoctor$recordInvalidKeyPoll(long window, int key, CallbackInfoReturnable<Boolean> cir) {
        if (key == -1 && CostRecorder.active()) {
            CostRecorder.recordInvalidKeyPoll(window, key);
        }
    }
}
