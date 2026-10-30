package com.zrlog.plugin.data.codec;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.client.ClientActionHandler;
import com.zrlog.plugin.support.MemorySocketChannels.MemoryChannel;
import com.zrlog.plugin.support.MemorySocketChannels.MemorySelector;
import org.junit.Test;

import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class SocketEncodeBudgetTest {
    @Test public void waitingForAnotherWriterConsumesRequestBudgetWithoutWritingASecondFrame() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        MemoryChannel channel = new MemoryChannel() {
            @Override public int write(ByteBuffer buffer) {
                writes.incrementAndGet(); entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                throw new IllegalStateException("End blocked test write");
            }
        };
        SocketEncode encode = new SocketEncode();
        IOSession session = session(channel, encode);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = worker.submit(() -> assertThrows(IllegalStateException.class,
                    () -> encode.doEncode(session, packet(), Duration.ofSeconds(5))));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long start = System.nanoTime();
            assertThrows(SocketTimeoutException.class, () -> encode.doEncode(session, packet(), Duration.ofMillis(50)));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500);
            assertEquals(1, writes.get()); release.countDown(); first.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); session.close();
        }
    }

    @Test public void requestBudgetBoundsProgressAcrossFrameWrites() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger writes = new AtomicInteger();
        MemoryChannel channel = new MemoryChannel() {
            @Override public int write(ByteBuffer buffer) {
                clock.addAndGet(6); writes.incrementAndGet(); buffer.get(); return 1;
            }
        };
        SocketEncode encode = new SocketEncode(clock::get, clock::addAndGet, 500);
        IOSession session = session(channel, encode);
        try {
            assertThrows(SocketTimeoutException.class, () -> encode.doEncode(session, packet(), Duration.ofNanos(10)));
            assertEquals(2, writes.get()); assertEquals(0, session.getSendMsgCounter().get());
        } finally { session.close(); }
    }

    @Test public void failedBudgetedSendClosesSessionAndReleasesPendingRequest() {
        AtomicLong clock = new AtomicLong();
        MemoryChannel channel = new MemoryChannel() {
            @Override public int write(ByteBuffer buffer) { clock.addAndGet(6); buffer.get(); return 1; }
        };
        IOSession session = session(channel, new SocketEncode(clock::get, clock::addAndGet, 500));
        try {
            assertThrows(IllegalStateException.class,
                    () -> session.sendJsonRequest("test", "CAPABILITY_INVOKE", 1, Duration.ofNanos(10)));
            assertTrue(session.isClosed()); assertTrue(session.getPipeMap().isEmpty());
        } finally { session.close(); }
    }

    private static IOSession session(MemoryChannel channel, SocketEncode encode) {
        return new IOSession(channel, new MemorySelector(), new SocketCodec(encode, new SocketDecode(Runnable::run)), new ClientActionHandler());
    }
    private static MsgPacket packet() {
        return new MsgPacket(new byte[]{1}, ContentType.BYTE, MsgPacketStatus.SEND_REQUEST, 1, "SERVICE");
    }
}
