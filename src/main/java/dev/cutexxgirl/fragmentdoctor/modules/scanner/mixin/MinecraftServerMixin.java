package dev.cutexxgirl.fragmentdoctor.modules.scanner.mixin;

import dev.cutexxgirl.fragmentdoctor.modules.doctor.create.CreateSmoother;
import dev.cutexxgirl.fragmentdoctor.modules.doctor.leafstorm.LeafstormSchedulerReplacement;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.ScannerRuntime;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void fragmentdoctor$scannerTickStart(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer)(Object)this;
        LeafstormSchedulerReplacement.onServerTickStart();
        CreateSmoother.onServerTickStart();
        ScannerRuntime.sessions().onServerTickStart(server);
    }

    @Inject(method = "tickServer", at = @At("TAIL"))
    private void fragmentdoctor$scannerTickEnd(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer)(Object)this;
        ScannerRuntime.sessions().onServerTickEnd(server);
        CreateSmoother.onServerTickEnd(server);
        LeafstormSchedulerReplacement.onServerTickEnd(server);
    }
}
