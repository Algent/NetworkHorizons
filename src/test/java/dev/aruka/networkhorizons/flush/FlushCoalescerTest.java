package dev.aruka.networkhorizons.flush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.network.EnumConnectionState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

class FlushCoalescerTest {

    private static final EnumConnectionState PLAY = EnumConnectionState.PLAY;

    @BeforeEach
    void onServerThread() {
        FlushCoalescer.serverThread = Thread.currentThread();
    }

    /** Stand-in for a NetworkManager with the mixin applied. */
    private static final class Connection implements CoalescingConnection {

        final EmbeddedChannel channel;
        int flushes;
        boolean pending;

        Connection() {
            channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {

                @Override
                public void flush(ChannelHandlerContext ctx) {
                    flushes++;
                    ctx.flush();
                }
            });
        }

        @Override
        public boolean networkhorizons$isPending() {
            return pending;
        }

        @Override
        public void networkhorizons$setPending(boolean p) {
            pending = p;
        }

        @Override
        public Channel networkhorizons$channel() {
            return channel;
        }
    }

    @Test
    void defersOnlyPlainPlayPacketsFromTheServerThread() {
        assertTrue(FlushCoalescer.shouldDefer(true, false, false, 0, PLAY, PLAY, false));
        // FML proxy packets have no vanilla state of their own
        assertTrue(FlushCoalescer.shouldDefer(true, false, false, 0, PLAY, null, true));

        assertFalse(FlushCoalescer.shouldDefer(false, false, false, 0, PLAY, PLAY, false), "disabled");
        assertFalse(FlushCoalescer.shouldDefer(true, true, false, 0, PLAY, PLAY, false), "client side");
        assertFalse(FlushCoalescer.shouldDefer(true, false, true, 0, PLAY, PLAY, false), "local channel");
        assertFalse(
            FlushCoalescer.shouldDefer(true, false, false, 1, PLAY, PLAY, false),
            "kick / disconnect listeners");
        assertFalse(
            FlushCoalescer.shouldDefer(true, false, false, 0, EnumConnectionState.LOGIN, PLAY, false),
            "login state");
        assertFalse(
            FlushCoalescer.shouldDefer(true, false, false, 0, PLAY, EnumConnectionState.LOGIN, false),
            "packet of another state (state change)");
    }

    @Test
    void otherThreadsKeepTheImmediateFlush() throws Exception {
        boolean[] result = new boolean[1];
        Thread t = new Thread(() -> result[0] = FlushCoalescer.shouldDefer(true, false, false, 0, PLAY, PLAY, false));
        t.start();
        t.join();
        assertFalse(result[0]);
    }

    @Test
    void writesWaitForTheFlushAndLeaveTogether() {
        Connection c = new Connection();
        // what the mixin does for a deferred packet: write, no flush
        c.channel.write("a");
        FlushCoalescer.deferred(c);
        c.channel.write("b");
        FlushCoalescer.deferred(c);
        assertNull(c.channel.readOutbound(), "nothing leaves before a flush");

        // vanilla's network tick flush
        c.channel.flush();
        FlushCoalescer.flushed(c);
        assertEquals("a", c.channel.readOutbound());
        assertEquals("b", c.channel.readOutbound());
        assertFalse(c.pending);

        // sent after the network tick (post-tick handlers): flushed at the end of the tick, once
        c.channel.write("c");
        FlushCoalescer.deferred(c);
        c.channel.write("d");
        FlushCoalescer.deferred(c);
        int before = c.flushes;
        FlushCoalescer.flushPending();
        assertEquals(before + 1, c.flushes);
        assertEquals("c", c.channel.readOutbound());
        assertEquals("d", c.channel.readOutbound());

        // nothing pending: no extra flush
        FlushCoalescer.flushPending();
        assertEquals(before + 1, c.flushes);
    }
}
