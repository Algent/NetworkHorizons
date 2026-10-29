package dev.aruka.networkhorizons.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;

import com.gtnewhorizon.gtnhlib.commands.GTNHClientCommand;

import dev.aruka.networkhorizons.config.EstimatorConfig;
import dev.aruka.networkhorizons.config.StatsConfig;
import dev.aruka.networkhorizons.stats.Window;

/** {@code /nethc}: the client's own connections (Brigadier in GTNHLib is server side only). */
public class ClientTrafficCommand extends GTNHClientCommand {

    public static final String NAME = "nethc";

    @Override
    public String getCommandName() {
        return NAME;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return TrafficCommands.usage(NAME);
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) throw new WrongUsageException(getCommandUsage(sender));
        switch (args[0]) {
            case "stats": {
                Window window = Window.LAST_60S;
                int top = StatsConfig.defaultTop;
                String player = null;
                for (int i = 1; i < args.length; i++) {
                    Window w = Window.byId(args[i]);
                    if (w != null) window = w;
                    else if (args[i].matches("\\d+")) top = Math.max(1, Integer.parseInt(args[i]));
                    else player = args[i];
                }
                TrafficCommands.stats(sender, true, player, top, window);
                break;
            }
            case "estimate":
                if (args.length >= 2 && "start".equals(args[1])) {
                    int seconds = args.length >= 3 ? parseIntBounded(sender, args[2], 10, 86400)
                        : EstimatorConfig.defaultSeconds;
                    EstimateCommands.start(sender, true, null, seconds);
                } else if (args.length >= 2 && "stop".equals(args[1])) {
                    EstimateCommands.stop(sender, true, null);
                } else {
                    EstimateCommands.show(sender, true, null);
                }
                break;
            case "dump":
                TrafficCommands.dump(sender, true, Minecraft.getMinecraft().mcDataDir);
                break;
            case "reset":
                TrafficCommands.reset(sender);
                break;
            default:
                throw new WrongUsageException(getCommandUsage(sender));
        }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "stats", "estimate", "dump", "reset");
        if (args.length == 2 && "estimate".equals(args[0]))
            return getListOfStringsMatchingLastWord(args, "start", "stop");
        if ("stats".equals(args[0])) {
            List<String> options = new ArrayList<>(TrafficCommands.connectionLabels());
            for (Window w : Window.values()) options.add(w.id);
            return getListOfStringsMatchingLastWord(args, options.toArray(new String[0]));
        }
        return Collections.emptyList();
    }
}
