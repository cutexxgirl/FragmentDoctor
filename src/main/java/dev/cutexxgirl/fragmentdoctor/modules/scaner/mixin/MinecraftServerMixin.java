package dev.cutexxgirl.fragmentdoctor.modules.scaner.mixin;

import dev.cutexxgirl.fragmentdoctor.modules.doctor.leafstorm.LeafstormDoctor;
import dev.cutexxgirl.fragmentdoctor.modules.scaner.ScanerRuntime;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void fragmentdoctor$scanerTickStart(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer)(Object)this;
        LeafstormDoctor.onServerTickStart(server);
        ScanerRuntime.sessions().onServerTickStart(server);
    }

    @Inject(method = "tickServer", at = @At("TAIL"))
    private void fragmentdoctor$scanerTickEnd(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer)(Object)this;
        ScanerRuntime.sessions().onServerTickEnd(server);
        LeafstormDoctor.onServerTickEnd(server);
    }
}
