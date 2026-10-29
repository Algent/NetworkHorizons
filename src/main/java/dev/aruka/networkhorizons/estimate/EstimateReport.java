package dev.aruka.networkhorizons.estimate;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** CSV output of the compression estimates, next to the traffic dump. */
public final class EstimateReport {

    private EstimateReport() {}

    /** @return the file written, or null when there is no estimate */
    public static File dump(File dir, String side, String timestamp) throws IOException {
        List<EstimatorSession> sessions = Estimator.sessions();
        if (sessions.isEmpty()) return null;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File file = new File(dir, "estimate-" + side + "-" + timestamp + ".csv");
        try (PrintWriter w = new PrintWriter(file, StandardCharsets.UTF_8.name())) {
            w.println(
                "connection,direction,running,seconds,dropped,scope,strategy,frames,payload_bytes,raw_bytes,bytes,saved_pct,cpu_ns");
            for (EstimatorSession s : sessions) {
                writeScope(w, s, "all", s.snapshot(false));
                writeScope(w, s, "without " + String.join(" ", s.excludedKeys), s.snapshot(true));
            }
        }
        return file;
    }

    private static void writeScope(PrintWriter w, EstimatorSession s, String scope, EstimatorSession.Snapshot snap) {
        String prefix = String.format(
            Locale.ROOT,
            "%s,%s,%s,%.0f,%d,\"%s\",",
            s.label.replace(",", ";"),
            s.direction,
            s.isActive(),
            s.seconds(),
            s.dropped(),
            scope);
        w.println(
            prefix + "raw,"
                + snap.frames
                + ","
                + snap.payloadBytes
                + ","
                + snap.rawBytes
                + ","
                + snap.rawBytes
                + ",0,0");
        for (Strategy st : Strategy.values()) {
            long bytes = snap.bytes(st);
            w.println(
                String.format(
                    Locale.ROOT,
                    "%s\"%s\",%d,%d,%d,%d,%.1f,%d",
                    prefix,
                    st.description,
                    snap.frames,
                    snap.payloadBytes,
                    snap.rawBytes,
                    bytes,
                    savedPercent(snap.rawBytes, bytes),
                    snap.nanos(st)));
        }
    }

    public static double savedPercent(long raw, long bytes) {
        return raw == 0 ? 0 : (1 - (double) bytes / raw) * 100;
    }
}
