package dev.aruka.networkhorizons.command;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import com.mojang.brigadier.Command;

import dev.aruka.networkhorizons.config.StatsConfig;
import dev.aruka.networkhorizons.estimate.EstimateReport;
import dev.aruka.networkhorizons.stats.ConnectionTraffic;
import dev.aruka.networkhorizons.stats.Direction;
import dev.aruka.networkhorizons.stats.TrafficRecorder;
import dev.aruka.networkhorizons.stats.TrafficReport;
import dev.aruka.networkhorizons.stats.TrafficStats;
import dev.aruka.networkhorizons.stats.Window;

/** Actions behind {@code /neth} (server) and {@code /nethc} (client), for the connections of the side they run on. */
public final class TrafficCommands {

    // EnumChatFormatting.getTextWithoutFormattingCodes is client only
    private static final Pattern FORMATTING = Pattern.compile("\u00a7[0-9a-fk-orA-FK-OR]");

    private TrafficCommands() {}

    /**
     * @param client true for {@code /nethc} (the client's connection to its server), false for {@code /neth}
     * @param player connection label to show, or null for all connections
     */
    public static int stats(ICommandSender sender, boolean client, String player, int top, Window window) {
        List<TrafficStats> sources = new ArrayList<>();
        List<ConnectionTraffic> live = TrafficRecorder.liveConnections();
        for (ConnectionTraffic conn : live) {
            if (player == null || conn.label()
                .equalsIgnoreCase(player)) sources.add(conn.stats());
        }
        if (player == null) {
            sources.add(TrafficRecorder.disconnected());
        } else if (sources.isEmpty()) {
            throw new CommandException("No connection for " + player);
        }

        int now = TrafficRecorder.nowSeconds();
        int seconds = TrafficReport.seconds(sources, window, now);
        List<TrafficReport.Row> rows = TrafficReport.aggregate(sources, window, now);
        String header;
        if (client) {
            List<String> servers = new ArrayList<>();
            for (ConnectionTraffic conn : live) servers.add(conn.label());
            header = EnumChatFormatting.AQUA + "[NetH client] "
                + (player != null ? player : servers.isEmpty() ? "not connected" : String.join(", ", servers));
        } else {
            header = EnumChatFormatting.GOLD + "[NetH server] "
                + (player != null ? player : live.size() + " connection(s)");
        }
        send(sender, header + ", " + window.id + " (" + seconds + "s)");
        if (!StatsConfig.enabled) send(sender, EnumChatFormatting.RED + "Stats are disabled in the config");
        String peer = client ? "server" : player != null ? player : "clients";
        // larger direction first: what the server sends, what the client receives
        Direction[] order = client ? new Direction[] { Direction.IN, Direction.OUT }
            : new Direction[] { Direction.OUT, Direction.IN };
        for (Direction dir : order) {
            long count = 0, bytes = 0;
            for (TrafficReport.Row r : rows) {
                if (r.direction == dir) {
                    count += r.count;
                    bytes += r.bytes;
                }
            }
            send(
                sender,
                String.format(
                    Locale.ROOT,
                    "%s%s %s/s, %.1f/t",
                    EnumChatFormatting.YELLOW,
                    dir == Direction.OUT ? "Sent to " + peer : "Received from " + peer,
                    TrafficReport.formatBytes((double) bytes / seconds),
                    (double) count / seconds / 20));
            int shown = 0;
            for (TrafficReport.Row r : rows) {
                if (r.direction != dir || shown++ >= top) continue;
                send(
                    sender,
                    String.format(
                        Locale.ROOT,
                        "%s %9s/s %5.1f%% %6.2f/t %s%s",
                        EnumChatFormatting.GRAY,
                        TrafficReport.formatBytes((double) r.bytes / seconds),
                        bytes == 0 ? 0 : r.bytes * 100.0 / bytes,
                        (double) r.count / seconds / 20,
                        EnumChatFormatting.WHITE,
                        r.label));
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    public static int dump(ICommandSender sender, boolean client, File baseDir) {
        try {
            File dir = new File(baseDir, "networkhorizons");
            String side = client ? "client" : "server";
            String timestamp = TrafficReport.timestamp();
            send(
                sender,
                "Wrote " + TrafficReport.dump(dir, side, timestamp)
                    .getPath());
            File estimates = EstimateReport.dump(dir, side, timestamp);
            if (estimates != null) send(sender, "Wrote " + estimates.getPath());
        } catch (IOException e) {
            throw new CommandException("Dump failed: " + e.getMessage());
        }
        return Command.SINGLE_SUCCESS;
    }

    public static int reset(ICommandSender sender) {
        TrafficRecorder.reset();
        send(sender, "Traffic stats reset");
        return Command.SINGLE_SUCCESS;
    }

    public static String usage(String name) {
        String estimate = name.equals(ClientTrafficCommand.NAME) ? "estimate [start [seconds] | stop]"
            : "estimate [start <player|all> [seconds] | stop [player] | <player>]";
        return "/" + name + " stats [60s|10m|total] [top N] [player] | " + estimate + " | dump | reset";
    }

    public static List<String> connectionLabels() {
        List<String> labels = new ArrayList<>();
        for (ConnectionTraffic conn : TrafficRecorder.liveConnections()) labels.add(conn.label());
        return labels;
    }

    /** Colors are kept for players only: the server console and RCON print them raw. */
    static void send(ICommandSender sender, String text) {
        if (!(sender instanceof EntityPlayer)) text = FORMATTING.matcher(text)
            .replaceAll("");
        sender.addChatMessage(new ChatComponentText(text));
    }
}
