package dev.aruka.networkhorizons.estimate;

import java.util.Arrays;
import java.util.zip.Deflater;

/**
 * Runs every {@link Strategy} over one stream of frames and sums the resulting wire sizes (length prefix included).
 * Only touched by the estimator thread, except for reads of the totals.
 */
final class ScopeModel {

    private static final int THRESHOLD = 256;

    long frames;
    long payloadBytes;
    long rawBytes;
    final long[] bytes = new long[Strategy.values().length];
    final long[] nanos = new long[Strategy.values().length];

    private final Deflater perPacket = new Deflater(6);
    private final Deflater streamL1 = new Deflater(1, true);
    private final Deflater streamL6 = new Deflater(6, true);
    private final Deflater tickL6 = new Deflater(6, true);
    private byte[] out = new byte[64 * 1024];
    private byte[] pending = new byte[64 * 1024];
    private int pendingLength;
    private long pendingGroup = Long.MIN_VALUE;
    private boolean ended;

    void accept(byte[] body, boolean precompressed, long group) {
        int len = body.length;
        frames++;
        payloadBytes += len;
        rawBytes += framed(len);

        long t = System.nanoTime();
        if (len >= THRESHOLD) {
            perPacket.reset();
            perPacket.setInput(body);
            perPacket.finish();
            int n = 0;
            while (!perPacket.finished()) n += perPacket.deflate(out);
            bytes[Strategy.PER_PACKET.ordinal()] += framed(varIntSize(len) + n);
        } else {
            bytes[Strategy.PER_PACKET.ordinal()] += framed(1 + len);
        }
        long t2 = System.nanoTime();
        nanos[Strategy.PER_PACKET.ordinal()] += t2 - t;

        if (precompressed) {
            // already deflated (chunks): sent raw with the flag byte, kept out of the dictionaries
            bytes[Strategy.STREAM_L1.ordinal()] += framed(1 + len);
            bytes[Strategy.STREAM_L6.ordinal()] += framed(1 + len);
            bytes[Strategy.STREAM_L6_TICK.ordinal()] += framed(1 + len);
            return;
        }
        bytes[Strategy.STREAM_L1.ordinal()] += framed(1 + syncFlush(streamL1, body, 0, len));
        long t3 = System.nanoTime();
        nanos[Strategy.STREAM_L1.ordinal()] += t3 - t2;
        bytes[Strategy.STREAM_L6.ordinal()] += framed(1 + syncFlush(streamL6, body, 0, len));
        long t4 = System.nanoTime();
        nanos[Strategy.STREAM_L6.ordinal()] += t4 - t3;

        if (group != pendingGroup) flushTick();
        pendingGroup = group;
        if (pendingLength + len > pending.length)
            pending = Arrays.copyOf(pending, Math.max(pending.length * 2, pendingLength + len));
        System.arraycopy(body, 0, pending, pendingLength, len);
        pendingLength += len;
    }

    /** Compresses the frames of the current tick as one message. */
    void flushTick() {
        if (pendingLength == 0) return;
        long t = System.nanoTime();
        bytes[Strategy.STREAM_L6_TICK.ordinal()] += framed(1 + syncFlush(tickL6, pending, 0, pendingLength));
        nanos[Strategy.STREAM_L6_TICK.ordinal()] += System.nanoTime() - t;
        pendingLength = 0;
    }

    /** Bytes of the tick not flushed yet, counted as raw so running totals stay comparable. */
    long pendingBytes() {
        return pendingLength == 0 ? 0 : framed(1 + pendingLength);
    }

    void end() {
        if (ended) return;
        ended = true;
        flushTick();
        perPacket.end();
        streamL1.end();
        streamL6.end();
        tickL6.end();
    }

    /** Compressed size with the trailing 00 00 FF FF of the sync flush removed (the receiver adds it back). */
    private int syncFlush(Deflater d, byte[] in, int off, int len) {
        d.setInput(in, off, len);
        int total = 0;
        while (true) {
            // only the size matters, so the output buffer is reused; a full buffer means more output is pending
            int n = d.deflate(out, 0, out.length, Deflater.SYNC_FLUSH);
            total += n;
            if (n < out.length) return total - 4;
        }
    }

    static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }

    static int framed(int bodySize) {
        return varIntSize(bodySize) + bodySize;
    }
}
