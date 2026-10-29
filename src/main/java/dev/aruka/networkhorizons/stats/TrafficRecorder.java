package dev.aruka.networkhorizons.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;

import dev.aruka.networkhorizons.config.StatsConfig;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/**
 * Entry point for the encoder / decoder hooks, and the registry of connections for the commands.
 * Counts framed sizes: the encoded packet plus its varint length prefix.
 */
public final class TrafficRecorder {

    private static final AttributeKey<ConnectionTraffic> KEY = new AttributeKey<>("networkhorizons:traffic");
    private static final long START = System.nanoTime();

    private static final Set<ConnectionTraffic> live = ConcurrentHashMap.newKeySet();
    /** Totals of connections that closed since the last reset. */
    private static volatile TrafficStats disconnected = new TrafficStats(0);
    private static volatile int resetSeconds;

    private TrafficRecorder() {}

    public static int nowSeconds() {
        return (int) ((System.nanoTime() - START) / 1_000_000_000L);
    }

    public static int resetSeconds() {
        return resetSeconds;
    }

    /** Called on the channel's event loop, once per encoded or decoded packet. */
    public static void record(Channel channel, Direction dir, Packet packet, int bodySize) {
        if (!StatsConfig.enabled) return;
        ConnectionTraffic conn = channel.attr(KEY)
            .get();
        if (conn == null) conn = register(channel);
        EnumConnectionState state = channel.attr(NetworkManager.attrKeyConnectionState)
            .get();
        conn.stats.record(dir, state, packet, bodySize + varIntSize(bodySize), nowSeconds());
    }

    static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }

    private static ConnectionTraffic register(Channel channel) {
        ConnectionTraffic conn = new ConnectionTraffic(channel, new TrafficStats(nowSeconds()));
        channel.attr(KEY)
            .set(conn);
        live.add(conn);
        channel.closeFuture()
            .addListener(f -> onClose(conn));
        return conn;
    }

    private static void onClose(ConnectionTraffic conn) {
        live.remove(conn);
        TrafficStats target = disconnected;
        synchronized (target) {
            target.merge(conn.stats);
        }
    }

    public static List<ConnectionTraffic> liveConnections() {
        return new ArrayList<>(live);
    }

    public static TrafficStats disconnected() {
        return disconnected;
    }

    /** Clears all counters. Packets counted concurrently may land in either the old or the new stats. */
    public static void reset() {
        int now = nowSeconds();
        resetSeconds = now;
        disconnected = new TrafficStats(now);
        for (ConnectionTraffic conn : live) {
            conn.stats = new TrafficStats(now);
        }
    }
}
