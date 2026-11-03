package dev.aruka.networkhorizons.compress;

import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import dev.aruka.networkhorizons.NetworkHorizons;
import dev.aruka.networkhorizons.stats.Direction;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DecoderException;

/**
 * Client side, between the vanilla splitter and decoder, so it gets one whole frame body at a time. Passes bodies
 * through until our START, then inflates compressed frames back into the vanilla frame bodies they hold. The switch
 * happens between two frames, in the order they arrived.
 */
public final class FrameDecompressor extends ChannelInboundHandlerAdapter {

    public static final String NAME = "networkhorizons:decompress";
    /** Inflated size of one compressed frame at most; the server keeps messages far below it. */
    static final int MAX_INFLATED = 4 * FrameCompressor.MAX_MESSAGE;
    private static final byte[] SYNC_TAIL = { 0, 0, (byte) 0xFF, (byte) 0xFF };

    private Codec codec;
    private Inflater inflater;
    private boolean ended;
    private CompressionStats stats;

    /** Call on the event loop, before the client replies to the server's HELLO. */
    public static boolean install(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get("splitter") == null) return false;
        if (pipeline.get(NAME) == null) pipeline.addAfter("splitter", NAME, new FrameDecompressor());
        return true;
    }

    public static boolean active(Channel channel) {
        FrameDecompressor d = (FrameDecompressor) channel.pipeline()
            .get(NAME);
        return d != null && d.codec != null;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf) || ended) {
            ctx.fireChannelRead(msg);
            return;
        }
        ByteBuf body = (ByteBuf) msg;
        int n = body.readableBytes();
        if (codec == null) {
            Codec start = Protocol.startCodec(body);
            ctx.fireChannelRead(body);
            if (start != null) begin(ctx, start);
            return;
        }
        if (n == 0 || body.getByte(body.readerIndex()) >= 0) {
            stats.raw += n + Protocol.varIntSize(n);
            ctx.fireChannelRead(body);
            return;
        }
        try {
            if (body.getByte(body.readerIndex()) != Protocol.marker(codec)) {
                throw new DecoderException("NetworkHorizons: unknown compressed frame marker");
            }
            inflate(ctx, body, n);
        } finally {
            body.release();
        }
    }

    private void begin(ChannelHandlerContext ctx, Codec start) {
        codec = start;
        inflater = new Inflater(true);
        stats = CompressionStats.open(ctx.channel(), Direction.IN);
        NetworkHorizons.LOG.info(
            "Compression on ({}) from {}",
            start.label(),
            ctx.channel()
                .remoteAddress());
    }

    private void inflate(ChannelHandlerContext ctx, ByteBuf body, int n) {
        Scratch scratch = Scratch.get();
        byte[] in = scratch.in(n - 1 + SYNC_TAIL.length);
        body.getBytes(body.readerIndex() + 1, in, 0, n - 1);
        System.arraycopy(SYNC_TAIL, 0, in, n - 1, SYNC_TAIL.length);
        inflater.setInput(in, 0, n - 1 + SYNC_TAIL.length);

        byte[] out = scratch.out(0);
        int pos = 0;
        try {
            while (true) {
                if (pos == out.length) {
                    if (pos >= MAX_INFLATED) throw new DecoderException("NetworkHorizons: compressed frame too large");
                    out = scratch.out(Math.min(MAX_INFLATED, pos * 2));
                }
                int r = inflater.inflate(out, pos, out.length - pos);
                pos += r;
                if (r == 0) {
                    // the sync flush tail must be consumed too, or the next frame desyncs
                    if (inflater.needsInput()) break;
                    throw new DecoderException("NetworkHorizons: corrupt compressed frame");
                }
            }
        } catch (DataFormatException e) {
            throw new DecoderException("NetworkHorizons: corrupt compressed frame", e);
        }

        stats.messages++;
        stats.compressed += n + Protocol.varIntSize(n);
        stats.uncompressed += pos;

        ByteBuf all = ctx.alloc()
            .heapBuffer(pos);
        try {
            all.writeBytes(out, 0, pos);
            int off = 0;
            while (off < pos) {
                int len = 0, i = 0;
                while (true) {
                    if (i == 5 || off + i >= pos) throw new DecoderException("NetworkHorizons: corrupt inner frame");
                    // not out: firing a frame may run code that reuses this thread's scratch arrays
                    byte b = all.getByte(off + i);
                    len |= (b & 0x7F) << (7 * i);
                    i++;
                    if (b >= 0) break;
                }
                int start = off + i;
                if (len < 0 || len > pos - start) throw new DecoderException("NetworkHorizons: corrupt inner frame");
                ctx.fireChannelRead(
                    all.slice(start, len)
                        .retain());
                off = start + len;
            }
        } finally {
            all.release();
        }
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

    private void end() {
        if (ended) return;
        ended = true;
        if (inflater != null) inflater.end();
        if (stats != null) stats.close();
    }
}
