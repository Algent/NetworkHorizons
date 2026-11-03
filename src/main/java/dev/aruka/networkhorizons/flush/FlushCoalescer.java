package dev.aruka.networkhorizons.flush;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.EnumConnectionState;

/**
 * Phase 3: packets the server thread sends during a tick are written without a flush and leave together, at
 * vanilla's per-connection flush in the network tick, or at the end of the server tick for those sent after it.
 * Every field is touched by the server thread only.
 */
public final class FlushCoalescer {

    /** Set at the start of every server tick, so integrated servers and restarts are handled. */
    public static volatile Thread serverThread;

    private static final List<CoalescingConnection> pending = new ArrayList<>();
    private static long deferredPackets;
    private static long flushes;

    private FlushCoalescer() {}

    /**
     * Whether a packet may be written without flushing. Anything else keeps vanilla's immediate flush: kick and
     * disconnect paths (they wait on listeners), state changes and login, other threads (they would wait up to a
     * tick), the client, and local connections.
     */
    public static boolean shouldDefer(boolean enabled, boolean clientSide, boolean local, int listeners,
        EnumConnectionState current, EnumConnectionState packetState, boolean fmlProxy) {
        return enabled && !clientSide
            && !local
            && listeners == 0
            && Thread.currentThread() == serverThread
            && current == EnumConnectionState.PLAY
            && (fmlProxy || packetState == EnumConnectionState.PLAY);
    }

    /** Called after a deferred write was scheduled. */
    public static void deferred(CoalescingConnection connection) {
        deferredPackets++;
        if (!connection.networkhorizons$isPending()) {
            connection.networkhorizons$setPending(true);
            pending.add(connection);
        }
    }

    /** Called after vanilla flushed the connection in its network tick. */
    public static void flushed(CoalescingConnection connection) {
        if (connection.networkhorizons$isPending()) {
            connection.networkhorizons$setPending(false);
            flushes++;
        }
    }

    /** End of the server tick: flush what was written after the network tick. */
    public static void flushPending() {
        for (CoalescingConnection c : pending) {
            if (c.networkhorizons$isPending()) {
                c.networkhorizons$setPending(false);
                c.networkhorizons$channel()
                    .flush();
                flushes++;
            }
        }
        pending.clear();
    }

    public static long deferredPackets() {
        return deferredPackets;
    }

    public static long flushes() {
        return flushes;
    }
}
