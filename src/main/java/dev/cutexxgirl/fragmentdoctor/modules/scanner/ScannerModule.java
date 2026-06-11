package dev.cutexxgirl.fragmentdoctor.modules.scanner;

import dev.cutexxgirl.fragmentdoctor.FragmentDoctor;
import dev.cutexxgirl.fragmentdoctor.modules.DoctorModule;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.command.ScannerCommands;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.session.ScannerSessionManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class ScannerModule implements DoctorModule {
    public static final String ID = "scanner";

    private final ScannerSessionManager sessionManager = ScannerRuntime.sessions();

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
        FragmentDoctor.LOGGER.info("Scanner module is ready.");
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        ScannerCommands.register(event.getDispatcher(), sessionManager);
    }
}
