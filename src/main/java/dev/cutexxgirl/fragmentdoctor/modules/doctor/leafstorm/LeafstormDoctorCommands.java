package dev.cutexxgirl.fragmentdoctor.modules.doctor.leafstorm;

import com.mojang.brigadier.CommandDispatcher;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class LeafstormDoctorCommands {
    private LeafstormDoctorCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("fragmentdoctor")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("doctor")
                        .then(Commands.literal("leafstorm")
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
        LeafstormDoctor.Status status = LeafstormDoctor.status();
        source.sendSuccess(() -> Component.literal("[Fragment Doctor] leafstorm limiter enabled=" + status.enabled()
                + " mode=" + status.mode()
                + " trend=" + status.queueTrend()
                + " last_mspt=" + String.format(Locale.ROOT, "%.2f", status.lastMspt())
                + " queue=" + status.queueDepth()
                + " budget=" + readableBudget(status.budget())
                + " remaining=" + readableBudget(status.remainingBudget())
                + " seen=" + status.seenLeaves()
                + " allowed=" + status.allowedLeaves()
                + " deferred=" + status.deferredLeaves()
                + " deduped=" + status.deduplicatedLeaves()
                + " dedup_keys=" + status.dedupKeyCount()
                + " top_leaves=" + status.topLeaves()), false);
        return status.enabled() ? 1 : 0;
    }

    private static int setEnabled(CommandSourceStack source, boolean enabled) {
        LeafstormConfig.setEnabled(enabled);
        source.sendSuccess(() -> Component.literal("[Fragment Doctor] leafstorm limiter " + (enabled ? "enabled" : "disabled")
                + " in fragmentdoctor-common.toml"), true);
        return 1;
    }

    private static String readableBudget(int budget) {
        return budget < 0 ? "unlimited" : Integer.toString(budget);
    }
}
