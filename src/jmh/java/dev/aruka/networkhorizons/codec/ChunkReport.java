package dev.aruka.networkhorizons.codec;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

/**
 * What sending chunk data through the phase 4 stream would gain, and cost: chunk packets rewritten with their data as
 * stored (level 0) zlib, which the client inflates as usual, so the stream compresses it with history.
 * {@code ./gradlew chunkReport -PchunkArgs="[--exclude KEY]... a.nhcap.gz"}.
 *
 * <p>
 * Layout with ChunkAPI / EndlessIDs (GTNH), read from captures:
 * S21 = x, z, groundUp, short bitmap, int raw length, int size, zlib;
 * S26 = short count, count x int raw length, int size, bool skylight, zlib, count x 10 bytes.
 */
public final class ChunkReport {

    private static final String[] NAMES = { "A: deflate-6 (phase 4 today)", "A: zstd-1", "A: zstd-3",
        "A: zstd-3, 8 MiB window", "B: deflate-1", "B: deflate-6", "B: zstd-1", "B: zstd-3",
        "B: zstd-3, 8 MiB window" };
    /** B: chunks through the stream; A: chunks sent as they are today, beside the stream. */
    private static final boolean[] INLINE = { false, false, false, false, true, true, true, true, true };

    public static void main(String[] args) throws Exception {
        Set<String> excluded = new HashSet<>();
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--exclude")) excluded.add(args[++i]);
            else paths.add(args[i]);
        }
        for (String p : paths) new ChunkReport().run(p, excluded);
    }

    private final Codecs.Encoder[] enc = { new Codecs.DeflateStream(6), new Codecs.ZstdStream(1, 0),
        new Codecs.ZstdStream(3, 0), new Codecs.ZstdStream(3, 23), new Codecs.DeflateStream(1),
        new Codecs.DeflateStream(6), new Codecs.ZstdStream(1, 0), new Codecs.ZstdStream(3, 0),
        new Codecs.ZstdStream(3, 23) };
    private final Codecs.Decoder[] dec = { new Codecs.DeflateStreamDecoder(), new Codecs.ZstdStreamDecoder(),
        new Codecs.ZstdStreamDecoder(), new Codecs.ZstdStreamDecoder(), new Codecs.DeflateStreamDecoder(),
        new Codecs.DeflateStreamDecoder(), new Codecs.ZstdStreamDecoder(), new Codecs.ZstdStreamDecoder(),
        new Codecs.ZstdStreamDecoder() };
    private final long[] wire = new long[NAMES.length];
    private final long[] encNanos = new long[NAMES.length];
    private final long[] decNanos = new long[NAMES.length];
    private byte[] decodeBuf = new byte[1 << 20];

    private long restBytes, chunkPackets, chunkVanillaBytes, chunkStoredBytes, chunkRawBytes, chunkLevel9Bytes,
        unparsed;
    private long inflateNanos, storedNanos, vanillaDeflateNanos, storedInflateNanos;
    private final ByteArrayOutputStream rest = new ByteArrayOutputStream();
    private final ByteArrayOutputStream all = new ByteArrayOutputStream();

    private void run(String path, Set<String> excluded) throws IOException {
        long first = -1, last = 0;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(path), 1 << 16))) {
            byte[] magic = new byte[5];
            in.readFully(magic);
            in.readUnsignedByte();
            if (in.readUnsignedByte() != 0) throw new IOException(path + ": server captures only");
            in.skipBytes(in.readUnsignedShort() + 8);
            int tick = Integer.MIN_VALUE;
            while (true) {
                long nanos;
                try {
                    nanos = in.readLong();
                } catch (EOFException e) {
                    break;
                }
                boolean out = in.readUnsignedByte() == 1;
                int state = in.readByte();
                int serverTick = in.readInt();
                byte[] body = new byte[readVarInt(in)];
                in.readFully(body);
                if (!out || state != 1 || excluded.contains(CaptureTicks.key(body))) continue;
                if (first < 0) first = nanos;
                last = nanos;
                if (serverTick != tick) {
                    flushTick();
                    tick = serverTick;
                }
                add(body);
            }
            flushTick();
        }
        report(path, (last - first) / 60e9);
        for (Codecs.Encoder e : enc) e.close();
        for (Codecs.Decoder d : dec) d.close();
    }

    private void add(byte[] body) {
        int id = body[0] & 0xFF;
        if (id != 0x21 && id != 0x26) {
            frame(rest, body);
            frame(all, body);
            restBytes += CaptureTicks.framed(body.length);
            return;
        }
        chunkPackets++;
        chunkVanillaBytes += CaptureTicks.framed(body.length);
        byte[] stored = storedChunk(body);
        if (stored == null) {
            unparsed++;
            stored = body;
        }
        chunkStoredBytes += CaptureTicks.framed(stored.length);
        frame(all, stored);
    }

    /** The same packet with its zlib data stored (level 0), or null if the layout is not the expected one. */
    private byte[] storedChunk(byte[] b) {
        int id = b[0] & 0xFF, sizeAt, rawLen = 0;
        if (id == 0x21) {
            if (b.length < 20) return null;
            rawLen = readInt(b, 12);
            sizeAt = 16;
        } else {
            int count = ((b[1] & 0xFF) << 8) | (b[2] & 0xFF);
            if (b.length < 8 + 4 * count) return null;
            for (int i = 0; i < count; i++) rawLen += readInt(b, 3 + 4 * i);
            sizeAt = 3 + 4 * count;
        }
        int dataAt = sizeAt + (id == 0x21 ? 4 : 5);
        int size = readInt(b, sizeAt);
        if (size < 0 || rawLen < 0 || dataAt + size > b.length) return null;

        byte[] raw = new byte[rawLen];
        long t0 = System.nanoTime();
        Inflater inf = new Inflater();
        try {
            inf.setInput(b, dataAt, size);
            int n = 0;
            while (n < rawLen && !inf.finished()) {
                int k = inf.inflate(raw, n, rawLen - n);
                if (k == 0 && (inf.needsInput() || inf.needsDictionary())) return null;
                n += k;
            }
            if (n != rawLen) return null;
        } catch (DataFormatException e) {
            return null;
        } finally {
            inf.end();
        }
        long t1 = System.nanoTime();
        byte[] vanilla = deflate(raw, Deflater.DEFAULT_COMPRESSION);
        long t2 = System.nanoTime();
        byte[] storedData = deflate(raw, Deflater.NO_COMPRESSION);
        long t3 = System.nanoTime();
        chunkLevel9Bytes += deflate(raw, 9).length - vanilla.length + size;
        inflateNanos += t1 - t0;
        vanillaDeflateNanos += t2 - t1;
        storedNanos += t3 - t2;
        chunkRawBytes += rawLen;

        long t4 = System.nanoTime();
        Inflater check = new Inflater();
        try {
            check.setInput(storedData);
            check.inflate(raw);
        } catch (DataFormatException e) {
            throw new IllegalStateException(e);
        } finally {
            check.end();
        }
        storedInflateNanos += System.nanoTime() - t4;

        byte[] out = new byte[b.length - size + storedData.length];
        System.arraycopy(b, 0, out, 0, dataAt);
        writeInt(out, sizeAt, storedData.length);
        System.arraycopy(storedData, 0, out, dataAt, storedData.length);
        System.arraycopy(b, dataAt + size, out, dataAt + storedData.length, b.length - dataAt - size);
        return out;
    }

    private static byte[] deflate(byte[] raw, int level) {
        Deflater d = new Deflater(level);
        try {
            d.setInput(raw);
            d.finish();
            byte[] out = new byte[raw.length + raw.length / 100 + 64];
            int n = 0;
            while (!d.finished()) n += d.deflate(out, n, out.length - n);
            return Arrays.copyOf(out, n);
        } finally {
            d.end();
        }
    }

    private void flushTick() {
        byte[] restTick = rest.toByteArray(), allTick = all.toByteArray();
        for (int i = 0; i < NAMES.length; i++) {
            byte[] tick = INLINE[i] ? allTick : restTick;
            if (tick.length > 0) encode(i, tick);
        }
        rest.reset();
        all.reset();
    }

    private void encode(int i, byte[] tick) {
        long t0 = System.nanoTime();
        int n = enc[i].encode(tick);
        long t1 = System.nanoTime();
        wire[i] += n + CaptureTicks.framed(n + 1) - n;
        if (decodeBuf.length < tick.length + 1024) decodeBuf = new byte[tick.length * 2 + 1024];
        byte[] copy = Arrays.copyOf(enc[i].output(), n);
        long t2 = System.nanoTime();
        int m = dec[i].decode(copy, n, decodeBuf);
        decNanos[i] += System.nanoTime() - t2;
        encNanos[i] += t1 - t0;
        if (m != tick.length) throw new IllegalStateException(NAMES[i] + ": round trip " + m + " != " + tick.length);
    }

    private void report(String path, double minutes) {
        long vanillaTotal = restBytes + chunkVanillaBytes;
        for (int i = 0; i < NAMES.length; i++) if (!INLINE[i]) wire[i] += chunkVanillaBytes;
        System.out.printf(
            Locale.ROOT,
            "%n## %s: %.1f min%n%n- other packets %.1f MiB; chunk packets %d, %.1f MiB as sent (level 9 would be %.1f),"
                + " %.1f MiB raw data, %.1f MiB stored; %d not parsed%n",
            path.replaceAll(".*[/\\\\]", ""),
            minutes,
            mib(restBytes),
            chunkPackets,
            mib(chunkVanillaBytes),
            mib(chunkLevel9Bytes),
            mib(chunkRawBytes),
            mib(chunkStoredBytes),
            unparsed);
        System.out.printf(
            Locale.ROOT,
            "- per chunk data byte: inflate %.2f ns, store (level 0) %.2f ns, vanilla deflate %.2f ns,"
                + " inflate stored %.2f ns%n%n",
            perByte(inflateNanos),
            perByte(storedNanos),
            perByte(vanillaDeflateNanos),
            perByte(storedInflateNanos));
        System.out.println(
            "| Scenario | Wire MiB | Share of today's uncompressed | Stream compress, ms/min | Stream decompress, ms/min |");
        System.out.println("|---|---|---|---|---|");
        for (int i = 0; i < NAMES.length; i++) {
            System.out.printf(
                Locale.ROOT,
                "| %s | %.1f | %.1f%% | %.0f | %.0f |%n",
                NAMES[i],
                mib(wire[i]),
                100.0 * wire[i] / vanillaTotal,
                encNanos[i] / 1e6 / minutes,
                decNanos[i] / 1e6 / minutes);
        }
        System.out.printf(
            Locale.ROOT,
            "%nChunk side costs, ms/min: inflate %.0f, store %.0f, vanilla deflate %.0f, inflate stored %.0f%n",
            inflateNanos / 1e6 / minutes,
            storedNanos / 1e6 / minutes,
            vanillaDeflateNanos / 1e6 / minutes,
            storedInflateNanos / 1e6 / minutes);
    }

    private double perByte(long nanos) {
        return chunkRawBytes == 0 ? 0 : (double) nanos / chunkRawBytes;
    }

    private static double mib(long bytes) {
        return bytes / 1048576.0;
    }

    private static void frame(ByteArrayOutputStream out, byte[] body) {
        int v = body.length;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
        out.write(body, 0, body.length);
    }

    private static int readInt(byte[] b, int i) {
        return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }

    private static void writeInt(byte[] b, int i, int v) {
        b[i] = (byte) (v >>> 24);
        b[i + 1] = (byte) (v >>> 16);
        b[i + 2] = (byte) (v >>> 8);
        b[i + 3] = (byte) v;
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int v = 0, shift = 0, b;
        do {
            b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return v;
    }
}
