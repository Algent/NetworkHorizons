package dev.aruka.networkhorizons.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import net.minecraft.network.EnumConnectionState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.aruka.networkhorizons.config.CaptureConfig;
import dev.aruka.networkhorizons.stats.Direction;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class CaptureTest {

    private static void waitClosed(CaptureSession s) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!s.closed && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertTrue(s.closed, "writer did not close the capture");
    }

    private static int readVarInt(DataInputStream in) throws Exception {
        int v = 0, shift = 0, b;
        do {
            b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return v;
    }

    @Test
    void writesHeaderAndRecordsInOrder(@TempDir Path dir) throws Exception {
        File file = dir.resolve("c.nhcap.gz")
            .toFile();
        CaptureSession s = Capture.start("algent", file, false, 600, 0);
        byte[] small = { 0x00, 1, 2, 3, 4 };
        byte[] big = new byte[300];
        for (int i = 0; i < big.length; i++) big[i] = (byte) i;
        ByteBuf buf = Unpooled.buffer();
        buf.writeBytes(new byte[] { 9, 9 }); // the frame does not start at index 0
        buf.writeBytes(small);
        Capture.offer(s, Direction.OUT, EnumConnectionState.PLAY, buf, 2, small.length, 1);
        Capture.offer(s, Direction.IN, EnumConnectionState.LOGIN, Unpooled.wrappedBuffer(big), 0, big.length, 2);
        Capture.stop(s);
        waitClosed(s);
        assertEquals(2, s.frames());

        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(file)))) {
            byte[] magic = new byte[5];
            in.readFully(magic);
            assertArrayEquals(CaptureSession.MAGIC, magic);
            assertEquals(1, in.readUnsignedByte()); // version
            assertEquals(0, in.readUnsignedByte()); // server
            byte[] label = new byte[in.readUnsignedShort()];
            in.readFully(label);
            assertEquals("algent", new String(label, StandardCharsets.UTF_8));
            long epoch = in.readLong();
            assertTrue(Math.abs(epoch - System.currentTimeMillis()) < 60_000);

            long t1 = in.readLong();
            assertEquals(1, in.readUnsignedByte()); // out
            assertEquals(1, in.readUnsignedByte()); // play
            assertEquals(-1, in.readInt()); // no server in unit tests
            byte[] b1 = new byte[readVarInt(in)];
            in.readFully(b1);
            assertArrayEquals(small, b1);

            long t2 = in.readLong();
            assertTrue(t2 >= t1 && t1 >= 0);
            assertEquals(0, in.readUnsignedByte()); // in
            assertEquals(3, in.readUnsignedByte()); // login
            assertEquals(-1, in.readInt());
            byte[] b2 = new byte[readVarInt(in)];
            in.readFully(b2);
            assertArrayEquals(big, b2);

            boolean eof = false;
            try {
                in.readByte();
            } catch (EOFException e) {
                eof = true;
            }
            assertTrue(eof);
        }
    }

    @Test
    void stopsAtEndTimeAndDropsWhenQueueIsFull(@TempDir Path dir) throws Exception {
        CaptureConfig.maxQueuedMegabytes = 1;
        CaptureSession s = Capture.start(
            "drop",
            dir.resolve("d.nhcap.gz")
                .toFile(),
            true,
            10,
            100);
        ByteBuf huge = Unpooled.wrappedBuffer(new byte[2 << 20]);
        Capture.offer(s, Direction.IN, EnumConnectionState.PLAY, huge, 0, huge.readableBytes(), 101);
        assertEquals(1, s.dropped());
        Capture.offer(s, Direction.IN, EnumConnectionState.PLAY, Unpooled.wrappedBuffer(new byte[4]), 0, 4, 110);
        assertTrue(!s.isActive());
        waitClosed(s);
        assertEquals(0, s.frames());
        CaptureConfig.maxQueuedMegabytes = 64;
    }

    @Test
    void stateIdIsProtocolIdPlusOne() {
        assertEquals(0, CaptureSession.stateId(EnumConnectionState.HANDSHAKING));
        assertEquals(1, CaptureSession.stateId(EnumConnectionState.PLAY));
        assertEquals(2, CaptureSession.stateId(EnumConnectionState.STATUS));
        assertEquals(3, CaptureSession.stateId(EnumConnectionState.LOGIN));
    }
}
