package dev.aruka.networkhorizons.compress;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import net.minecraft.util.MessageDeserializer2;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * Replays the server to client play frames of real captures through the compressor and back, one message per tick.
 * Opt-in, since captures are private: {@code ./gradlew test -PcaptureDir=<dir with *.nhcap.gz>}.
 */
class CaptureRoundTripTest {

    @Test
    void capturesRoundTrip() throws IOException {
        String dir = System.getProperty("nh.captures", "");
        assumeTrue(!dir.isEmpty(), "no -PcaptureDir");
        File[] files = new File(dir).listFiles((d, n) -> n.endsWith(".nhcap.gz"));
        assumeTrue(files != null && files.length > 0, "no captures in " + dir);
        for (File f : files) replay(f);
    }

    private static void replay(File file) throws IOException {
        EmbeddedChannel server = new EmbeddedChannel(new FrameCompressor(Codec.DEFLATE, 6));
        EmbeddedChannel client = new EmbeddedChannel(new MessageDeserializer2(), new FrameDecompressor());
        client.writeInbound(CompressionTest.frame(CompressionTest.startBody()));
        ((ByteBuf) client.readInbound()).release();

        ArrayDeque<byte[]> expected = new ArrayDeque<>();
        long frames = 0, uncompressed = 0, wire = 0, nanos = 0;
        try (DataInputStream in = new DataInputStream(
            new BufferedInputStream(new GZIPInputStream(new FileInputStream(file)), 1 << 16))) {
            byte[] magic = new byte[5];
            in.readFully(magic);
            in.readUnsignedByte();
            boolean clientSide = in.readUnsignedByte() == 1;
            assumeTrue(!clientSide, "server captures only");
            in.skipBytes(in.readUnsignedShort());
            in.readLong();
            int lastTick = Integer.MIN_VALUE;
            while (true) {
                int dir, state, tick;
                byte[] body;
                try {
                    in.readLong();
                    dir = in.readUnsignedByte();
                    state = in.readUnsignedByte();
                    tick = in.readInt();
                    body = new byte[readVarInt(in)];
                    in.readFully(body);
                } catch (EOFException e) {
                    break;
                }
                // play state (1 + 1) frames sent by the server
                if (dir != 1 || state != 1) continue;
                if (tick != lastTick) {
                    wire += exchange(server, client, expected);
                    lastTick = tick;
                }
                long t0 = System.nanoTime();
                ByteBuf frame = Unpooled.buffer(body.length + 5);
                Protocol.writeVarInt(frame, body.length);
                frame.writeBytes(body);
                server.write(frame);
                nanos += System.nanoTime() - t0;
                expected.add(body);
                frames++;
                uncompressed += body.length + Protocol.varIntSize(body.length);
            }
            wire += exchange(server, client, expected);
        }
        assertEquals(0, expected.size());
        System.out.printf(
            Locale.ROOT,
            "%s: %d frames, %.1f MiB -> %.1f MiB on the wire (%.1f%%)%n",
            file.getName(),
            frames,
            uncompressed / 1048576.0,
            wire / 1048576.0,
            100.0 * wire / uncompressed);
        server.finish();
        client.finish();
    }

    /** Flushes one tick, feeds it to the client, and checks what comes out. Returns the wire bytes. */
    private static long exchange(EmbeddedChannel server, EmbeddedChannel client, ArrayDeque<byte[]> expected) {
        server.flush();
        long wire = 0;
        Object msg;
        while ((msg = server.readOutbound()) != null) {
            ByteBuf buf = (ByteBuf) msg;
            wire += buf.readableBytes();
            client.writeInbound(buf);
        }
        while ((msg = client.readInbound()) != null) {
            ByteBuf buf = (ByteBuf) msg;
            byte[] b = new byte[buf.readableBytes()];
            buf.readBytes(b);
            buf.release();
            assertArrayEquals(expected.poll(), b);
        }
        return wire;
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int v = 0;
        for (int i = 0;; i++) {
            int b = in.readUnsignedByte();
            v |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) return v;
        }
    }
}
