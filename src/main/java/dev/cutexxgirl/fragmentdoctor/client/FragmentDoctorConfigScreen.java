package dev.cutexxgirl.fragmentdoctor.client;

import dev.cutexxgirl.fragmentdoctor.modules.doctor.leafstorm.LeafstormConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

public final class FragmentDoctorConfigScreen {
    private FragmentDoctorConfigScreen() {
    }

    public static void register(ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, (IConfigScreenFactory)(container, parent) -> build(parent));
    }

    private static Screen build(Screen parent) {
        LeafstormConfig.refreshMutableValuesFromDisk();
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("Fragment Doctor"))
                .setSavingRunnable(LeafstormConfig.SPEC::save);
        ConfigEntryBuilder entries = builder.entryBuilder();
        addLeafstorm(builder, entries);
        addCreate(builder, entries);
        return builder.build();
    }

    private static void addLeafstorm(ConfigBuilder builder, ConfigEntryBuilder entries) {
        ConfigCategory category = builder.getOrCreateCategory(Component.literal("Leafstorm"));
        LeafstormConfig.Values values = LeafstormConfig.VALUES;
        category.addEntry(entries.startBooleanToggle(Component.literal("Enabled"), values.schedulerEnabled.get())
                .setDefaultValue(false)
                .setSaveConsumer(value -> {
                    values.schedulerEnabled.set(value);
                    if (value) {
                        values.schedulerShadowMode.set(false);
                    }
                })
                .build());
        category.addEntry(entries.startBooleanToggle(Component.literal("Shadow mode"), values.schedulerShadowMode.get())
                .setDefaultValue(false)
                .setSaveConsumer(values.schedulerShadowMode::set)
                .build());
        category.addEntry(entries.startBooleanToggle(Component.literal("Intercept vanilla leaf schedules"), values.schedulerInterceptVanillaLeafSchedules.get())
                .setDefaultValue(true)
                .setSaveConsumer(values.schedulerInterceptVanillaLeafSchedules::set)
                .build());
        category.addEntry(entries.startIntField(Component.literal("Global budget"), values.schedulerGlobalBudget.get())
                .setDefaultValue(128)
                .setMin(0)
                .setMax(65_536)
                .setSaveConsumer(values.schedulerGlobalBudget::set)
                .build());
        category.addEntry(entries.startIntField(Component.literal("Scan budget"), values.schedulerScanBudget.get())
                .setDefaultValue(1024)
                .setMin(1)
                .setMax(262_144)
                .setSaveConsumer(values.schedulerScanBudget::set)
                .build());
        category.addEntry(entries.startIntField(Component.literal("Per chunk budget"), values.schedulerPerChunkBudget.get())
                .setDefaultValue(4)
                .setMin(1)
                .setMax(65_536)
                .setSaveConsumer(values.schedulerPerChunkBudget::set)
                .build());
        category.addEntry(entries.startDoubleField(Component.literal("Max drain ms per tick"), values.schedulerMaxDrainMillisPerTick.get())
                .setDefaultValue(2.0D)
                .setMin(0.0D)
                .setMax(1000.0D)
                .setSaveConsumer(values.schedulerMaxDrainMillisPerTick::set)
                .build());
        category.addEntry(entries.startDoubleField(Component.literal("Healthy MSPT threshold"), values.schedulerHealthyMsptThreshold.get())
                .setDefaultValue(30.0D)
                .setMin(1.0D)
                .setMax(5000.0D)
                .setSaveConsumer(values.schedulerHealthyMsptThreshold::set)
                .build());
        category.addEntry(entries.startDoubleField(Component.literal("Soft MSPT threshold"), values.schedulerSoftMsptThreshold.get())
                .setDefaultValue(45.0D)
                .setMin(1.0D)
                .setMax(5000.0D)
                .setSaveConsumer(values.schedulerSoftMsptThreshold::set)
                .build());
        category.addEntry(entries.startDoubleField(Component.literal("Hard MSPT threshold"), values.schedulerHardMsptThreshold.get())
                .setDefaultValue(70.0D)
                .setMin(1.0D)
                .setMax(5000.0D)
                .setSaveConsumer(values.schedulerHardMsptThreshold::set)
                .build());
        category.addEntry(entries.startIntField(Component.literal("Max jobs"), values.schedulerMaxJobs.get())
                .setDefaultValue(500_000)
                .setMin(1_000)
                .setMax(2_000_000)
                .setSaveConsumer(values.schedulerMaxJobs::set)
                .build());
        category.addEntry(entries.startIntField(Component.literal("Job TTL ticks"), values.schedulerJobTtlTicks.get())
                .setDefaultValue(20 * 60)
                .setMin(20)
                .setMax(20 * 60 * 30)
                .setSaveConsumer(values.schedulerJobTtlTicks::set)
                .build());
        category.addEntry(entries.startIntField(Component.literal("Hot chunk schedule threshold"), values.schedulerHotChunkScheduleThreshold.get())
                .setDefaultValue(256)
                .setMin(1)
                .setMax(65_536)
                .setSaveConsumer(values.schedulerHotChunkScheduleThreshold::set)
                .build());
        category.addEntry(entries.startIntField(Component.literal("Hot chunk delay ticks"), values.schedulerHotChunkDelayTicks.get())
                .setDefaultValue(20)
                .setMin(1)
                .setMax(20 * 60)
                .setSaveConsumer(values.schedulerHotChunkDelayTicks::set)
                .build());
        category.addEntry(entries.startIntField(Component.literal("Hot chunk max delay ticks"), values.schedulerHotChunkMaxDelayTicks.get())
                .setDefaultValue(20 * 10)
                .setMin(1)
                .setMax(20 * 60 * 10)
                .setSaveConsumer(values.schedulerHotChunkMaxDelayTicks::set)
                .build());
        category.addEntry(entries.startIntField(Component.literal("Log every ticks"), values.schedulerLogEveryTicks.get())
                .setDefaultValue(100)
                .setMin(1)
                .setMax(20 * 60)
                .setSaveConsumer(values.schedulerLogEveryTicks::set)
                .build());
    }

    private static void addCreate(ConfigBuilder builder, ConfigEntryBuilder entries) {
        ConfigCategory category = builder.getOrCreateCategory(Component.literal("Create smoother"));
        LeafstormConfig.Values values = LeafstormConfig.VALUES;
        category.addEntry(entries.startBooleanToggle(Component.literal("Enabled"), values.createSmootherEnabled.get())
                .setDefaultValue(false)
                .setSaveConsumer(values.createSmootherEnabled::set)
                .build());
        category.addEntry(entries.startIntField(Component.literal("Block entity budget"), values.createSmootherBlockEntityBudget.get())
                .setDefaultValue(32)
                .setMin(0)
                .setMax(65_536)
                .setSaveConsumer(values.createSmootherBlockEntityBudget::set)
                .build());
        category.addEntry(entries.startIntField(Component.literal("Entity budget"), values.createSmootherEntityBudget.get())
                .setDefaultValue(8)
                .setMin(0)
                .setMax(65_536)
                .setSaveConsumer(values.createSmootherEntityBudget::set)
                .build());
        category.addEntry(entries.startIntField(Component.literal("Warmup ticks"), values.createSmootherWarmupTicks.get())
                .setDefaultValue(20 * 10)
                .setMin(1)
                .setMax(20 * 60 * 10)
                .setSaveConsumer(values.createSmootherWarmupTicks::set)
                .build());
        category.addEntry(entries.startIntField(Component.literal("High risk budget"), values.createSmootherHighRiskBudget.get())
                .setDefaultValue(1)
                .setMin(0)
                .setMax(65_536)
                .setSaveConsumer(values.createSmootherHighRiskBudget::set)
                .build());
        category.addEntry(entries.startIntField(Component.literal("High risk initial delay ticks"), values.createSmootherHighRiskInitialDelayTicks.get())
                .setDefaultValue(20 * 5)
                .setMin(0)
                .setMax(20 * 60 * 10)
                .setSaveConsumer(values.createSmootherHighRiskInitialDelayTicks::set)
                .build());
        category.addEntry(entries.startIntField(Component.literal("Max consecutive skips"), values.createSmootherMaxConsecutiveSkips.get())
                .setDefaultValue(4)
                .setMin(0)
                .setMax(200)
                .setSaveConsumer(values.createSmootherMaxConsecutiveSkips::set)
                .build());
        category.addEntry(entries.startDoubleField(Component.literal("MSPT threshold"), values.createSmootherMsptThreshold.get())
                .setDefaultValue(35.0D)
                .setMin(1.0D)
                .setMax(5000.0D)
                .setSaveConsumer(values.createSmootherMsptThreshold::set)
                .build());
        category.addEntry(entries.startIntField(Component.literal("Log every ticks"), values.createSmootherLogEveryTicks.get())
                .setDefaultValue(100)
                .setMin(1)
                .setMax(20 * 60)
                .setSaveConsumer(values.createSmootherLogEveryTicks::set)
                .build());
    }
}
