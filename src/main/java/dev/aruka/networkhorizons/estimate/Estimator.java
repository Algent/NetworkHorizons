package dev.aruka.networkhorizons.estimate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S21PacketChunkData;
import net.minecraft.network.play.server.S26PacketMapChunkBulk;

import dev.aruka.networkhorizons.NetworkHorizons;
import dev.aruka.networkhorizons.config.EstimatorConfig;
import dev.aruka.networkhorizons.stats.Direction;
import io.netty.buffer.ByteBuf;

/**
 * Shadow compression estimator (phase 2): copies the frames of opted-in connections into a bounded queue and
 * compresses them on one low-priority thread, never on the Netty threads. A full queue drops frames and counts them.
 */
public final class Estimator {

    private static final BlockingQueue<Item> queue = new LinkedBlockingQueue<>();
    private static final AtomicLong queuedBytes = new AtomicLong();
    /** Latest session per connection label, running or finished. */
    private static final Map<String, EstimatorSession> sessions = new ConcurrentHashMap<>();
    private static Thread worker;

    private Estimator() {}

    private static final class Item {

        final EstimatorSession session;
        /** null marks the end of the session. */
        final byte[] body;
        final boolean excluded;
        final boolean precompressed;
        final long group;

        Item(EstimatorSession session, byte[] body, boolean excluded, boolean precompressed, long group) {
            this.session = session;
            this.body = body;
            this.excluded = excluded;
            this.precompressed = precompressed;
            this.group = group;
        }
    }

    public static synchronized EstimatorSession start(String label, Direction direction, boolean serverTicks,
        int seconds, int nowSeconds) {
        if (worker == null) {
            worker = new Thread(Estimator::run, "NetworkHorizons estimator");
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
        EstimatorSession session = new EstimatorSession(
            label,
            direction,
            new HashSet<>(Arrays.asList(EstimatorConfig.excludedKeys)),
            serverTicks,
            nowSeconds + seconds);
        EstimatorSession previous = sessions.put(label, session);
        if (previous != null) stop(previous);
        return session;
    }

    public static void stop(EstimatorSession session) {
        if (!session.active) return;
        session.stopNanos = System.nanoTime();
        session.active = false;
        queue.add(new Item(session, null, false, false, 0));
    }

    public static List<EstimatorSession> sessions() {
        return new ArrayList<>(sessions.values());
    }

    /**
     * Called on the connection's event loop for a frame of a direction being estimated.
     *
     * @param label key label of the packet, as in {@code /neth stats}
     */
    public static void offer(EstimatorSession session, String label, Packet packet, ByteBuf buf, int index, int length,
        int nowSeconds) {
        if (!session.active) return;
        if (nowSeconds >= session.endSeconds) {
            stop(session);
            return;
        }
        long max = (long) EstimatorConfig.maxQueuedMegabytes << 20;
        if (queuedBytes.get() + length > max) {
            session.dropped++;
            return;
        }
        byte[] body = new byte[length];
        buf.getBytes(index, body);
        boolean precompressed = packet instanceof S21PacketChunkData || packet instanceof S26PacketMapChunkBulk;
        queuedBytes.addAndGet(length);
        queue.add(
            new Item(
                session,
                body,
                session.excludedKeys.contains(label),
                precompressed,
                session.groupFor(System.nanoTime())));
    }

    private static void run() {
        while (true) {
            Item item;
            try {
                item = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                if (item.body == null) {
                    item.session.end();
                } else {
                    queuedBytes.addAndGet(-item.body.length);
                    // a frame offered while the session was being stopped can arrive after the end marker
                    if (!item.session.ended) {
                        item.session.process(item.body, item.excluded, item.precompressed, item.group);
                    }
                }
            } catch (RuntimeException e) {
                NetworkHorizons.LOG.error("Compression estimator failed on a frame", e);
            }
        }
    }
}
