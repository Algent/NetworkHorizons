package dev.aruka.networkhorizons.compress;

import java.nio.charset.StandardCharsets;

import dev.aruka.networkhorizons.NetworkHorizons;
import io.netty.buffer.ByteBuf;

/**
 * Wire format of phase 4.
 *
 * <p>
 * Handshake on our custom payload channel: the server sends HELLO at login, the client answers REPLY, and the server
 * sends START, after which it compresses what it sends. Compressed messages are frames of their own, framed like
 * vanilla ones ({@code [varint length][body]}), whose body starts with {@link #marker}: a vanilla body starts with a
 * one-byte packet id below 0x80, so the two never mix up, and frames sent raw (chunks) need no wrapping.
 */
public final class Protocol {

    public static final String CHANNEL = NetworkHorizons.MODID;
    public static final int VERSION = 1;

    static final byte HELLO = 1;
    static final byte REPLY = 2;
    static final byte START = 3;

    static final int CUSTOM_PAYLOAD_ID = 0x3F;
    static final int CHUNK_DATA_ID = 0x21;
    static final int MAP_CHUNK_BULK_ID = 0x26;

    /** Frame body prefix of an S3F on our channel: packet id, then the channel name as a vanilla string. */
    private static final byte[] PAYLOAD_PREFIX;
    private static final int START_LENGTH = 5;

    static {
        byte[] name = CHANNEL.getBytes(StandardCharsets.UTF_8);
        PAYLOAD_PREFIX = new byte[2 + name.length];
        PAYLOAD_PREFIX[0] = CUSTOM_PAYLOAD_ID;
        PAYLOAD_PREFIX[1] = (byte) name.length;
        System.arraycopy(name, 0, PAYLOAD_PREFIX, 2, name.length);
    }

    private Protocol() {}

    public static byte marker(Codec codec) {
        return (byte) (0x80 | codec.id);
    }

    public static byte[] hello(Codec codec) {
        return new byte[] { HELLO, VERSION, (byte) codec.id };
    }

    /** @param accepted null to decline */
    public static byte[] reply(Codec accepted) {
        return new byte[] { REPLY, VERSION, (byte) (accepted == null ? 0 : accepted.id) };
    }

    public static byte[] start(Codec codec) {
        return new byte[] { START, 'N', 'H', 'Z', (byte) codec.id };
    }

    /**
     * Matches the frame body of our START custom payload without decoding it: our prefix, a short payload length (2 or
     * 3 bytes, FML's var-short), then the 5 START bytes.
     *
     * @return the codec it starts, or null if the body is anything else
     */
    public static Codec startCodec(ByteBuf body) {
        int ri = body.readerIndex(), n = body.readableBytes();
        int min = PAYLOAD_PREFIX.length + 2 + START_LENGTH;
        if (n < min || n > min + 1) return null;
        for (int i = 0; i < PAYLOAD_PREFIX.length; i++) {
            if (body.getByte(ri + i) != PAYLOAD_PREFIX[i]) return null;
        }
        int p = ri + n - START_LENGTH;
        if (body.getByte(p) != START || body.getByte(p + 1) != 'N'
            || body.getByte(p + 2) != 'H'
            || body.getByte(p + 3) != 'Z') return null;
        return Codec.byId(body.getByte(p + 4));
    }

    /**
     * Whether a prepender output buffer is exactly one vanilla frame worth compressing: not a chunk packet (already
     * deflated), and not something unexpected (partial or several frames), which is sent raw instead.
     */
    static boolean compressible(ByteBuf frame) {
        int ri = frame.readerIndex(), n = frame.readableBytes();
        int len = 0, i = 0;
        while (true) {
            if (i == 5 || i >= n) return false;
            byte b = frame.getByte(ri + i);
            len |= (b & 0x7F) << (7 * i);
            i++;
            if (b >= 0) break;
        }
        if (len < 1 || i + len != n) return false;
        byte id = frame.getByte(ri + i);
        return id >= 0 && id != CHUNK_DATA_ID && id != MAP_CHUNK_BULK_ID;
    }

    static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }

    static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }
}
