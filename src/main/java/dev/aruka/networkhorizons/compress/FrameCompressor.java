package dev.aruka.networkhorizons.compress;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

import net.minecraft.network.play.server.S3FPacketCustomPayload;

import dev.aruka.networkhorizons.NetworkHorizons;
import dev.aruka.networkhorizons.config.CompressionConfig;
import dev.aruka.networkhorizons.stats.Direction;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;

/**
 * Server side, between the vanilla prepender and encryption. Frames written between two flushes (one tick with phase
 * 3) go through one deflate stream kept for the whole connection, and leave as one compressed frame at the flush.
 * Chunk packets and very large frames close the pending message and go out raw, in order.
 */
public final class FrameCompressor extends ChannelDuplexHandler {

    public static final String NAME = "networkhorizons:compress";
    /** Uncompressed bytes per compressed frame at most, so it stays far below the 21-bit vanilla frame length. */
    static final int MAX_MESSAGE = 1024 * 1024;
    /** Larger frames are sent raw. */
    static final int MAX_FRAME = 512 * 1024;

    private final Deflater deflater;
    private final byte marker;
    private final List<ByteBuf> frames = new ArrayList<>();
    private final List<ChannelPromise> promises = new ArrayList<>();
    private int pendingBytes;
    private boolean ended;
    private CompressionStats stats;

    FrameCompressor(Codec codec, int level) {
        deflater = new Deflater(level, true);
        marker = Protocol.marker(codec);
    }

    /**
     * Sends START, then compresses everything written after it. Runs on the event loop, so writes queued before stay
     * raw and before START, and writes queued after are compressed.
     */
    public static void install(Channel channel, Codec codec, int level, String label) {
        channel.eventLoop()
            .execute(() -> {
                ChannelPipeline pipeline = channel.pipeline();
                if (!channel.isOpen() || pipeline.get("prepender") == null || pipeline.get(NAME) != null) return;
                channel.writeAndFlush(new S3FPacketCustomPayload(Protocol.CHANNEL, Protocol.start(codec)))
                    .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
                pipeline.addBefore("prepender", NAME, new FrameCompressor(codec, level));
                NetworkHorizons.LOG.info("Compression on for {} ({} level {})", label, codec.label(), level);
            });
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        stats = CompressionStats.open(ctx.channel(), Direction.OUT);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (!(msg instanceof ByteBuf)) {
            ctx.write(msg, promise);
            return;
        }
        ByteBuf frame = (ByteBuf) msg;
        if (ended) {
            frame.release();
            promise.tryFailure(new ClosedChannelException());
            return;
        }
        int size = frame.readableBytes();
        if (!CompressionConfig.enabled || size > MAX_FRAME || !Protocol.compressible(frame)) {
            emit(ctx);
            stats.raw += size;
            ctx.write(frame, promise);
            return;
        }
        if (pendingBytes + size > MAX_MESSAGE) emit(ctx);
        frames.add(frame);
        promises.add(promise);
        pendingBytes += size;
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
        emit(ctx);
        ctx.flush();
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
        emit(ctx);
        ctx.flush();
        ctx.close(promise);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        end();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        end();
    }

    private void emit(ChannelHandlerContext ctx) {
        if (frames.isEmpty() || ended) return;
        ByteBuf out = null;
        try {
            Scratch scratch = Scratch.get();
            byte[] buf = scratch.out(0);
            int pos = 0;
            for (ByteBuf frame : frames) {
                int n = frame.readableBytes();
                if (frame.hasArray()) {
                    deflater.setInput(frame.array(), frame.arrayOffset() + frame.readerIndex(), n);
                } else {
                    byte[] in = scratch.in(n);
                    frame.getBytes(frame.readerIndex(), in, 0, n);
                    deflater.setInput(in, 0, n);
                }
                while (!deflater.needsInput()) {
                    buf = scratch.out(pos + 8192);
                    pos += deflater.deflate(buf, pos, buf.length - pos, Deflater.NO_FLUSH);
                }
            }
            int avail;
            int n;
            do {
                buf = scratch.out(pos + 8192);
                avail = buf.length - pos;
                n = deflater.deflate(buf, pos, avail, Deflater.SYNC_FLUSH);
                pos += n;
            } while (n == avail);
            // the receiver appends the 00 00 FF FF every sync flush ends with
            pos -= 4;

            int bodySize = 1 + pos;
            int frameSize = Protocol.varIntSize(bodySize) + bodySize;
            out = ctx.alloc()
                .buffer(frameSize);
            Protocol.writeVarInt(out, bodySize);
            out.writeByte(marker);
            out.writeBytes(buf, 0, pos);

            stats.messages++;
            stats.uncompressed += pendingBytes;
            stats.compressed += frameSize;
            if (promises.size() == 1) {
                ctx.write(out, promises.get(0));
            } else {
                ChannelPromise[] waiting = promises.toArray(new ChannelPromise[0]);
                ctx.write(out)
                    .addListener((ChannelFutureListener) f -> {
                        for (ChannelPromise p : waiting) {
                            if (f.isSuccess()) p.trySuccess();
                            else p.tryFailure(f.cause());
                        }
                    });
            }
            out = null;
        } catch (RuntimeException e) {
            if (out != null) out.release();
            for (ChannelPromise p : promises) p.tryFailure(e);
            ctx.fireExceptionCaught(e);
        } finally {
            for (ByteBuf frame : frames) frame.release();
            frames.clear();
            promises.clear();
            pendingBytes = 0;
        }
    }

    private void end() {
        if (ended) return;
        ended = true;
        for (ByteBuf frame : frames) frame.release();
        for (ChannelPromise p : promises) p.tryFailure(new ClosedChannelException());
        frames.clear();
        promises.clear();
        pendingBytes = 0;
        deflater.end();
        if (stats != null) stats.close();
    }
}
