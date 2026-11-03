package dev.aruka.networkhorizons.flush;

import io.netty.channel.Channel;

/** Implemented by {@code NetworkManager} through a mixin. Server thread only. */
public interface CoalescingConnection {

    boolean networkhorizons$isPending();

    void networkhorizons$setPending(boolean pending);

    Channel networkhorizons$channel();
}
