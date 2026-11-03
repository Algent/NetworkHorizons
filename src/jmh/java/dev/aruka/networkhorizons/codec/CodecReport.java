package dev.aruka.networkhorizons.codec;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.sun.management.OperatingSystemMXBean;

/**
 * Size, round trip, rough speed and memory of every candidate codec on captures:
 * {@code ./gradlew codecReport -PcodecArgs="--exclude [GalacticraftCore]#0 a.nhcap.gz b.nhcap.gz"}.
 * Speeds here are a quick median of passes; {@link CodecBenchmark} measures them with JMH.
 */
public final class CodecReport {

    private static final int PASSES = 5;
    private static final int MEMORY_CONTEXTS = 256;
    private static final int MEMORY_TICKS = 300;

    public static void main(String[] args) throws Exception {
        Set<String> excluded = new HashSet<>();
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--exclude")) excluded.add(args[++i]);
            else paths.add(args[i]);
        }
        if (paths.isEmpty()) throw new IllegalArgumentException("usage: [--exclude KEY]... capture.nhcap.gz...");

        List<CaptureTicks> captures = new ArrayList<>();
        for (String p : paths) captures.add(CaptureTicks.load(p, excluded));

        for (CaptureTicks c : captures) {
            long tickBytes = 0;
            int max = 0;
            for (byte[] t : c.ticks) {
                tickBytes += t.length;
                max = Math.max(max, t.length);
            }
            System.out.printf(
                Locale.ROOT,
                "%n## %s: %d frames, %.1f MiB framed, %d ticks (%.1f MiB compressible), chunks %.1f MiB%n%n",
                c.name,
                c.frames,
                c.rawBytes / 1048576.0,
                c.ticks.size(),
                tickBytes / 1048576.0,
                c.chunkBytes / 1048576.0);
            System.out.println("| Codec | Saved | Compress ns/B | Decompress ns/B | Compress MB/s |");
            System.out.println("|---|---|---|---|---|");
            for (Codecs.Spec spec : Codecs.all()) {
                long wire = roundTrip(spec, c, max);
                double comp = medianCompress(spec, c) / tickBytes;
                double decomp = medianDecompress(spec, c, max) / tickBytes;
                System.out.printf(
                    Locale.ROOT,
                    "| %s | %.1f%% | %.1f | %.1f | %.0f |%n",
                    spec.name,
                    (1 - (double) wire / c.rawBytes) * 100,
                    comp,
                    decomp,
                    1000 / comp);
            }
        }

        System.out.printf(
            Locale.ROOT,
            "%n## Memory per connection (%d contexts after %d ticks of %s)%n%n",
            MEMORY_CONTEXTS,
            MEMORY_TICKS,
            captures.get(0).name);
        System.out.println("| Codec | Encoder KiB | Decoder KiB |");
        System.out.println("|---|---|---|");
        for (Codecs.Spec spec : Codecs.all()) {
            double[] mem = memory(spec, captures.get(0));
            System.out.printf(Locale.ROOT, "| %s | %.0f | %.0f |%n", spec.name, mem[0], mem[1]);
        }
    }

    /** Encodes and decodes every tick, checks the result, returns the wire size of the capture. */
    static long roundTrip(Codecs.Spec spec, CaptureTicks c, int maxTick) {
        byte[] dst = new byte[maxTick + 1024];
        long wire = c.chunkBytes;
        try (Codecs.Encoder enc = spec.encoder.get(); Codecs.Decoder dec = spec.decoder.get()) {
            for (byte[] t : c.ticks) {
                int n = enc.encode(t);
                wire += CaptureTicks.framed(1 + n);
                int m = dec.decode(enc.output(), n, dst);
                if (m != t.length || !Arrays.equals(Arrays.copyOf(dst, m), t)) {
                    throw new IllegalStateException(spec.name + ": round trip mismatch on " + c.name);
                }
            }
        }
        return wire;
    }

    /** Median nanoseconds to encode the whole capture with a fresh encoder. */
    static double medianCompress(Codecs.Spec spec, CaptureTicks c) {
        long[] times = new long[PASSES];
        for (int pass = -2; pass < PASSES; pass++) {
            try (Codecs.Encoder enc = spec.encoder.get()) {
                long t = System.nanoTime();
                for (byte[] tick : c.ticks) enc.encode(tick);
                if (pass >= 0) times[pass] = System.nanoTime() - t;
            }
        }
        Arrays.sort(times);
        return times[PASSES / 2];
    }

    static double medianDecompress(Codecs.Spec spec, CaptureTicks c, int maxTick) {
        List<byte[]> encoded = new ArrayList<>();
        try (Codecs.Encoder enc = spec.encoder.get()) {
            for (byte[] tick : c.ticks) encoded.add(Arrays.copyOf(enc.output(), enc.encode(tick)));
        }
        byte[] dst = new byte[maxTick + 1024];
        long[] times = new long[PASSES];
        for (int pass = -2; pass < PASSES; pass++) {
            try (Codecs.Decoder dec = spec.decoder.get()) {
                long t = System.nanoTime();
                for (byte[] e : encoded) dec.decode(e, e.length, dst);
                if (pass >= 0) times[pass] = System.nanoTime() - t;
            }
        }
        Arrays.sort(times);
        return times[PASSES / 2];
    }

    /**
     * KiB per live encoder / decoder: process committed memory (native: zlib, zstd) plus Java heap, measured
     * across many contexts that each processed some real ticks, so windows and tables are allocated.
     */
    static double[] memory(Codecs.Spec spec, CaptureTicks c) throws Exception {
        List<byte[]> ticks = c.ticks.subList(0, Math.min(MEMORY_TICKS, c.ticks.size()));
        int maxTick = 0;
        for (byte[] t : ticks) maxTick = Math.max(maxTick, t.length);
        double encoder, decoder;

        List<Codecs.Encoder> encoders = new ArrayList<>();
        long before = used();
        for (int i = 0; i < MEMORY_CONTEXTS; i++) {
            Codecs.Encoder enc = spec.encoder.get();
            for (byte[] t : ticks) enc.encode(t);
            encoders.add(enc);
        }
        encoder = (used() - before) / 1024.0 / MEMORY_CONTEXTS;

        // decoders need the encoded stream of one encoder
        List<byte[]> encoded = new ArrayList<>();
        try (Codecs.Encoder enc = spec.encoder.get()) {
            for (byte[] t : ticks) encoded.add(Arrays.copyOf(enc.output(), enc.encode(t)));
        }
        for (Codecs.Encoder e : encoders) e.close();
        encoders.clear();

        byte[] dst = new byte[maxTick + 1024];
        List<Codecs.Decoder> decoders = new ArrayList<>();
        before = used();
        for (int i = 0; i < MEMORY_CONTEXTS; i++) {
            Codecs.Decoder dec = spec.decoder.get();
            for (byte[] e : encoded) dec.decode(e, e.length, dst);
            decoders.add(dec);
        }
        decoder = (used() - before) / 1024.0 / MEMORY_CONTEXTS;
        for (Codecs.Decoder d : decoders) d.close();
        return new double[] { encoder, decoder };
    }

    private static long used() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(50);
        }
        Runtime rt = Runtime.getRuntime();
        long heap = rt.totalMemory() - rt.freeMemory();
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        // committed virtual memory includes the heap's committed size; count only the native part from it
        return os.getCommittedVirtualMemorySize() - rt.totalMemory() + heap;
    }
}
