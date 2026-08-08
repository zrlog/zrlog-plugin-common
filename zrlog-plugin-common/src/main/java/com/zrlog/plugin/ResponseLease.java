package com.zrlog.plugin;

import com.zrlog.plugin.data.codec.MsgPacket;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns a response packet and its transport resources, including temporary FILE data, until closed.
 */
public final class ResponseLease implements AutoCloseable {

    private final MsgPacket packet;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    ResponseLease(MsgPacket packet, Runnable release) {
        this.packet = Objects.requireNonNull(packet, "packet");
        this.release = release;
    }

    public MsgPacket getPacket() {
        return packet;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && release != null) {
            release.run();
        }
    }
}
