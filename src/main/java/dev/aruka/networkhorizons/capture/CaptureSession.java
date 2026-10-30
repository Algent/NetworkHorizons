package dev.aruka.networkhorizons.capture;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import net.minecraft.network.EnumConnectionState;
import net.minecraft.server.MinecraftServer;

import dev.aruka.networkhorizons.stats.Direction;

/**
 * Raw capture of one connection, both directions, to a gzipped file. Format (big endian):
 *
 * <pre>
 * header: "NHCAP" | u8 version (1) | u8 side (0 server, 1 client) | u16 label length | label (UTF-8)
 *         | i64 start epoch millis
 * record: i64 nanos since start | u8 direction (0 in, 1 out, relative to the side) | u8 state
 *         (0 handshaking, 1 play, 2 status, 3 login) | i32 server tick (-1 on clients) | varint length | frame body
 * </pre>
 *
 * The frame body is the packet id and data, before the length prefix and before encryption.
 */
public final class CaptureSession {

    static final byte[] MAGIC = { 'N', 'H', 'C', 'A', 'P' };
    static final int VERSION = 1;

    public final String label;
    public final File file;
    public final boolean client;
    final long startNanos = System.nanoTime();
    final int endSeconds;

    volatile boolean active = true;
    volatile long stopNanos;
    /** Written only by the connection's event loop. */
    volatile long dropped;
    /** Writer thread only. */
    volatile long frames;
    volatile long bytes;
    volatile String error;
    /** Writer thread only: the file is closed, later frames are ignored. */
    boolean closed;
    private DataOutputStream out;

    CaptureSession(String label, File file, boolean client, int endSeconds) {
        this.label = label;
        this.file = file;
        this.client = client;
        this.endSeconds = endSeconds;
    }

    /** Builds one record on the connection's event loop. */
    byte[] record(Direction dir, EnumConnectionState state, byte[] body) {
        int tick = -1;
        if (!client) {
            MinecraftServer server = MinecraftServer.getServer();
            if (server != null) tick = server.getTickCounter();
        }
        int len = body.length;
        int varint = varIntSize(len);
        byte[] r = new byte[8 + 1 + 1 + 4 + varint + len];
        long nanos = System.nanoTime() - startNanos;
        for (int i = 0; i < 8; i++) r[i] = (byte) (nanos >>> (56 - 8 * i));
        r[8] = (byte) (dir == Direction.OUT ? 1 : 0);
        r[9] = stateId(state);
        r[10] = (byte) (tick >>> 24);
        r[11] = (byte) (tick >>> 16);
        r[12] = (byte) (tick >>> 8);
        r[13] = (byte) tick;
        int p = 14;
        int v = len;
        while ((v & ~0x7F) != 0) {
            r[p++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        r[p++] = (byte) v;
        System.arraycopy(body, 0, r, p, len);
        return r;
    }

    /** Vanilla protocol state id (-1 handshaking, 0 play, 1 status, 2 login) plus one, -1 when unknown. */
    static byte stateId(EnumConnectionState state) {
        return (byte) (state == null ? -1 : state.func_150759_c() + 1);
    }

    /** Writer thread only. */
    void write(byte[] record) throws IOException {
        if (out == null) open();
        out.write(record);
        frames++;
        bytes += record.length;
    }

    private void open() throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        out = new DataOutputStream(
            new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(file), 1 << 16), 1 << 16));
        out.write(MAGIC);
        out.writeByte(VERSION);
        out.writeByte(client ? 1 : 0);
        byte[] l = label.getBytes(StandardCharsets.UTF_8);
        out.writeShort(l.length);
        out.write(l);
        out.writeLong(System.currentTimeMillis() - (System.nanoTime() - startNanos) / 1_000_000L);
    }

    /** Writer thread only. */
    void close() {
        closed = true;
        try {
            if (out == null) open();
            out.close();
        } catch (IOException e) {
            error = e.getMessage();
        }
    }

    public boolean isActive() {
        return active;
    }

    public long frames() {
        return frames;
    }

    public long bytes() {
        return bytes;
    }

    public long dropped() {
        return dropped;
    }

    public String error() {
        return error;
    }

    public double seconds() {
        long end = active ? System.nanoTime() : stopNanos;
        return (end - startNanos) / 1e9;
    }

    static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }
}
