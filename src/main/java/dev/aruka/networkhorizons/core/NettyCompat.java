package dev.aruka.networkhorizons.core;

/** Netty 4.0.10 {@code EmptyArrays} fields removed in later 4.0.x, for code compiled against 4.0.10. */
public final class NettyCompat {

    public static final boolean[] EMPTY_BOOLEANS = {};
    public static final double[] EMPTY_DOUBLES = {};
    public static final float[] EMPTY_FLOATS = {};
    public static final int[] EMPTY_INTS = {};
    public static final short[] EMPTY_SHORTS = {};
    public static final long[] EMPTY_LONGS = {};

    private NettyCompat() {}
}
