package dev.aruka.networkhorizons.compress;

import java.util.Arrays;

/** Per-thread work arrays, so a connection holds only its codec state between messages. */
final class Scratch {

    private static final ThreadLocal<Scratch> LOCAL = ThreadLocal.withInitial(Scratch::new);

    private byte[] in = new byte[64 * 1024];
    private byte[] out = new byte[64 * 1024];

    static Scratch get() {
        return LOCAL.get();
    }

    /** Contents are not kept. */
    byte[] in(int size) {
        if (in.length < size) in = new byte[Math.max(size, in.length * 2)];
        return in;
    }

    /** Contents are kept. */
    byte[] out(int size) {
        if (out.length < size) out = Arrays.copyOf(out, Math.max(size, out.length * 2));
        return out;
    }
}
