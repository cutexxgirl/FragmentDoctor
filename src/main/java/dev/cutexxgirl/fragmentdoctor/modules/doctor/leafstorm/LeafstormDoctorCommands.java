package dev.cutexxgirl.fragmentdoctor.modules.doctor.leafstorm;

import com.mojang.brigadier.CommandDispatcher;
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
        LeafstormSchedulerReplacement.Status scheduler = LeafstormSchedulerReplacement.status();
        source.sendSuccess(() -> Component.literal("[Fragment Doctor] leafstorm scheduler configured=" + scheduler.configured()
                + " active=" + scheduler.active()
                + " shadow=" + scheduler.shadowMode()
                + " pending=" + scheduler.pendingJobs()
                + " heap=" + scheduler.heapEntries()
                + " budget=" + scheduler.globalBudget()
                + " scan_budget=" + scheduler.scanBudget()
                + " execution_used=" + scheduler.executionBudgetUsed()
                + " scan_used=" + scheduler.scanBudgetUsed()
                + " per_chunk=" + scheduler.perChunkBudget()
                + " intercepted=" + scheduler.intercepted()
                + " enqueued=" + scheduler.enqueued()
                + " coalesced=" + scheduler.coalesced()
                + " debounced=" + scheduler.debounced()
                + " executed=" + scheduler.executed()
                + " skipped_non_ticking_chunk=" + scheduler.skippedNonTickingChunk()
                + " skipped_hot_chunk=" + scheduler.skippedHotChunk()
                + " skipped_costly_chunk=" + scheduler.skippedCostlyChunk()
                + " costly_leaf_ticks=" + scheduler.costlyLeafTicks()
                + " last_costly_leaf_ms=" + scheduler.lastCostlyLeafMillis()
                + " dropped_stale=" + scheduler.droppedStale()
                + " dropped_overflow=" + scheduler.droppedOverflow()
                + " drain_paused=" + scheduler.drainPaused()
                + " drain_pause=" + scheduler.drainPauseReason()
                + " top_leaves=" + scheduler.topLeaves()
                + " top_chunks=" + scheduler.topChunks()), false);
        return scheduler.configured() ? 1 : 0;
    }

    private static int setEnabled(CommandSourceStack source, boolean enabled) {
        LeafstormConfig.setEnabled(enabled);
        source.sendSuccess(() -> Component.literal("[Fragment Doctor] leafstorm scheduler " + (enabled ? "enabled" : "disabled")
                + " in fragmentdoctor-common.toml"), true);
        return 1;
    }
}
