package dev.cutexxgirl.fragmentdoctor.modules.doctor.create;

import dev.cutexxgirl.fragmentdoctor.FragmentDoctor;
import dev.cutexxgirl.fragmentdoctor.modules.DoctorModule;
import dev.cutexxgirl.fragmentdoctor.modules.doctor.leafstorm.LeafstormConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

public final class CreateSmootherModule implements DoctorModule {
    public static final String ID = "doctor.create";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void register(IEventBus modEventBus, IEventBus gameEventBus) {
        modEventBus.addListener(this::onCommonSetup);
        gameEventBus.addListener(this::onRegisterCommands);
        gameEventBus.addListener(this::onServerStopping);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        FragmentDoctor.LOGGER.info("Create smoother module is ready. enabled={}", LeafstormConfig.VALUES.createSmootherEnabled.get());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CreateSmootherCommands.register(event.getDispatcher());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        CreateSmoother.clearForServerStop();
    }
}
