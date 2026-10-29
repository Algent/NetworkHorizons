package dev.aruka.networkhorizons.command;

import static com.gtnewhorizon.gtnhlib.util.CommandUtils.argument;
import static com.gtnewhorizon.gtnhlib.util.CommandUtils.literal;

import java.io.File;
import java.util.function.Supplier;

import net.minecraft.command.ICommandSender;

import com.gtnewhorizon.gtnhlib.brigadier.BrigadierApi;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import dev.aruka.networkhorizons.config.StatsConfig;
import dev.aruka.networkhorizons.stats.Window;

/** Server command, through GTNHLib's Brigadier: {@code /neth stats [window] [top] [player] | dump | reset}. */
public final class NetworkHorizonsCommand {

    public static final String NAME = "neth";
    private static final String ARG_TOP = "top";
    private static final String ARG_PLAYER = "player";

    private static final SuggestionProvider<ICommandSender> CONNECTIONS = (ctx, builder) -> {
        for (String label : TrafficCommands.connectionLabels()) builder.suggest(label);
        return builder.buildFuture();
    };

    private NetworkHorizonsCommand() {}

    /** Call after GTNHLib's {@code BrigadierApi.init()}, which runs at server about to start. */
    public static void register(Supplier<File> baseDir) {
        LiteralArgumentBuilder<ICommandSender> stats = literal("stats")
            .executes(
                ctx -> TrafficCommands.stats(ctx.getSource(), false, null, StatsConfig.defaultTop, Window.LAST_60S))
            .then(player(Window.LAST_60S, null));
        for (Window window : Window.values()) {
            stats.then(
                literal(window.id)
                    .executes(
                        ctx -> TrafficCommands.stats(ctx.getSource(), false, null, StatsConfig.defaultTop, window))
                    .then(
                        argument(ARG_TOP, IntegerArgumentType.integer(1, 1000))
                            .executes(
                                ctx -> TrafficCommands.stats(
                                    ctx.getSource(),
                                    false,
                                    null,
                                    IntegerArgumentType.getInteger(ctx, ARG_TOP),
                                    window))
                            .then(player(window, ARG_TOP))));
        }

        BrigadierApi.getCommandDispatcher()
            .register(
                literal(NAME).requires(src -> src.canCommandSenderUseCommand(2, NAME))
                    .executes(ctx -> {
                        TrafficCommands.send(ctx.getSource(), TrafficCommands.usage(NAME));
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(stats)
                    .then(literal("dump").executes(ctx -> TrafficCommands.dump(ctx.getSource(), false, baseDir.get())))
                    .then(literal("reset").executes(ctx -> TrafficCommands.reset(ctx.getSource()))));
    }

    /** Player argument; uses the top argument when {@code topArg} is not null. */
    private static RequiredArgumentBuilder<ICommandSender, String> player(Window window, String topArg) {
        return argument(ARG_PLAYER, StringArgumentType.word()).suggests(CONNECTIONS)
            .executes(
                ctx -> TrafficCommands.stats(
                    ctx.getSource(),
                    false,
                    StringArgumentType.getString(ctx, ARG_PLAYER),
                    topArg == null ? StatsConfig.defaultTop : IntegerArgumentType.getInteger(ctx, topArg),
                    window));
    }
}
