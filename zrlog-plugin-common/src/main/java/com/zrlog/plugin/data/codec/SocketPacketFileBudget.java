package com.zrlog.plugin.data.codec;

import java.util.concurrent.atomic.AtomicLong;

public final class SocketPacketFileBudget {

    private static final SocketPacketFileBudget UNLIMITED = new SocketPacketFileBudget(Long.MAX_VALUE);

    private final long maxBytes;
    private final AtomicLong reservedBytes = new AtomicLong();

    public SocketPacketFileBudget(long maxBytes) {
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("maxBytes must be greater than zero");
        }
        this.maxBytes = maxBytes;
    }

    static SocketPacketFileBudget unlimited() {
        return UNLIMITED;
    }

    public boolean tryReserve(long bytes) {
        if (bytes < 0L) {
            return false;
        }
        if (bytes == 0L) {
            return true;
        }
        while (true) {
            long current = reservedBytes.get();
            if (current > maxBytes - bytes) {
                return false;
            }
            if (reservedBytes.compareAndSet(current, current + bytes)) {
                return true;
            }
        }
    }

    public void release(long bytes) {
        if (bytes <= 0L) {
            return;
        }
        reservedBytes.getAndUpdate(current -> Math.max(0L, current - bytes));
    }

    public long getReservedBytes() {
        return reservedBytes.get();
    }

    public long getMaxBytes() {
        return maxBytes;
    }
}
