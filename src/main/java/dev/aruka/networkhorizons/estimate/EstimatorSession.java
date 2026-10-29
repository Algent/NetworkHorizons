package dev.aruka.networkhorizons.estimate;

import java.util.Set;

import net.minecraft.server.MinecraftServer;

import dev.aruka.networkhorizons.stats.Direction;

/**
 * Compression estimate for one connection and direction. Frames are offered from the connection's event loop
 * and compressed on the estimator thread, in two scopes: every frame, and every frame but the excluded keys.
 */
public final class EstimatorSession {

    /** Client side: frames closer than this belong to the same burst, standing in for a server tick. */
    private static final long CLIENT_GROUP_GAP_NANOS = 10_000_000L;

    public final String label;
    public final Direction direction;
    public final Set<String> excludedKeys;
    final boolean serverTicks;
    final long startNanos = System.nanoTime();
    final int endSeconds;

    volatile boolean active = true;
    volatile long stopNanos;
    /** Written only by the connection's event loop. */
    volatile long dropped;
    /** Estimator thread only: deflaters released, later frames are ignored. */
    boolean ended;

    final ScopeModel all = new ScopeModel();
    final ScopeModel filtered = new ScopeModel();

    private long lastNanos;
    private long group;

    EstimatorSession(String label, Direction direction, Set<String> excludedKeys, boolean serverTicks, int endSeconds) {
        this.label = label;
        this.direction = direction;
        this.excludedKeys = excludedKeys;
        this.serverTicks = serverTicks;
        this.endSeconds = endSeconds;
    }

    /** Tick of the frame: the server tick counter on servers, bursts of arrivals on clients. */
    long groupFor(long nanos) {
        if (serverTicks) {
            MinecraftServer server = MinecraftServer.getServer();
            if (server != null) return server.getTickCounter();
        }
        if (nanos - lastNanos > CLIENT_GROUP_GAP_NANOS) group++;
        lastNanos = nanos;
        return group;
    }

    /** Estimator thread only. */
    void process(byte[] body, boolean excluded, boolean precompressed, long group) {
        all.accept(body, precompressed, group);
        if (!excluded) filtered.accept(body, precompressed, group);
    }

    /** Estimator thread only, after the last frame. */
    void end() {
        ended = true;
        all.end();
        filtered.end();
    }

    public boolean isActive() {
        return active;
    }

    public long dropped() {
        return dropped;
    }

    public double seconds() {
        long end = active ? System.nanoTime() : stopNanos;
        return (end - startNanos) / 1e9;
    }

    /** @param withoutExcluded true for the scope without the excluded keys */
    public Snapshot snapshot(boolean withoutExcluded) {
        ScopeModel m = withoutExcluded ? filtered : all;
        long pending = m.pendingBytes();
        long[] bytes = m.bytes.clone();
        bytes[Strategy.STREAM_L6_TICK.ordinal()] += pending;
        return new Snapshot(m.frames, m.payloadBytes, m.rawBytes, bytes, m.nanos.clone());
    }

    /** Totals of one scope, read while the estimator may still be writing them. */
    public static final class Snapshot {

        public final long frames;
        public final long payloadBytes;
        /** Framed size without compression. */
        public final long rawBytes;
        private final long[] bytes;
        private final long[] nanos;

        Snapshot(long frames, long payloadBytes, long rawBytes, long[] bytes, long[] nanos) {
            this.frames = frames;
            this.payloadBytes = payloadBytes;
            this.rawBytes = rawBytes;
            this.bytes = bytes;
            this.nanos = nanos;
        }

        public long bytes(Strategy s) {
            return bytes[s.ordinal()];
        }

        public long nanos(Strategy s) {
            return nanos[s.ordinal()];
        }
    }
}
