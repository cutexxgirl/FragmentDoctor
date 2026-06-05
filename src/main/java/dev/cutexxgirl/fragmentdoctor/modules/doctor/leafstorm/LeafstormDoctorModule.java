package dev.cutexxgirl.fragmentdoctor.modules.doctor.leafstorm;

import dev.cutexxgirl.fragmentdoctor.FragmentDoctor;
import dev.cutexxgirl.fragmentdoctor.modules.DoctorModule;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

public final class LeafstormDoctorModule implements DoctorModule {
    public static final String ID = "doctor.leafstorm";

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
        FragmentDoctor.LOGGER.info("Leafstorm doctor module is ready. enabled={}", LeafstormConfig.enabled());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        LeafstormDoctorCommands.register(event.getDispatcher());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        LeafstormSchedulerReplacement.clearForServerStop();
    }
}
