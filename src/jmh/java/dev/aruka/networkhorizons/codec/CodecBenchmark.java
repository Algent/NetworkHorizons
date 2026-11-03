package dev.aruka.networkhorizons.codec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Time to compress / decompress a whole capture, tick by tick, with a fresh context (a new connection).
 * Divide by the compressible bytes printed at setup for ns per byte. Needs a capture:
 * {@code ./gradlew jmh -PjmhArgs="CodecBenchmark -p capture=C:/path/x.nhcap.gz"}.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
@State(Scope.Benchmark)
public class CodecBenchmark {

    @Param({})
    public String capture;

    @Param({ "deflate-1", "deflate-3", "deflate-6", "deflate-1-nohistory", "zstd-1", "zstd-3", "zstd-1-w17",
        "zstd-3-w17", "zstd-3-w20", "lz4-fast-nohistory", "lz4-hc9-nohistory" })
    public String codec;

    @Param({ "[GalacticraftCore]#0" })
    public String exclude;

    private Codecs.Spec spec;
    private List<byte[]> ticks;
    private final List<byte[]> encoded = new ArrayList<>();
    private byte[] dst;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        CaptureTicks c = CaptureTicks.load(capture, Collections.singleton(exclude));
        spec = Codecs.byName(codec);
        ticks = c.ticks;
        long bytes = 0;
        int max = 0;
        for (byte[] t : ticks) {
            bytes += t.length;
            max = Math.max(max, t.length);
        }
        dst = new byte[max + 1024];
        try (Codecs.Encoder enc = spec.encoder.get()) {
            for (byte[] t : ticks) encoded.add(Arrays.copyOf(enc.output(), enc.encode(t)));
        }
        System.out.println("\n[setup] " + c.name + ": " + ticks.size() + " ticks, " + bytes + " compressible bytes");
    }

    @Benchmark
    public long compress() {
        long n = 0;
        try (Codecs.Encoder enc = spec.encoder.get()) {
            for (byte[] t : ticks) n += enc.encode(t);
        }
        return n;
    }

    @Benchmark
    public long decompress() {
        long n = 0;
        try (Codecs.Decoder dec = spec.decoder.get()) {
            for (byte[] e : encoded) n += dec.decode(e, e.length, dst);
        }
        return n;
    }
}
