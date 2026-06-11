package dev.cutexxgirl.fragmentdoctor.modules.doctor.create;

import com.mojang.brigadier.CommandDispatcher;
import dev.cutexxgirl.fragmentdoctor.modules.doctor.leafstorm.LeafstormConfig;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class CreateSmootherCommands {
    private CreateSmootherCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("fragmentdoctor")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("doctor")
                        .then(Commands.literal("create")
                                .then(Commands.literal("status")
                                        .executes(context -> status(context.getSource())))
                                .then(Commands.literal("enable")
                                        .requires(source -> source.hasPermission(3))
                                        .executes(context -> setEnabled(context.getSource(), true)))
                                .then(Commands.literal("disable")
                                        .requires(source -> source.hasPermission(3))
                                        .executes(context -> setEnabled(context.getSource(), false))))));
    }

    private static int status(CommandSourceStack source) {
        CreateSmoother.Status status = CreateSmoother.status();
        source.sendSuccess(() -> Component.literal("[Fragment Doctor] create smoother enabled=" + status.enabled()
                + " pressured=" + status.pressured()
                + " last_mspt=" + String.format(Locale.ROOT, "%.2f", status.lastMspt())
                + " be_budget=" + status.blockEntityBudget()
                + " entity_budget=" + status.entityBudget()
                + " be_seen=" + status.seenBlockEntities()
                + " be_allowed=" + status.allowedBlockEntities()
                + " be_skipped=" + status.skippedBlockEntities()
                + " entity_seen=" + status.seenEntities()
                + " entity_allowed=" + status.allowedEntities()
                + " entity_skipped=" + status.skippedEntities()
                + " forced=" + status.forcedTicks()
                + " tracked_be=" + status.trackedBlockEntities()
                + " tracked_entities=" + status.trackedEntities()), false);
        return status.enabled() ? 1 : 0;
    }

    private static int setEnabled(CommandSourceStack source, boolean enabled) {
        LeafstormConfig.setCreateSmootherEnabled(enabled);
        source.sendSuccess(() -> Component.literal("[Fragment Doctor] create smoother " + (enabled ? "enabled" : "disabled")
                + " in fragmentdoctor-common.toml"), true);
        return 1;
    }
}
