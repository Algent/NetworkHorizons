package dev.aruka.networkhorizons.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.NetworkStatistics;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S3FPacketCustomPayload;
import net.minecraft.util.MessageDeserializer;
import net.minecraft.util.MessageSerializer;
import net.minecraft.util.MessageSerializer2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * Drives the real vanilla encoder / decoder in an {@link EmbeddedChannel} and feeds the recorder the same values
 * the mixins pass (mixins are not applied in unit tests).
 */
class TrafficRecorderTest {

    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private EmbeddedChannel encoder;

    @BeforeEach
    void setUp() {
        TrafficRecorder.reset();
        encoder = serverChannel(new MessageSerializer(new NetworkStatistics()));
    }

    @AfterEach
    void tearDown() {
        for (EmbeddedChannel ch : channels) ch.close();
    }

    /** Server side channel in play state, like {@code NetworkManager.setConnectionState(PLAY)}. */
    private EmbeddedChannel serverChannel(ChannelHandler... handlers) {
        // Netty 4.0.10 refuses an EmbeddedChannel without handlers
        EmbeddedChannel ch = new EmbeddedChannel(
            handlers.length > 0 ? handlers : new ChannelHandler[] { new ChannelInboundHandlerAdapter() });
        channels.add(ch);
        ch.attr(NetworkManager.attrKeyConnectionState)
            .set(EnumConnectionState.PLAY);
        ch.attr(NetworkManager.attrKeySendable)
            .set(EnumConnectionState.PLAY.func_150754_b(false));
        ch.attr(NetworkManager.attrKeyReceivable)
            .set(EnumConnectionState.PLAY.func_150757_a(false));
        return ch;
    }

    /** Encodes like the outbound pipeline, records like the encoder hook, returns the framed wire size. */
    private int send(Packet packet) {
        encoder.writeOutbound(packet);
        ByteBuf body = (ByteBuf) encoder.readOutbound();
        TrafficRecorder.record(encoder, Direction.OUT, packet, body.readableBytes());

        // separate channel, not recorded: no mixins here
        EmbeddedChannel framed = new EmbeddedChannel(
            new MessageSerializer2(),
            new MessageSerializer(new NetworkStatistics()));
        framed.attr(NetworkManager.attrKeySendable)
            .set(EnumConnectionState.PLAY.func_150754_b(false));
        framed.writeOutbound(packet);
        int size = ((ByteBuf) framed.readOutbound()).readableBytes();
        framed.close();
        return size;
    }

    private static PacketStats only(Direction dir, String label) {
        PacketStats found = null;
        for (ConnectionTraffic conn : TrafficRecorder.liveConnections()) {
            for (PacketStats s : conn.stats()
                .all()) {
                if (s.direction == dir && s.label()
                    .equals(label)) {
                    found = s;
                }
            }
        }
        assertNotNull(found, "no stats for " + dir + " " + label);
        return found;
    }

    @Test
    void outboundCountsFramedSizeByClass() {
        int wire = send(new S00PacketKeepAlive(1234));
        PacketStats s = only(Direction.OUT, "S00PacketKeepAlive");
        assertEquals(1, s.count(Window.TOTAL, TrafficRecorder.nowSeconds()));
        assertEquals(wire, s.bytes(Window.TOTAL, TrafficRecorder.nowSeconds()));
        assertEquals(EnumConnectionState.PLAY, s.state);
    }

    @Test
    void customPayloadKeyedByChannelAndFirstByte() {
        int wire = send(new S3FPacketCustomPayload("Test", new byte[] { 7, 1, 2, 3 }));
        send(new S3FPacketCustomPayload("Test", new byte[] { 7, 9 }));
        send(new S3FPacketCustomPayload("Test", new byte[0]));
        PacketStats s = only(Direction.OUT, "[Test]#7");
        assertEquals(2, s.count(Window.TOTAL, TrafficRecorder.nowSeconds()));
        assertTrue(s.bytes(Window.TOTAL, TrafficRecorder.nowSeconds()) > wire);
        assertEquals(wire, s.maxBytes());
        only(Direction.OUT, "[Test]#-");
    }

    @Test
    void largeFrameCountsMultiByteLengthPrefix() {
        int wire = send(new S3FPacketCustomPayload("Big", new byte[20000]));
        assertEquals(wire, only(Direction.OUT, "[Big]#0").bytes(Window.TOTAL, TrafficRecorder.nowSeconds()));
    }

    @Test
    void inboundCountsDecodedFrame() {
        // client to server packet, encoded by a client side channel
        EmbeddedChannel client = new EmbeddedChannel(new MessageSerializer(new NetworkStatistics()));
        channels.add(client);
        client.attr(NetworkManager.attrKeySendable)
            .set(EnumConnectionState.PLAY.func_150754_b(true));
        client.writeOutbound(new C17PacketCustomPayload("Mod", new byte[] { 3, 4, 5 }));
        ByteBuf frame = (ByteBuf) client.readOutbound();
        int bodySize = frame.readableBytes();

        EmbeddedChannel server = serverChannel(new MessageDeserializer(new NetworkStatistics()));
        server.writeInbound(frame);
        Packet decoded = (Packet) server.readInbound();
        TrafficRecorder.record(server, Direction.IN, decoded, bodySize);

        PacketStats s = only(Direction.IN, "[Mod]#3");
        assertEquals(C17PacketCustomPayload.class, s.packetClass);
        assertEquals(bodySize + 1, s.bytes(Window.TOTAL, TrafficRecorder.nowSeconds()));
    }

    @Test
    void closedConnectionMovesToDisconnectedBucket() {
        EmbeddedChannel ch = serverChannel();
        TrafficRecorder.record(ch, Direction.OUT, new S00PacketKeepAlive(1), 5);
        assertEquals(
            1,
            TrafficRecorder.liveConnections()
                .size());
        ch.close();
        assertTrue(
            TrafficRecorder.liveConnections()
                .isEmpty());
        List<PacketStats> all = TrafficRecorder.disconnected()
            .all();
        assertEquals(1, all.size());
        assertEquals(
            6,
            all.get(0)
                .bytes(Window.TOTAL, TrafficRecorder.nowSeconds()));
    }

    @Test
    void resetClearsLiveAndDisconnected() {
        EmbeddedChannel a = serverChannel();
        EmbeddedChannel b = serverChannel();
        TrafficRecorder.record(a, Direction.OUT, new S00PacketKeepAlive(1), 5);
        TrafficRecorder.record(b, Direction.OUT, new S00PacketKeepAlive(1), 5);
        b.close();
        TrafficRecorder.reset();
        assertTrue(a.isOpen());
        assertTrue(
            TrafficRecorder.liveConnections()
                .get(0)
                .stats()
                .all()
                .isEmpty());
        assertTrue(
            TrafficRecorder.disconnected()
                .all()
                .isEmpty());
    }

    @Test
    void reportAggregatesAcrossConnections() {
        EmbeddedChannel a = serverChannel();
        EmbeddedChannel b = serverChannel();
        TrafficRecorder.record(a, Direction.OUT, new S00PacketKeepAlive(1), 5);
        TrafficRecorder.record(b, Direction.OUT, new S00PacketKeepAlive(1), 5);
        TrafficRecorder.record(b, Direction.OUT, new S3FPacketCustomPayload("X", new byte[] { 1 }), 100);
        List<TrafficStats> sources = new ArrayList<>();
        for (ConnectionTraffic c : TrafficRecorder.liveConnections()) sources.add(c.stats());
        List<TrafficReport.Row> rows = TrafficReport.aggregate(sources, Window.TOTAL, TrafficRecorder.nowSeconds());
        assertEquals(2, rows.size());
        assertEquals("[X]#1", rows.get(0).label);
        assertEquals(2, rows.get(1).count);
        assertEquals(12, rows.get(1).bytes);
    }
}
