package dev.aruka.networkhorizons.estimate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Random;

import net.minecraft.network.play.server.S00PacketKeepAlive;

import org.junit.jupiter.api.Test;

import dev.aruka.networkhorizons.config.EstimatorConfig;
import dev.aruka.networkhorizons.stats.Direction;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class EstimatorTest {

    /** A GC gear slot like payload: same shape every time, a few bytes change. */
    private static byte[] gearPacket(int slot) {
        byte[] b = "?\u0010GalacticraftCore\u0000\u0014\u0000\u0000\u0000\u0000 \u0006algent\u0000\u0000\u0000&ÿÿÿÿ"
            .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        b[b.length - 5] = (byte) slot;
        return b;
    }

    private static byte[] random(int len, long seed) {
        byte[] b = new byte[len];
        new Random(seed).nextBytes(b);
        return b;
    }

    @Test
    void rawIsTheFramedSize() {
        ScopeModel m = new ScopeModel();
        m.accept(new byte[10], false, 0);
        m.accept(new byte[200], false, 0);
        m.end();
        assertEquals(2, m.frames);
        assertEquals(210, m.payloadBytes);
        assertEquals(11 + 202, m.rawBytes);
        // under the threshold, 1.8 style only adds its 1-byte "uncompressed" length
        assertEquals(m.rawBytes + 2, m.bytes[Strategy.PER_PACKET.ordinal()]);
    }

    @Test
    void streamCompressesRepeatedSmallPacketsAndPerPacketDoesNot() {
        ScopeModel m = new ScopeModel();
        for (int i = 0; i < 2000; i++) m.accept(gearPacket(i % 9), false, i / 9);
        m.end();
        long raw = m.rawBytes;
        assertTrue(m.bytes[Strategy.PER_PACKET.ordinal()] > raw);
        assertTrue(m.bytes[Strategy.STREAM_L1.ordinal()] < raw / 3, "L1 " + m.bytes[Strategy.STREAM_L1.ordinal()]);
        assertTrue(m.bytes[Strategy.STREAM_L6.ordinal()] <= m.bytes[Strategy.STREAM_L1.ordinal()]);
        // 9 packets per tick in one frame beats 9 frames
        assertTrue(m.bytes[Strategy.STREAM_L6_TICK.ordinal()] < m.bytes[Strategy.STREAM_L6.ordinal()]);
    }

    @Test
    void precompressedFramesBypassTheStreams() {
        ScopeModel m = new ScopeModel();
        byte[] chunk = random(1000, 1);
        m.accept(chunk, true, 0);
        m.end();
        long expected = ScopeModel.framed(1 + chunk.length);
        assertEquals(expected, m.bytes[Strategy.STREAM_L1.ordinal()]);
        assertEquals(expected, m.bytes[Strategy.STREAM_L6.ordinal()]);
        assertEquals(expected, m.bytes[Strategy.STREAM_L6_TICK.ordinal()]);
    }

    @Test
    void excludedFramesOnlyCountInTheAllScope() {
        EstimatorSession s = new EstimatorSession(
            "p",
            Direction.OUT,
            Collections.singleton("[GalacticraftCore]#0"),
            false,
            Integer.MAX_VALUE);
        s.process(gearPacket(1), true, false, 0);
        s.process(new byte[50], false, false, 0);
        s.end();
        assertEquals(2, s.snapshot(false).frames);
        assertEquals(1, s.snapshot(true).frames);
        assertEquals(51, s.snapshot(true).rawBytes);
    }

    @Test
    void runningSnapshotCountsTheUnflushedTickAsRaw() {
        ScopeModel m = new ScopeModel();
        m.accept(new byte[100], false, 7);
        assertEquals(0, m.bytes[Strategy.STREAM_L6_TICK.ordinal()]);
        assertEquals(ScopeModel.framed(101), m.pendingBytes());
        m.end();
        assertEquals(0, m.pendingBytes());
        assertTrue(m.bytes[Strategy.STREAM_L6_TICK.ordinal()] > 0);
    }

    @Test
    void queueEndToEndAndDrops() throws Exception {
        EstimatorConfig.maxQueuedMegabytes = 1;
        EstimatorSession s = Estimator.start("queue-test", Direction.OUT, false, 600, 0);
        ByteBuf buf = Unpooled.wrappedBuffer(new byte[4096]);
        for (int i = 0; i < 100; i++) Estimator.offer(s, "X", new S00PacketKeepAlive(1), buf, 0, 4096, 1);
        // larger than the whole queue budget: dropped and counted
        ByteBuf big = Unpooled.wrappedBuffer(new byte[2 << 20]);
        Estimator.offer(s, "X", new S00PacketKeepAlive(1), big, 0, big.readableBytes(), 1);
        Estimator.stop(s);
        long deadline = System.currentTimeMillis() + 10_000;
        while (!s.ended && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertTrue(s.ended);
        assertEquals(100, s.snapshot(false).frames);
        assertEquals(1, s.dropped());
        EstimatorConfig.maxQueuedMegabytes = 64;
    }

    @Test
    void sessionStopsAtItsEndTime() {
        EstimatorSession s = Estimator.start("timeout-test", Direction.OUT, false, 10, 100);
        ByteBuf buf = Unpooled.wrappedBuffer(new byte[8]);
        Estimator.offer(s, "X", new S00PacketKeepAlive(1), buf, 0, 8, 109);
        assertTrue(s.isActive());
        Estimator.offer(s, "X", new S00PacketKeepAlive(1), buf, 0, 8, 110);
        assertTrue(!s.isActive());
    }
}
