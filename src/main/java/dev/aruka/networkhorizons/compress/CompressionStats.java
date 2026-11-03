package dev.aruka.networkhorizons.compress;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.aruka.networkhorizons.stats.Direction;
import io.netty.channel.Channel;

/**
 * Byte counts of one compressing (OUT, server) or decompressing (IN, client) connection, plus the totals of closed
 * ones. Sizes include frame length prefixes. Written only from the channel's event loop.
 */
public final class CompressionStats {

    private static final Set<CompressionStats> live = ConcurrentHashMap.newKeySet();
    private static volatile CompressionStats closedOut = new CompressionStats(null, Direction.OUT);
    private static volatile CompressionStats closedIn = new CompressionStats(null, Direction.IN);

    public final Channel channel;
    public final Direction direction;
    public volatile long messages;
    /** Frames that went through the codec, before compression. */
    public volatile long uncompressed;
    /** The compressed frames those became. */
    public volatile long compressed;
    /** Frames sent as they are (chunks, very large packets). */
    public volatile long raw;

    private CompressionStats(Channel channel, Direction direction) {
        this.channel = channel;
        this.direction = direction;
    }

    static CompressionStats open(Channel channel, Direction direction) {
        CompressionStats s = new CompressionStats(channel, direction);
        live.add(s);
        return s;
    }

    void close() {
        if (!live.remove(this)) return;
        CompressionStats target = direction == Direction.OUT ? closedOut : closedIn;
        synchronized (target) {
            target.add(this);
        }
    }

    public void add(CompressionStats other) {
        messages += other.messages;
        uncompressed += other.uncompressed;
        compressed += other.compressed;
        raw += other.raw;
    }

    public static CompressionStats sum(Direction direction) {
        return new CompressionStats(null, direction);
    }

    public static List<CompressionStats> live(Direction direction) {
        List<CompressionStats> out = new ArrayList<>();
        for (CompressionStats s : live) if (s.direction == direction) out.add(s);
        return out;
    }

    public static CompressionStats closed(Direction direction) {
        return direction == Direction.OUT ? closedOut : closedIn;
    }

    /** Counts written concurrently may survive the reset. */
    public static void reset() {
        closedOut = new CompressionStats(null, Direction.OUT);
        closedIn = new CompressionStats(null, Direction.IN);
        for (CompressionStats s : live) {
            s.messages = 0;
            s.uncompressed = 0;
            s.compressed = 0;
            s.raw = 0;
        }
    }
}
