package dev.cutexxgirl.fragmentdoctor.modules.scaner.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.cutexxgirl.fragmentdoctor.modules.scaner.session.ScanerSessionManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class ScanerCommands {
    private ScanerCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, ScanerSessionManager sessions) {
        dispatcher.register(Commands.literal("fragmentdoctor")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("scaner")
                        .then(Commands.literal("start")
                                .executes(context -> start(context.getSource(), sessions, 60))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(5, 600))
                                        .executes(context -> start(context.getSource(), sessions, IntegerArgumentType.getInteger(context, "seconds")))))
                        .then(Commands.literal("stop")
                                .executes(context -> stop(context.getSource(), sessions)))
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource(), sessions)))
                        .then(Commands.literal("last")
                                .executes(context -> last(context.getSource(), sessions)))));
    }

    private static int start(CommandSourceStack source, ScanerSessionManager sessions, int seconds) {
        var result = sessions.start(source.getServer(), seconds);
        if (result.success()) {
            source.sendSuccess(() -> Component.literal("[Fragment Doctor] " + result.message() + ". log=" + result.logPath()), true);
            return 1;
        }
        source.sendFailure(Component.literal("[Fragment Doctor] " + result.message()));
        return 0;
    }

    private static int stop(CommandSourceStack source, ScanerSessionManager sessions) {
        var result = sessions.stop(source.getServer(), "command_stop");
        if (result.success()) {
            source.sendSuccess(() -> Component.literal("[Fragment Doctor] " + result.message() + ". summary=" + result.summaryPath()), true);
            return 1;
        }
        source.sendFailure(Component.literal("[Fragment Doctor] " + result.message()));
        return 0;
    }

    private static int status(CommandSourceStack source, ScanerSessionManager sessions) {
        var status = sessions.status();
        if (status.active()) {
            source.sendSuccess(() -> Component.literal("[Fragment Doctor] scaner running: " + status.sessionId()
                    + " time " + String.format("%.1f", status.elapsedSeconds()) + "/" + status.durationSeconds() + "s"
                    + " tick " + status.elapsedTicks() + "/" + status.durationTicks()), false);
        } else {
            source.sendSuccess(() -> Component.literal("[Fragment Doctor] scaner idle. last=" + status.lastSummaryPath()), false);
        }
        return status.active() ? 1 : 0;
    }

    private static int last(CommandSourceStack source, ScanerSessionManager sessions) {
        var last = sessions.lastSummaryPath();
        if (last.isPresent()) {
            source.sendSuccess(() -> Component.literal("[Fragment Doctor] last scaner summary: " + last.get()), false);
            return 1;
        }
        source.sendFailure(Component.literal("[Fragment Doctor] no scaner summary exists yet"));
        return 0;
    }
}
