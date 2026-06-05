package dev.cutexxgirl.fragmentdoctor.modules;

import net.neoforged.bus.api.IEventBus;

public interface DoctorModule {
    String id();

    void register(IEventBus modEventBus, IEventBus gameEventBus);
}
