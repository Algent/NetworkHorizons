package dev.aruka.networkhorizons.stats;

import java.util.concurrent.TimeUnit;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.NetworkStatistics;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.network.play.server.S3FPacketCustomPayload;
import net.minecraft.util.MessageSerializer;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import dev.aruka.networkhorizons.config.StatsConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * Cost of counting one packet ({@code record}) next to the cost of encoding it with the vanilla encoder
 * ({@code encode}), which is what every packet already pays. No mixins here: {@code encode} is vanilla only.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class RecordBenchmark {

    /** Typical GTNH packets: keep-alive, GC gear slot (41 B framed), a small tile entity update. */
    static Packet keepAlive() {
        return new S00PacketKeepAlive(1234);
    }

    static Packet customPayload() {
        return new S3FPacketCustomPayload("GalacticraftCore", new byte[20]);
    }

    static Packet tileEntity() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setString("id", "cropsnh:cropSticksTE");
        nbt.setInteger("x", 1);
        nbt.setInteger("y", 64);
        nbt.setInteger("z", 1);
        nbt.setShort("growth", (short) 3);
        return new S35PacketUpdateTileEntity(1, 64, 1, 1, nbt);
    }

    static EmbeddedChannel playChannel(ChannelHandler handler) {
        EmbeddedChannel ch = new EmbeddedChannel(handler);
        ch.attr(NetworkManager.attrKeyConnectionState)
            .set(EnumConnectionState.PLAY);
        ch.attr(NetworkManager.attrKeySendable)
            .set(EnumConnectionState.PLAY.func_150754_b(false));
        return ch;
    }

    @State(Scope.Thread)
    public static class RecordState {

        @Param({ "true", "false" })
        public boolean enabled;

        EmbeddedChannel channel;
        Packet keepAlive = keepAlive(), customPayload = customPayload(), tileEntity = tileEntity();

        @Setup(Level.Trial)
        public void setUp() {
            StatsConfig.enabled = enabled;
            TrafficRecorder.reset();
            channel = playChannel(new ChannelInboundHandlerAdapter());
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            channel.close();
            StatsConfig.enabled = true;
        }
    }

    /** Exposes the protected vanilla encode. */
    static final class Encoder extends MessageSerializer {

        Encoder() {
            super(new NetworkStatistics());
        }

        void encodeInto(ChannelHandlerContext ctx, Packet packet, ByteBuf out) throws Exception {
            encode(ctx, packet, out);
        }
    }

    @State(Scope.Thread)
    public static class EncodeState {

        Encoder encoder;
        EmbeddedChannel channel;
        ChannelHandlerContext ctx;
        ByteBuf out;
        Packet keepAlive = keepAlive(), customPayload = customPayload(), tileEntity = tileEntity();

        @Setup(Level.Trial)
        public void setUp() {
            encoder = new Encoder();
            channel = playChannel(encoder);
            ctx = channel.pipeline()
                .context(encoder);
            out = Unpooled.buffer(4096);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            out.release();
            channel.close();
        }
    }

    @Benchmark
    public void recordKeepAlive(RecordState s) {
        TrafficRecorder.record(s.channel, Direction.OUT, s.keepAlive, 5);
    }

    @Benchmark
    public void recordCustomPayload(RecordState s) {
        TrafficRecorder.record(s.channel, Direction.OUT, s.customPayload, 40);
    }

    @Benchmark
    public void recordTileEntity(RecordState s) {
        TrafficRecorder.record(s.channel, Direction.OUT, s.tileEntity, 120);
    }

    @Benchmark
    public int encodeKeepAlive(EncodeState s) throws Exception {
        s.out.clear();
        s.encoder.encodeInto(s.ctx, s.keepAlive, s.out);
        return s.out.readableBytes();
    }

    @Benchmark
    public int encodeCustomPayload(EncodeState s) throws Exception {
        s.out.clear();
        s.encoder.encodeInto(s.ctx, s.customPayload, s.out);
        return s.out.readableBytes();
    }

    @Benchmark
    public int encodeTileEntity(EncodeState s) throws Exception {
        s.out.clear();
        s.encoder.encodeInto(s.ctx, s.tileEntity, s.out);
        return s.out.readableBytes();
    }
}
