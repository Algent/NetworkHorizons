package dev.aruka.networkhorizons.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.EnumChatFormatting;

import com.mojang.brigadier.Command;

import dev.aruka.networkhorizons.estimate.EstimateReport;
import dev.aruka.networkhorizons.estimate.Estimator;
import dev.aruka.networkhorizons.estimate.EstimatorSession;
import dev.aruka.networkhorizons.estimate.Strategy;
import dev.aruka.networkhorizons.stats.ConnectionTraffic;
import dev.aruka.networkhorizons.stats.Direction;
import dev.aruka.networkhorizons.stats.TrafficRecorder;
import dev.aruka.networkhorizons.stats.TrafficReport;

/**
 * {@code estimate} subcommands of {@code /neth} and {@code /nethc}. The server estimates what it sends to a player,
 * the client what it receives from its server.
 */
public final class EstimateCommands {

    private EstimateCommands() {}

    private static String tag(boolean client) {
        return client ? EnumChatFormatting.AQUA + "[NetH client] " : EnumChatFormatting.GOLD + "[NetH server] ";
    }

    private static List<ConnectionTraffic> targets(String player) {
        List<ConnectionTraffic> out = new ArrayList<>();
        for (ConnectionTraffic conn : TrafficRecorder.liveConnections()) {
            if (player == null || conn.label()
                .equalsIgnoreCase(player)) out.add(conn);
        }
        if (out.isEmpty()) throw new CommandException(player == null ? "No connection" : "No connection for " + player);
        return out;
    }

    /** @param player connection label, or null for all connections */
    public static int start(ICommandSender sender, boolean client, String player, int seconds) {
        int now = TrafficRecorder.nowSeconds();
        for (ConnectionTraffic conn : targets(player)) {
            String label = conn.label();
            conn.estimate = Estimator.start(label, client ? Direction.IN : Direction.OUT, !client, seconds, now);
            TrafficCommands.send(sender, tag(client) + "Estimating " + label + " for " + seconds + "s");
        }
        return Command.SINGLE_SUCCESS;
    }

    public static int stop(ICommandSender sender, boolean client, String player) {
        int stopped = 0;
        for (ConnectionTraffic conn : targets(player)) {
            EstimatorSession s = conn.estimate;
            if (s == null) continue;
            Estimator.stop(s);
            conn.estimate = null;
            stopped++;
        }
        TrafficCommands.send(sender, tag(client) + "Stopped " + stopped + " estimate(s)");
        return Command.SINGLE_SUCCESS;
    }

    public static int show(ICommandSender sender, boolean client, String player) {
        List<EstimatorSession> sessions = new ArrayList<>();
        for (EstimatorSession s : Estimator.sessions()) {
            if (player == null || s.label.equalsIgnoreCase(player)) sessions.add(s);
        }
        if (sessions.isEmpty()) {
            TrafficCommands.send(
                sender,
                tag(client) + "No estimate yet: /"
                    + (client ? "nethc estimate start [seconds]" : "neth estimate start <player|all> [seconds]"));
            return Command.SINGLE_SUCCESS;
        }
        sessions.sort(Comparator.comparing(s -> s.label));
        for (EstimatorSession s : sessions) {
            TrafficCommands.send(
                sender,
                String.format(
                    Locale.ROOT,
                    "%sEstimate %s, %s %s: %.0fs, %s, %d dropped",
                    tag(client),
                    s.label,
                    s.direction == Direction.OUT ? "sent to" : "received from",
                    s.direction == Direction.OUT ? "player" : "server",
                    s.seconds(),
                    s.isActive() ? "running" : "done",
                    s.dropped()));
            scope(sender, "All", s.snapshot(false), s.seconds());
            if (!s.excludedKeys.isEmpty()) {
                scope(sender, "Without " + String.join(", ", s.excludedKeys), s.snapshot(true), s.seconds());
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void scope(ICommandSender sender, String name, EstimatorSession.Snapshot snap, double seconds) {
        TrafficCommands.send(
            sender,
            String.format(
                Locale.ROOT,
                "%s%s: %s raw, %s/s, %d frames",
                EnumChatFormatting.YELLOW,
                name,
                TrafficReport.formatBytes(snap.rawBytes),
                TrafficReport.formatBytes(snap.rawBytes / Math.max(1, seconds)),
                snap.frames));
        for (Strategy st : Strategy.values()) {
            long bytes = snap.bytes(st);
            TrafficCommands.send(
                sender,
                String.format(
                    Locale.ROOT,
                    "%s %9s %5.1f%% saved %5.0f ns/B %s%s",
                    EnumChatFormatting.GRAY,
                    TrafficReport.formatBytes(bytes),
                    EstimateReport.savedPercent(snap.rawBytes, bytes),
                    snap.payloadBytes == 0 ? 0 : (double) snap.nanos(st) / snap.payloadBytes,
                    EnumChatFormatting.WHITE,
                    st.description));
        }
    }
}
