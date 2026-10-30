package dev.aruka.networkhorizons.command;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.EnumChatFormatting;

import com.mojang.brigadier.Command;

import dev.aruka.networkhorizons.capture.Capture;
import dev.aruka.networkhorizons.capture.CaptureSession;
import dev.aruka.networkhorizons.config.CaptureConfig;
import dev.aruka.networkhorizons.stats.ConnectionTraffic;
import dev.aruka.networkhorizons.stats.TrafficRecorder;
import dev.aruka.networkhorizons.stats.TrafficReport;

/** {@code capture} subcommands of {@code /neth} and {@code /nethc}: raw frames of a connection, both directions. */
public final class CaptureCommands {

    private CaptureCommands() {}

    private static String tag(boolean client) {
        return client ? EnumChatFormatting.AQUA + "[NetH client] " : EnumChatFormatting.GOLD + "[NetH server] ";
    }

    /** @param player connection label, or null for all connections */
    public static int start(ICommandSender sender, boolean client, String player, int seconds, File baseDir) {
        if (!CaptureConfig.allowed) throw new CommandException("Captures are disabled in the config (capture.allowed)");
        List<ConnectionTraffic> targets = new ArrayList<>();
        for (ConnectionTraffic conn : TrafficRecorder.liveConnections()) {
            if (player == null || conn.label()
                .equalsIgnoreCase(player)) targets.add(conn);
        }
        if (targets.isEmpty())
            throw new CommandException(player == null ? "No connection" : "No connection for " + player);
        int now = TrafficRecorder.nowSeconds();
        String timestamp = TrafficReport.timestamp();
        File dir = new File(new File(baseDir, "networkhorizons"), "captures");
        for (ConnectionTraffic conn : targets) {
            String label = conn.label();
            File file = new File(
                dir,
                "capture-" + (client ? "client" : "server")
                    + "-"
                    + label.replaceAll("[^A-Za-z0-9._-]", "_")
                    + "-"
                    + timestamp
                    + ".nhcap.gz");
            conn.capture = Capture.start(label, file, client, seconds, now);
            TrafficCommands
                .send(sender, tag(client) + "Capturing " + label + " for " + seconds + "s to " + file.getPath());
        }
        TrafficCommands.send(
            sender,
            EnumChatFormatting.RED
                + "Captures contain everything the connection carries (chat, positions, inventories). Keep them private.");
        return Command.SINGLE_SUCCESS;
    }

    public static int stop(ICommandSender sender, boolean client, String player) {
        int stopped = 0;
        for (ConnectionTraffic conn : TrafficRecorder.liveConnections()) {
            CaptureSession s = conn.capture;
            if (s == null || player != null && !conn.label()
                .equalsIgnoreCase(player)) continue;
            Capture.stop(s);
            conn.capture = null;
            stopped++;
        }
        TrafficCommands.send(sender, tag(client) + "Stopped " + stopped + " capture(s)");
        return Command.SINGLE_SUCCESS;
    }

    public static int show(ICommandSender sender, boolean client) {
        List<CaptureSession> sessions = Capture.sessions();
        if (sessions.isEmpty()) {
            TrafficCommands.send(
                sender,
                tag(client) + "No capture yet: /"
                    + (client ? "nethc capture start [seconds]" : "neth capture start <player|all> [seconds]"));
            return Command.SINGLE_SUCCESS;
        }
        sessions.sort(Comparator.comparing(s -> s.label));
        for (CaptureSession s : sessions) {
            TrafficCommands.send(
                sender,
                String.format(
                    Locale.ROOT,
                    "%sCapture %s: %.0fs, %s, %d frames, %s uncompressed, %d dropped%s",
                    tag(client),
                    s.label,
                    s.seconds(),
                    s.isActive() ? "running" : "done",
                    s.frames(),
                    TrafficReport.formatBytes(s.bytes()),
                    s.dropped(),
                    s.error() == null ? "" : EnumChatFormatting.RED + ", error: " + s.error()));
            TrafficCommands.send(sender, EnumChatFormatting.GRAY + " " + s.file.getPath());
        }
        return Command.SINGLE_SUCCESS;
    }
}
