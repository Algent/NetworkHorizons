package dev.aruka.networkhorizons.codec;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Server-to-client traffic of a {@code .nhcap.gz} capture, grouped per tick as phase 4 would send it: the
 * compressible frames of a tick concatenated, chunk packets (already deflated) apart.
 */
public final class CaptureTicks {

    private static final long GAP_NANOS = 10_000_000L;

    public final String name;
    /** Concatenated frame bodies of each tick, chunks and excluded keys left out. */
    public final List<byte[]> ticks = new ArrayList<>();
    /** Framed size of every server-to-client frame kept (chunks included). */
    public long rawBytes;
    /** Chunk packets: sent raw with the flag byte by every strategy. */
    public long chunkBytes;
    public long payloadBytes;
    public int frames;

    private CaptureTicks(String name) {
        this.name = name;
    }

    /** @param excluded keys left out entirely, e.g. {@code [GalacticraftCore]#0} */
    public static CaptureTicks load(String path, Set<String> excluded) throws IOException {
        String name = path.replaceAll(".*[/\\\\]", "")
            .replace(".nhcap.gz", "");
        CaptureTicks c = new CaptureTicks(name);
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(path), 1 << 16))) {
            byte[] magic = new byte[5];
            in.readFully(magic);
            if (!Arrays.equals(magic, "NHCAP".getBytes()) || in.readUnsignedByte() != 1) {
                throw new IOException(path + ": not a version 1 capture");
            }
            boolean client = in.readUnsignedByte() == 1;
            in.skipBytes(in.readUnsignedShort() + 8);
            ByteArrayOutputStream tick = new ByteArrayOutputStream();
            long currentGroup = Long.MIN_VALUE;
            long lastNanos = Long.MIN_VALUE;
            long gapGroup = 0;
            while (true) {
                long nanos;
                try {
                    nanos = in.readLong();
                } catch (java.io.EOFException e) {
                    break;
                }
                boolean out = in.readUnsignedByte() == 1;
                int state = in.readByte();
                int serverTick = in.readInt();
                int len = readVarInt(in);
                byte[] body = new byte[len];
                in.readFully(body);
                if (out == client || state != 1) continue; // server to client, play state only
                if (excluded.contains(key(body))) continue;
                c.frames++;
                c.rawBytes += framed(len);
                c.payloadBytes += len;
                int id = body[0] & 0xFF;
                if (id == 0x21 || id == 0x26) {
                    c.chunkBytes += framed(1 + len);
                    continue;
                }
                if (lastNanos != Long.MIN_VALUE && nanos - lastNanos > GAP_NANOS) gapGroup++;
                lastNanos = nanos;
                long group = serverTick >= 0 ? serverTick : gapGroup;
                if (group != currentGroup && tick.size() > 0) {
                    c.ticks.add(tick.toByteArray());
                    tick.reset();
                }
                currentGroup = group;
                tick.write(body);
            }
            if (tick.size() > 0) c.ticks.add(tick.toByteArray());
        }
        return c;
    }

    /** Same labels as {@code /neth stats}, enough to recognize custom payload keys. */
    static String key(byte[] b) {
        if ((b[0] & 0xFF) != 0x3F) return "#" + (b[0] & 0xFF);
        int[] pos = { 1 };
        int n = readVarInt(b, pos);
        String channel = new String(b, pos[0], n, java.nio.charset.StandardCharsets.UTF_8);
        int i = pos[0] + n;
        // FML var-short, Hodgepodge-extended
        int low = ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
        i += 2;
        if ((low & 0x8000) != 0) {
            int high = b[i++] & 0xFF;
            if (((high << 15) | (low & 0x7FFF)) == 0x7FFFFF) {
                pos[0] = i;
                readVarInt(b, pos);
                i = pos[0];
            }
        }
        return "[" + channel + "]#" + (i < b.length ? b[i] & 0xFF : -1);
    }

    static int framed(int bodySize) {
        int size = 1;
        for (int v = bodySize; (v & ~0x7F) != 0; v >>>= 7) size++;
        return size + bodySize;
    }

    private static int readVarInt(InputStream in) throws IOException {
        int v = 0, shift = 0, b;
        do {
            b = in.read();
            if (b < 0) throw new java.io.EOFException();
            v |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return v;
    }

    private static int readVarInt(byte[] b, int[] pos) {
        int v = 0, shift = 0, x;
        do {
            x = b[pos[0]++] & 0xFF;
            v |= (x & 0x7F) << shift;
            shift += 7;
        } while ((x & 0x80) != 0);
        return v;
    }
}
