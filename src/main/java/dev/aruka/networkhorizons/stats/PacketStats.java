package dev.aruka.networkhorizons.stats;

import net.minecraft.network.EnumConnectionState;

/** Counters for one key: direction + packet class, or direction + custom payload channel + first payload byte. */
public final class PacketStats {

    /** Sub id for custom payloads with an empty payload. */
    public static final int NO_SUB_ID = -1;

    public final Direction direction;
    public final EnumConnectionState state;
    public final Class<?> packetClass;
    /** Custom payload channel, null for other packets. */
    public final String channel;
    public final int subId;

    long count;
    long bytes;
    int maxBytes;
    final RateWindow lastMinute = new RateWindow(60, 1);
    final RateWindow lastTenMinutes = new RateWindow(60, 10);

    PacketStats(Direction direction, EnumConnectionState state, Class<?> packetClass, String channel, int subId) {
        this.direction = direction;
        this.state = state;
        this.packetClass = packetClass;
        this.channel = channel;
        this.subId = subId;
    }

    void add(int nowSeconds, int size) {
        count++;
        bytes += size;
        if (size > maxBytes) maxBytes = size;
        lastMinute.add(nowSeconds, 1, size);
        lastTenMinutes.add(nowSeconds, 1, size);
    }

    void merge(PacketStats other) {
        count += other.count;
        bytes += other.bytes;
        maxBytes = Math.max(maxBytes, other.maxBytes);
        lastMinute.merge(other.lastMinute);
        lastTenMinutes.merge(other.lastTenMinutes);
    }

    public int maxBytes() {
        return maxBytes;
    }

    public long count(Window window, int nowSeconds) {
        switch (window) {
            case LAST_60S:
                return lastMinute.count(nowSeconds);
            case LAST_10M:
                return lastTenMinutes.count(nowSeconds);
            default:
                return count;
        }
    }

    public long bytes(Window window, int nowSeconds) {
        switch (window) {
            case LAST_60S:
                return lastMinute.bytes(nowSeconds);
            case LAST_10M:
                return lastTenMinutes.bytes(nowSeconds);
            default:
                return bytes;
        }
    }

    /** Short label: packet class, or {@code [channel]#subId}, prefixed by the state outside of play. */
    public String label() {
        String name = channel != null ? "[" + channel + "]#" + (subId == NO_SUB_ID ? "-" : subId)
            : packetClass.getSimpleName();
        return state == null || state == EnumConnectionState.PLAY ? name : state.name() + ":" + name;
    }
}
