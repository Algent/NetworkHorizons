package dev.aruka.networkhorizons.stats;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.network.play.server.S3FPacketCustomPayload;

import org.junit.jupiter.api.Test;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

/** Counting a packet on a known key must not allocate (phase 1 "no allocation per packet"). */
class TrafficRecorderAllocationTest {

    private static final int CALLS = 200_000;

    @Test
    void recordDoesNotAllocateOnKnownKeys() {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread()
            .getId();

        EmbeddedChannel ch = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        ch.attr(NetworkManager.attrKeyConnectionState)
            .set(EnumConnectionState.PLAY);
        Packet[] packets = { new S00PacketKeepAlive(1), new S35PacketUpdateTileEntity(1, 2, 3, 4, new NBTTagCompound()),
            new S3FPacketCustomPayload("GalacticraftCore", new byte[] { 0, 1, 2, 3 }),
            new S3FPacketCustomPayload("GregTech", new byte[] { 2, 5 }) };

        TrafficRecorder.reset();
        // first calls create the keys and the connection, then let the JIT settle
        for (int i = 0; i < CALLS; i++) TrafficRecorder.record(ch, Direction.OUT, packets[i & 3], 40);

        long before = threads.getThreadAllocatedBytes(thread);
        for (int i = 0; i < CALLS; i++) TrafficRecorder.record(ch, Direction.OUT, packets[i & 3], 40);
        long allocated = threads.getThreadAllocatedBytes(thread) - before;

        ch.close();
        // a few hundred bytes of noise (the measurement itself) are fine, one object per call is not
        assertTrue(allocated < 4096, "allocated " + allocated + " bytes over " + CALLS + " calls");
    }
}
