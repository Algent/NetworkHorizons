package dev.aruka.networkhorizons.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.network.play.server.S3FPacketCustomPayload;

import dev.aruka.networkhorizons.mixins.S3FPacketCustomPayloadAccessor;

/**
 * All counters of one connection, or of a bucket of connections.
 * Written by one thread (the connection's event loop); lookups on known keys do not allocate.
 */
public final class TrafficStats {

    private static final int SUB_IDS = 257; // 256 first byte values + empty payload

    public final int createdSeconds;

    @SuppressWarnings("unchecked")
    private final ConcurrentHashMap<Class<?>, PacketStats>[] byClass = new ConcurrentHashMap[] {
        new ConcurrentHashMap<>(), new ConcurrentHashMap<>() };
    @SuppressWarnings("unchecked")
    private final ConcurrentHashMap<String, PacketStats[]>[] byChannel = new ConcurrentHashMap[] {
        new ConcurrentHashMap<>(), new ConcurrentHashMap<>() };

    public TrafficStats(int createdSeconds) {
        this.createdSeconds = createdSeconds;
    }

    public PacketStats record(Direction dir, EnumConnectionState state, Packet packet, int size, int nowSeconds) {
        String channel = null;
        byte[] data = null;
        if (packet instanceof S3FPacketCustomPayloadAccessor) {
            S3FPacketCustomPayloadAccessor p = (S3FPacketCustomPayloadAccessor) packet;
            channel = p.networkhorizons$getChannel();
            data = p.networkhorizons$getData();
        } else if (packet instanceof S3FPacketCustomPayload) {
            // mixins not applied (unit tests); the getters only exist on the client
            S3FPacketCustomPayload p = (S3FPacketCustomPayload) packet;
            channel = p.func_149169_c();
            data = p.func_149168_d();
        } else if (packet instanceof C17PacketCustomPayload) {
            C17PacketCustomPayload p = (C17PacketCustomPayload) packet;
            channel = p.func_149559_c();
            data = p.func_149558_e();
        }

        int subId = channel != null && data != null && data.length > 0 ? data[0] & 0xFF : PacketStats.NO_SUB_ID;
        PacketStats stats = get(dir, state, packet.getClass(), channel, subId);
        stats.add(nowSeconds, size);
        return stats;
    }

    private PacketStats get(Direction dir, EnumConnectionState state, Class<?> packetClass, String channel, int subId) {
        int d = dir.ordinal();
        PacketStats stats;
        if (channel != null) {
            PacketStats[] subs = byChannel[d].get(channel);
            if (subs == null) {
                subs = new PacketStats[SUB_IDS];
                byChannel[d].put(channel, subs);
            }
            stats = subs[subId + 1];
            if (stats == null) {
                stats = subs[subId + 1] = new PacketStats(dir, state, packetClass, channel, subId);
            }
        } else {
            stats = byClass[d].get(packetClass);
            if (stats == null) {
                stats = new PacketStats(dir, state, packetClass, null, subId);
                byClass[d].put(packetClass, stats);
            }
        }
        return stats;
    }

    /** Snapshot of all keys, in no particular order. */
    public List<PacketStats> all() {
        List<PacketStats> out = new ArrayList<>();
        for (int d = 0; d < 2; d++) {
            out.addAll(byClass[d].values());
            for (PacketStats[] subs : byChannel[d].values()) {
                for (PacketStats s : subs) {
                    if (s != null) out.add(s);
                }
            }
        }
        return out;
    }

    /** Adds another bucket's counters into this one. Callers serialize merges into the same target. */
    void merge(TrafficStats other) {
        for (PacketStats s : other.all()) {
            get(s.direction, s.state, s.packetClass, s.channel, s.subId).merge(s);
        }
    }
}
