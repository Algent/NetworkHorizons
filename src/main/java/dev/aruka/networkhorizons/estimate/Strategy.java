package dev.aruka.networkhorizons.estimate;

/** Compression strategies compared on the same frames. */
public enum Strategy {

    /** Each packet alone, zlib level 6 from 256 bytes, with a varint uncompressed length (what 1.8 does). */
    PER_PACKET("per packet, deflate >=256 B (1.8 style)"),
    /** One deflate context per connection, sync flush after every packet (the phase 4 design). */
    STREAM_L1("stream deflate L1, flush per packet"),
    STREAM_L6("stream deflate L6, flush per packet"),
    /** Same, but one flush per tick: needs several packets per frame (phase 3 batching). */
    STREAM_L6_TICK("stream deflate L6, flush per tick");

    public final String description;

    Strategy(String description) {
        this.description = description;
    }
}
