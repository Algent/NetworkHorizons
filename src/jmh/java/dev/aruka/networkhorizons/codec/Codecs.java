package dev.aruka.networkhorizons.codec;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;

/**
 * Candidate phase 4 codecs, one message per tick. "History" codecs keep one context per connection and flush after
 * every tick (what phase 4 would do); the others compress each tick on its own. Every encoded size is the bytes
 * that would go on the wire inside the frame, before the flag byte and the length prefix.
 * Scratch buffers are static (single-threaded benchmark, per thread in a real implementation), so measured memory
 * is the codec state only.
 */
public final class Codecs {

    private Codecs() {}

    public abstract static class Encoder implements AutoCloseable {

        protected static byte[] out = new byte[64 * 1024];

        /** @return encoded length, bytes in {@link #output()} until the next encode */
        public abstract int encode(byte[] tick);

        public byte[] output() {
            return out;
        }

        protected static void ensure(int size) {
            if (out.length < size) out = Arrays.copyOf(out, Math.max(size, out.length * 2));
        }

        @Override
        public void close() {}
    }

    public abstract static class Decoder implements AutoCloseable {

        /** @return decoded length written into {@code dst} */
        public abstract int decode(byte[] in, int len, byte[] dst);

        @Override
        public void close() {}
    }

    public static final class Spec {

        public final String name;
        public final boolean history;
        public final Supplier<Encoder> encoder;
        public final Supplier<Decoder> decoder;

        Spec(String name, boolean history, Supplier<Encoder> encoder, Supplier<Decoder> decoder) {
            this.name = name;
            this.history = history;
            this.encoder = encoder;
            this.decoder = decoder;
        }
    }

    public static List<Spec> all() {
        List<Spec> specs = new ArrayList<>();
        for (int level : new int[] { 1, 3, 6 }) {
            specs.add(new Spec("deflate-" + level, true, () -> new DeflateStream(level), DeflateStreamDecoder::new));
        }
        specs.add(new Spec("deflate-1-nohistory", false, () -> new DeflateTick(1), DeflateTickDecoder::new));
        // default window: 512 KiB at level 1, 2 MiB at level 3 (zstd streaming defaults); w17 = 128 KiB, w20 = 1 MiB
        specs.add(new Spec("zstd-1", true, () -> new ZstdStream(1, 0), ZstdStreamDecoder::new));
        specs.add(new Spec("zstd-3", true, () -> new ZstdStream(3, 0), ZstdStreamDecoder::new));
        specs.add(new Spec("zstd-1-w17", true, () -> new ZstdStream(1, 17), ZstdStreamDecoder::new));
        specs.add(new Spec("zstd-3-w17", true, () -> new ZstdStream(3, 17), ZstdStreamDecoder::new));
        specs.add(new Spec("zstd-3-w20", true, () -> new ZstdStream(3, 20), ZstdStreamDecoder::new));
        specs.add(new Spec("lz4-fast-nohistory", false, () -> new Lz4Tick(false), Lz4TickDecoder::new));
        specs.add(new Spec("lz4-hc9-nohistory", false, () -> new Lz4Tick(true), Lz4TickDecoder::new));
        return specs;
    }

    public static Spec byName(String name) {
        for (Spec s : all()) {
            if (s.name.equals(name)) return s;
        }
        throw new IllegalArgumentException("Unknown codec " + name);
    }

    // ---- deflate (JDK zlib) ----

    /** One raw deflate stream per connection, sync flush per tick, trailing 00 00 FF FF removed. */
    static final class DeflateStream extends Encoder {

        private final Deflater d;

        DeflateStream(int level) {
            d = new Deflater(level, true);
        }

        @Override
        public int encode(byte[] tick) {
            d.setInput(tick);
            int n = 0;
            while (true) {
                ensure(n + 4096);
                int k = d.deflate(out, n, out.length - n, Deflater.SYNC_FLUSH);
                n += k;
                if (n < out.length) return n - 4;
            }
        }

        @Override
        public void close() {
            d.end();
        }
    }

    static final class DeflateStreamDecoder extends Decoder {

        private static final byte[] TAIL = { 0, 0, (byte) 0xFF, (byte) 0xFF };
        private final Inflater inf = new Inflater(true);
        private static byte[] in = new byte[64 * 1024];

        @Override
        public int decode(byte[] src, int len, byte[] dst) {
            if (in.length < len + 4) in = new byte[(len + 4) * 2];
            System.arraycopy(src, 0, in, 0, len);
            System.arraycopy(TAIL, 0, in, len, 4);
            inf.setInput(in, 0, len + 4);
            try {
                // consume everything, including the empty block of the sync flush, or the next tick desyncs
                int n = 0;
                while (!inf.needsInput()) {
                    if (n == dst.length) throw new IllegalStateException("decode buffer too small");
                    n += inf.inflate(dst, n, dst.length - n);
                }
                return n;
            } catch (DataFormatException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void close() {
            inf.end();
        }
    }

    /** Each tick as its own raw deflate stream: no history between ticks. */
    static final class DeflateTick extends Encoder {

        private final Deflater d;

        DeflateTick(int level) {
            d = new Deflater(level, true);
        }

        @Override
        public int encode(byte[] tick) {
            d.reset();
            d.setInput(tick);
            d.finish();
            int n = 0;
            while (!d.finished()) {
                ensure(n + 4096);
                n += d.deflate(out, n, out.length - n);
            }
            return n;
        }

        @Override
        public void close() {
            d.end();
        }
    }

    static final class DeflateTickDecoder extends Decoder {

        private final Inflater inf = new Inflater(true);

        @Override
        public int decode(byte[] src, int len, byte[] dst) {
            inf.reset();
            inf.setInput(src, 0, len);
            try {
                int n = 0;
                while (!inf.finished()) n += inf.inflate(dst, n, dst.length - n);
                return n;
            } catch (DataFormatException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void close() {
            inf.end();
        }
    }

    // ---- zstd (zstd-jni) ----

    /** One zstd frame per connection that never ends, flushed per tick. */
    static final class ZstdStream extends Encoder {

        private final ZstdCompressCtx ctx = new ZstdCompressCtx();
        private static ByteBuffer src = ByteBuffer.allocateDirect(64 * 1024);
        private static final ByteBuffer dst = ByteBuffer.allocateDirect(64 * 1024);

        ZstdStream(int level, int windowLog) {
            ctx.setLevel(level);
            if (windowLog > 0) ctx.setWindowLog(windowLog);
        }

        @Override
        public int encode(byte[] tick) {
            if (src.capacity() < tick.length) src = ByteBuffer.allocateDirect(tick.length * 2);
            src.clear();
            src.put(tick);
            src.flip();
            int n = 0;
            boolean done;
            do {
                dst.clear();
                done = ctx.compressDirectByteBufferStream(dst, src, EndDirective.FLUSH);
                dst.flip();
                int k = dst.remaining();
                ensure(n + k);
                dst.get(out, n, k);
                n += k;
            } while (!done);
            return n;
        }

        @Override
        public void close() {
            ctx.close();
        }
    }

    static final class ZstdStreamDecoder extends Decoder {

        private final ZstdDecompressCtx ctx = new ZstdDecompressCtx();
        private static ByteBuffer src = ByteBuffer.allocateDirect(64 * 1024);
        private static ByteBuffer dst = ByteBuffer.allocateDirect(256 * 1024);

        @Override
        public int decode(byte[] in, int len, byte[] out) {
            if (src.capacity() < len) src = ByteBuffer.allocateDirect(len * 2);
            if (dst.capacity() < out.length) dst = ByteBuffer.allocateDirect(out.length * 2);
            src.clear();
            src.put(in, 0, len);
            src.flip();
            dst.clear();
            dst.limit(out.length);
            int before;
            do {
                before = dst.position();
                ctx.decompressDirectByteBufferStream(dst, src);
            } while (src.hasRemaining() || dst.position() != before && dst.hasRemaining());
            int n = dst.position();
            dst.flip();
            dst.get(out, 0, n);
            return n;
        }

        @Override
        public void close() {
            ctx.close();
        }
    }

    // ---- lz4 (lz4-java): no streaming history in its API, so one block per tick ----

    static final class Lz4Tick extends Encoder {

        private final LZ4Compressor c;

        Lz4Tick(boolean high) {
            LZ4Factory f = LZ4Factory.fastestInstance();
            c = high ? f.highCompressor(9) : f.fastCompressor();
        }

        @Override
        public int encode(byte[] tick) {
            // the block format needs the original length on the wire
            ensure(5 + c.maxCompressedLength(tick.length));
            int n = 0;
            int v = tick.length;
            while ((v & ~0x7F) != 0) {
                out[n++] = (byte) ((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            out[n++] = (byte) v;
            return n + c.compress(tick, 0, tick.length, out, n, out.length - n);
        }
    }

    static final class Lz4TickDecoder extends Decoder {

        private final LZ4FastDecompressor d = LZ4Factory.fastestInstance()
            .fastDecompressor();

        @Override
        public int decode(byte[] in, int len, byte[] dst) {
            int v = 0, shift = 0, i = 0, b;
            do {
                b = in[i++] & 0xFF;
                v |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            d.decompress(in, i, dst, 0, v);
            return v;
        }
    }
}
