package dev.cutexxgirl.fragmentdoctor.modules.scaner;

import dev.cutexxgirl.fragmentdoctor.FragmentDoctor;
import dev.cutexxgirl.fragmentdoctor.modules.DoctorModule;
import dev.cutexxgirl.fragmentdoctor.modules.scaner.command.ScanerCommands;
import dev.cutexxgirl.fragmentdoctor.modules.scaner.session.ScanerSessionManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class ScanerModule implements DoctorModule {
    public static final String ID = "scaner";

    private final ScanerSessionManager sessionManager = ScanerRuntime.sessions();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void register(IEventBus modEventBus, IEventBus gameEventBus) {
        modEventBus.addListener(this::onCommonSetup);
        gameEventBus.addListener(this::onRegisterCommands);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        FragmentDoctor.LOGGER.info("Scaner module is ready.");
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        ScanerCommands.register(event.getDispatcher(), sessionManager);
    }
}
