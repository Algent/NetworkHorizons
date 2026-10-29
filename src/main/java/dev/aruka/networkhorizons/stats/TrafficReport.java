package dev.aruka.networkhorizons.stats;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Aggregation and output for the stats commands. Runs on the command thread, allocation is fine here. */
public final class TrafficReport {

    public static final class Row {

        public final Direction direction;
        public final String label;
        public long count;
        public long bytes;
        public int maxBytes;

        Row(Direction direction, String label) {
            this.direction = direction;
            this.label = label;
        }
    }

    private TrafficReport() {}

    /** Rows summed over the sources by direction and label, largest bytes first. */
    public static List<Row> aggregate(Collection<TrafficStats> sources, Window window, int now) {
        Map<String, Row> rows = new LinkedHashMap<>();
        for (TrafficStats stats : sources) {
            for (PacketStats s : stats.all()) {
                long bytes = s.bytes(window, now);
                if (bytes == 0) continue;
                String label = s.label();
                Row row = rows.computeIfAbsent(s.direction + label, k -> new Row(s.direction, label));
                row.count += s.count(window, now);
                row.bytes += bytes;
                row.maxBytes = Math.max(row.maxBytes, s.maxBytes());
            }
        }
        List<Row> out = new ArrayList<>(rows.values());
        out.sort((a, b) -> Long.compare(b.bytes, a.bytes));
        return out;
    }

    /** Seconds a rate is averaged over: the window, capped by how long the oldest source has been counting. */
    public static int seconds(Collection<TrafficStats> sources, Window window, int now) {
        int oldest = now;
        for (TrafficStats s : sources) oldest = Math.min(oldest, s.createdSeconds);
        int counting = Math.max(1, now - oldest);
        switch (window) {
            case LAST_60S:
                return Math.min(60, counting);
            case LAST_10M:
                return Math.min(600, counting);
            default:
                return counting;
        }
    }

    public static String formatBytes(double bytes) {
        if (bytes >= 1024 * 1024) return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024 * 1024));
        if (bytes >= 1024) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024);
        return String.format(Locale.ROOT, "%.0f B", bytes);
    }

    /** Writes one CSV row per key per connection, live ones first, then the disconnected bucket. */
    public static File dump(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File file = new File(dir, "traffic-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".csv");
        int now = TrafficRecorder.nowSeconds();
        try (PrintWriter w = new PrintWriter(file, StandardCharsets.UTF_8.name())) {
            w.println(
                "connection,direction,state,packet,channel,sub_id,count,bytes,max_bytes,count_60s,bytes_60s,count_10m,bytes_10m,seconds");
            for (ConnectionTraffic conn : TrafficRecorder.liveConnections()) {
                writeRows(w, conn.label(), conn.stats(), now);
            }
            writeRows(w, "(disconnected)", TrafficRecorder.disconnected(), now);
        }
        return file;
    }

    private static void writeRows(PrintWriter w, String connection, TrafficStats stats, int now) {
        int seconds = Math.max(1, now - stats.createdSeconds);
        for (PacketStats s : stats.all()) {
            w.println(
                String.join(
                    ",",
                    csv(connection),
                    s.direction.name(),
                    s.state == null ? "" : s.state.name(),
                    s.packetClass.getSimpleName(),
                    s.channel == null ? "" : csv(s.channel),
                    s.channel == null || s.subId == PacketStats.NO_SUB_ID ? "" : Integer.toString(s.subId),
                    Long.toString(s.count(Window.TOTAL, now)),
                    Long.toString(s.bytes(Window.TOTAL, now)),
                    Integer.toString(s.maxBytes()),
                    Long.toString(s.count(Window.LAST_60S, now)),
                    Long.toString(s.bytes(Window.LAST_60S, now)),
                    Long.toString(s.count(Window.LAST_10M, now)),
                    Long.toString(s.bytes(Window.LAST_10M, now)),
                    Integer.toString(seconds)));
        }
    }

    private static String csv(String s) {
        return s.indexOf(',') < 0 && s.indexOf('"') < 0 ? s : '"' + s.replace("\"", "\"\"") + '"';
    }
}
