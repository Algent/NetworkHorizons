package dev.aruka.networkhorizons.compress;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;

import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S3FPacketCustomPayload;
import net.minecraft.util.MessageDeserializer2;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;

class CompressionTest {

    private final Random random = new Random(42);

    /** Vanilla frame: varint length, then the body. */
    static ByteBuf frame(byte[] body) {
        ByteBuf buf = Unpooled.buffer();
        Protocol.writeVarInt(buf, body.length);
        buf.writeBytes(body);
        return buf;
    }

    static byte[] startBody() throws IOException {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeVarIntToBuffer(Protocol.CUSTOM_PAYLOAD_ID);
        new S3FPacketCustomPayload(Protocol.CHANNEL, Protocol.start(Codec.DEFLATE)).writePacketData(buf);
        return Arrays.copyOf(buf.array(), buf.writerIndex());
    }

    private byte[] body(int id, int size, boolean repetitive) {
        byte[] b = new byte[size];
        if (repetitive) {
            for (int i = 0; i < size; i++) b[i] = (byte) ((i % 37) * (id + 1) + (random.nextInt(8) == 0 ? 1 : 0));
        } else {
            random.nextBytes(b);
        }
        b[0] = (byte) id;
        return b;
    }

    private static byte[] drain(EmbeddedChannel ch) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Object msg;
        while ((msg = ch.readOutbound()) != null) {
            ByteBuf buf = (ByteBuf) msg;
            byte[] b = new byte[buf.readableBytes()];
            buf.readBytes(b);
            buf.release();
            out.write(b, 0, b.length);
        }
        return out.toByteArray();
    }

    private static List<byte[]> received(EmbeddedChannel ch) {
        List<byte[]> bodies = new ArrayList<>();
        Object msg;
        while ((msg = ch.readInbound()) != null) {
            ByteBuf buf = (ByteBuf) msg;
            byte[] b = new byte[buf.readableBytes()];
            buf.readBytes(b);
            buf.release();
            bodies.add(b);
        }
        return bodies;
    }

    @Test
    void roundTripKeepsEveryFrameInOrder() throws IOException {
        List<byte[]> expected = new ArrayList<>();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();

        // before START: vanilla frames, sent before the compressor is installed
        for (int i = 0; i < 5; i++) {
            byte[] b = body(random.nextInt(0x40), 1 + random.nextInt(200), true);
            expected.add(b);
            wire.write(drainFrame(b));
        }
        byte[] start = startBody();
        assertEquals(Codec.DEFLATE, Protocol.startCodec(Unpooled.wrappedBuffer(start)));
        expected.add(start);
        wire.write(drainFrame(start));

        EmbeddedChannel server = new EmbeddedChannel(new FrameCompressor(Codec.DEFLATE, 6));
        long uncompressedPart = 0, rawPart = 0;
        for (int tick = 0; tick < 300; tick++) {
            int packets = random.nextInt(60);
            for (int p = 0; p < packets; p++) {
                byte[] b;
                int kind = random.nextInt(100);
                if (kind < 3) b = body(random.nextBoolean() ? 0x21 : 0x26, 2000 + random.nextInt(20000), false);
                else if (kind < 4) b = body(0x3F, 1 + random.nextInt(4), false);
                else b = body(random.nextInt(0x40), 1 + random.nextInt(400), true);
                expected.add(b);
                uncompressedPart += b.length + Protocol.varIntSize(b.length);
                if (kind < 3) rawPart += b.length + Protocol.varIntSize(b.length);
                server.write(frame(b));
            }
            if (tick == 100) {
                // larger than MAX_FRAME: raw
                byte[] b = body(0x3F, FrameCompressor.MAX_FRAME + 1000, true);
                expected.add(b);
                uncompressedPart += b.length + Protocol.varIntSize(b.length);
                rawPart += b.length + Protocol.varIntSize(b.length);
                server.write(frame(b));
            }
            if (tick == 200) {
                // more than MAX_MESSAGE in one tick: split over several compressed frames
                for (int i = 0; i < 6; i++) {
                    byte[] b = body(0x35, 300_000, true);
                    expected.add(b);
                    uncompressedPart += b.length + Protocol.varIntSize(b.length);
                    server.write(frame(b));
                }
            }
            server.flush();
            wire.write(drain(server));
        }
        byte[] all = wire.toByteArray();
        long compressed = all.length - rawPart;
        assertTrue(
            compressed < (uncompressedPart - rawPart) / 2,
            "compressed: " + compressed + " of " + (uncompressedPart - rawPart));

        EmbeddedChannel client = new EmbeddedChannel(new MessageDeserializer2(), new FrameDecompressor());
        List<byte[]> got = new ArrayList<>();
        for (int off = 0; off < all.length;) {
            int n = Math.min(all.length - off, 1 + random.nextInt(random.nextBoolean() ? 16 : 70_000));
            client.writeInbound(Unpooled.wrappedBuffer(all, off, n));
            got.addAll(received(client));
            off += n;
        }
        assertEquals(expected.size(), got.size());
        for (int i = 0; i < expected.size(); i++) assertArrayEquals(expected.get(i), got.get(i), "frame " + i);
        server.finish();
        client.finish();
    }

    private static byte[] drainFrame(byte[] body) {
        ByteBuf f = frame(body);
        byte[] b = new byte[f.readableBytes()];
        f.readBytes(b);
        return b;
    }

    @Test
    void promisesCompleteWhenTheMessageIsWritten() {
        EmbeddedChannel server = new EmbeddedChannel(new FrameCompressor(Codec.DEFLATE, 6));
        ChannelFuture a = server.write(frame(body(1, 50, true)));
        ChannelFuture b = server.write(frame(body(2, 50, true)));
        assertFalse(a.isDone());
        assertNull(server.readOutbound(), "nothing leaves before the flush");
        server.flush();
        assertTrue(a.isSuccess());
        assertTrue(b.isSuccess());
        ByteBuf out = (ByteBuf) server.readOutbound();
        assertEquals(Protocol.marker(Codec.DEFLATE), out.getByte(out.readerIndex() + 1));
        out.release();
        server.finish();
    }

    private static EmbeddedChannel startedClient() throws IOException {
        EmbeddedChannel client = new EmbeddedChannel(new FrameDecompressor());
        client.writeInbound(Unpooled.wrappedBuffer(startBody()));
        received(client);
        return client;
    }

    @Test
    void garbageFailsCleanly() throws IOException {
        for (int i = 0; i < 500; i++) {
            EmbeddedChannel client = startedClient();
            byte[] junk = new byte[1 + random.nextInt(2000)];
            random.nextBytes(junk);
            junk[0] = Protocol.marker(Codec.DEFLATE);
            try {
                client.writeInbound(Unpooled.wrappedBuffer(junk));
                received(client);
            } catch (DecoderException expected) {
                // the only acceptable failure
            }
        }
    }

    @Test
    void unknownMarkerIsRejected() throws IOException {
        EmbeddedChannel client = startedClient();
        assertThrows(
            DecoderException.class,
            () -> client.writeInbound(Unpooled.wrappedBuffer(new byte[] { (byte) 0x85, 1 })));
    }

    @Test
    void inflateIsBounded() throws IOException {
        Deflater d = new Deflater(9, true);
        d.setInput(new byte[FrameDecompressor.MAX_INFLATED + 1024]);
        byte[] out = new byte[1 << 20];
        int n = d.deflate(out, 1, out.length - 1, Deflater.SYNC_FLUSH);
        d.end();
        out[0] = Protocol.marker(Codec.DEFLATE);
        EmbeddedChannel client = startedClient();
        DecoderException e = assertThrows(
            DecoderException.class,
            () -> client.writeInbound(Unpooled.wrappedBuffer(out, 0, 1 + n - 4)));
        assertTrue(
            e.getMessage()
                .contains("too large"),
            e.getMessage());
    }

    @Test
    void framesToSendRaw() {
        assertTrue(Protocol.compressible(frame(new byte[] { 0x35, 1, 2 })));
        assertFalse(Protocol.compressible(frame(new byte[] { 0x21, 1, 2 })), "chunk");
        assertFalse(Protocol.compressible(frame(new byte[] { 0x26, 1, 2 })), "chunk bulk");
        ByteBuf two = frame(new byte[] { 0x35, 1 }).writeBytes(frame(new byte[] { 0x35, 1 }));
        assertFalse(Protocol.compressible(two), "not exactly one frame");
        assertNull(Protocol.startCodec(Unpooled.wrappedBuffer(new byte[] { 0x3F, 1, 2, 3 })));
    }
}
