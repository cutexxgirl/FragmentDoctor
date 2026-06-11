package dev.cutexxgirl.fragmentdoctor;

import dev.cutexxgirl.fragmentdoctor.modules.DoctorModule;
import dev.cutexxgirl.fragmentdoctor.modules.doctor.create.CreateSmootherModule;
import dev.cutexxgirl.fragmentdoctor.modules.doctor.leafstorm.LeafstormConfig;
import dev.cutexxgirl.fragmentdoctor.modules.doctor.leafstorm.LeafstormDoctorModule;
import dev.cutexxgirl.fragmentdoctor.modules.scanner.ScannerModule;
import java.util.List;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(FragmentDoctor.MOD_ID)
public final class FragmentDoctor {
    public static final String MOD_ID = "fragmentdoctor";
    public static final Logger LOGGER = LoggerFactory.getLogger("FragmentDoctor");

    private final List<DoctorModule> modules = List.of(
            new LeafstormDoctorModule(),
            new CreateSmootherModule(),
            new ScannerModule()
    );

    public FragmentDoctor(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, LeafstormConfig.SPEC);
        if (FMLEnvironment.dist == Dist.CLIENT && ModList.get().isLoaded("cloth_config")) {
            dev.cutexxgirl.fragmentdoctor.client.FragmentDoctorConfigScreen.register(modContainer);
        }
        LOGGER.info("Fragment Doctor is starting with {} module(s).", modules.size());
        modules.forEach(module -> {
            LOGGER.info("Loading module: {}", module.id());
            module.register(modEventBus, NeoForge.EVENT_BUS);
        });
    }
}
