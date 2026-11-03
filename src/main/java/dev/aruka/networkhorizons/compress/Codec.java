package dev.aruka.networkhorizons.compress;

/** Stream codecs. The id is sent in the handshake and in the marker byte of compressed frames. */
public enum Codec {

    DEFLATE(1);

    public final int id;

    Codec(int id) {
        this.id = id;
    }

    public static Codec byId(int id) {
        for (Codec c : values()) if (c.id == id) return c;
        return null;
    }

    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
