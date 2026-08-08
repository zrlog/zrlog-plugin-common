package com.zrlog.plugin.data.codec;

import java.util.concurrent.atomic.AtomicLong;

public final class SocketPacketMemoryBudget {

    private static final SocketPacketMemoryBudget UNLIMITED = new SocketPacketMemoryBudget(Long.MAX_VALUE);

    private final long maxBytes;
    private final AtomicLong reservedBytes = new AtomicLong();

    public SocketPacketMemoryBudget(long maxBytes) {
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("maxBytes must be greater than zero");
        }
        this.maxBytes = maxBytes;
    }

    static SocketPacketMemoryBudget unlimited() {
        return UNLIMITED;
    }

    public boolean tryReserve(int bytes) {
        if (bytes < 0) {
            return false;
        }
        if (bytes == 0) {
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

    public void release(int bytes) {
        if (bytes <= 0) {
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
