package dev.aruka.networkhorizons.capture;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.network.EnumConnectionState;

import dev.aruka.networkhorizons.NetworkHorizons;
import dev.aruka.networkhorizons.config.CaptureConfig;
import dev.aruka.networkhorizons.stats.Direction;
import io.netty.buffer.ByteBuf;

/**
 * Raw frame capture (phase 2), opt-in and time-limited. Frames are copied on the Netty threads and written by one
 * low-priority thread; a full queue drops frames and counts them. Captures hold everything the connection carries
 * (chat, positions, inventories): they stay local and are never enabled by default.
 */
public final class Capture {

    private static final BlockingQueue<Item> queue = new LinkedBlockingQueue<>();
    private static final AtomicLong queuedBytes = new AtomicLong();
    /** Latest capture per connection label, running or finished. */
    private static final Map<String, CaptureSession> sessions = new ConcurrentHashMap<>();
    private static Thread writer;

    private Capture() {}

    private static final class Item {

        final CaptureSession session;
        /** null closes the file. */
        final byte[] record;

        Item(CaptureSession session, byte[] record) {
            this.session = session;
            this.record = record;
        }
    }

    public static synchronized CaptureSession start(String label, File file, boolean client, int seconds,
        int nowSeconds) {
        if (writer == null) {
            writer = new Thread(Capture::run, "NetworkHorizons capture writer");
            writer.setDaemon(true);
            writer.setPriority(Thread.MIN_PRIORITY);
            writer.start();
        }
        CaptureSession session = new CaptureSession(label, file, client, nowSeconds + seconds);
        CaptureSession previous = sessions.put(label, session);
        if (previous != null) stop(previous);
        return session;
    }

    public static void stop(CaptureSession session) {
        if (!session.active) return;
        session.stopNanos = System.nanoTime();
        session.active = false;
        queue.add(new Item(session, null));
    }

    public static List<CaptureSession> sessions() {
        return new ArrayList<>(sessions.values());
    }

    /** Called on the connection's event loop for every frame, both directions. */
    public static void offer(CaptureSession session, Direction dir, EnumConnectionState state, ByteBuf buf, int index,
        int length, int nowSeconds) {
        if (!session.active) return;
        if (nowSeconds >= session.endSeconds) {
            stop(session);
            return;
        }
        long max = (long) CaptureConfig.maxQueuedMegabytes << 20;
        if (queuedBytes.get() + length > max) {
            session.dropped++;
            return;
        }
        byte[] body = new byte[length];
        buf.getBytes(index, body);
        byte[] record = session.record(dir, state, body);
        queuedBytes.addAndGet(record.length);
        queue.add(new Item(session, record));
    }

    private static void run() {
        while (true) {
            Item item;
            try {
                item = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            CaptureSession s = item.session;
            if (item.record == null) {
                s.close();
                continue;
            }
            queuedBytes.addAndGet(-item.record.length);
            // a frame offered while the capture was being stopped can arrive after the close
            if (s.closed || s.error != null) continue;
            try {
                s.write(item.record);
            } catch (IOException e) {
                s.error = e.getMessage();
                NetworkHorizons.LOG.error("Capture of {} failed, writing {}", s.label, s.file, e);
            }
        }
    }
}
