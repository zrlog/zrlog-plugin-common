package com.zrlog.plugin;

import com.zrlog.plugin.data.codec.MsgPacket;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PipeInfoTest {

    @Test
    public void shouldReleaseReplacedAndRemovedResponsesExactlyOnce() {
        PipeInfo pipeInfo = new PipeInfo(null, null, null, 1L, 2L);
        AtomicInteger firstRelease = new AtomicInteger();
        AtomicInteger secondRelease = new AtomicInteger();

        pipeInfo.setResponseMsgPacket(new MsgPacket(), firstRelease::incrementAndGet);
        pipeInfo.setResponseMsgPacket(new MsgPacket(), secondRelease::incrementAndGet);

        assertEquals(1, firstRelease.get());
        assertEquals(0, secondRelease.get());
        pipeInfo.releaseResponse();
        pipeInfo.releaseResponse();
        assertEquals(1, firstRelease.get());
        assertEquals(1, secondRelease.get());
    }

    @Test
    public void shouldTransferResponseOwnershipToLeaseExactlyOnce() {
        PipeInfo pipeInfo = new PipeInfo(null, null, null, 1L, 2L);
        MsgPacket response = new MsgPacket();
        AtomicInteger responseRelease = new AtomicInteger();
        AtomicInteger duplicateRelease = new AtomicInteger();
        pipeInfo.setResponseMsgPacket(response, responseRelease::incrementAndGet);

        ResponseLease lease = pipeInfo.claimResponse();

        assertSame(response, lease.getPacket());
        assertNull(pipeInfo.getResponseMsgPacket());
        pipeInfo.releaseResponse();
        pipeInfo.setResponseMsgPacket(new MsgPacket(), duplicateRelease::incrementAndGet);
        assertEquals(0, responseRelease.get());
        assertEquals(1, duplicateRelease.get());

        lease.close();
        lease.close();
        assertEquals(1, responseRelease.get());
    }

    @Test
    public void shouldNotifyRequestAbortExactlyOnceWithoutResponse() {
        AtomicInteger aborts = new AtomicInteger();
        PipeInfo pipeInfo = new PipeInfo(null, null, null, 1L, 2L, aborts::incrementAndGet);

        assertTrue(pipeInfo.releaseExpiredResponse());
        pipeInfo.releaseExpiredResponse();
        pipeInfo.releaseResponse();

        assertEquals(1, aborts.get());
    }

    @Test
    public void shouldNotNotifyRequestAbortAfterResponseCompletes() {
        AtomicInteger aborts = new AtomicInteger();
        PipeInfo pipeInfo = new PipeInfo(null, null, null, 1L, 2L, aborts::incrementAndGet);
        pipeInfo.setResponseMsgPacket(new MsgPacket());

        pipeInfo.completeResponse();
        pipeInfo.releaseResponse();

        assertEquals(0, aborts.get());
    }

    @Test
    public void shouldReleaseExactlyOnceWhenClaimRacesCleanup() throws Exception {
        for (int i = 0; i < 100; i++) {
            PipeInfo pipeInfo = new PipeInfo(null, null, null, 1L, 2L);
            AtomicInteger releases = new AtomicInteger();
            AtomicReference<ResponseLease> claimed = new AtomicReference<>();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            pipeInfo.setResponseMsgPacket(new MsgPacket(), releases::incrementAndGet);
            Thread claimant = new Thread(() -> {
                ready.countDown();
                await(start);
                claimed.set(pipeInfo.claimResponse());
            });
            Thread cleanup = new Thread(() -> {
                ready.countDown();
                await(start);
                pipeInfo.releaseResponse();
            });
            claimant.start();
            cleanup.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            claimant.join(5000);
            cleanup.join(5000);
            assertFalse(claimant.isAlive());
            assertFalse(cleanup.isAlive());

            ResponseLease lease = claimed.get();
            if (lease != null) {
                lease.close();
                lease.close();
            }
            assertEquals(1, releases.get());
        }
    }

    @Test
    public void shouldReleaseRequestExactlyOnceWhenResponseTimeoutAndCloseRace() throws Exception {
        for (int i = 0; i < 100; i++) {
            AtomicInteger requestReleases = new AtomicInteger();
            AtomicInteger responseReleases = new AtomicInteger();
            PipeInfo pipeInfo = new PipeInfo(new MsgPacket(), null, null, 1L, 2L,
                    () -> {
                    }, requestReleases::incrementAndGet);
            CountDownLatch ready = new CountDownLatch(3);
            CountDownLatch start = new CountDownLatch(1);
            Thread response = new Thread(() -> {
                ready.countDown();
                await(start);
                pipeInfo.setResponseMsgPacket(new MsgPacket(), responseReleases::incrementAndGet);
            });
            Thread timeout = new Thread(() -> {
                ready.countDown();
                await(start);
                pipeInfo.releaseExpiredResponse();
            });
            Thread close = new Thread(() -> {
                ready.countDown();
                await(start);
                pipeInfo.releaseResponse();
            });
            response.start();
            timeout.start();
            close.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            response.join(5000);
            timeout.join(5000);
            close.join(5000);

            assertFalse(response.isAlive());
            assertFalse(timeout.isAlive());
            assertFalse(close.isAlive());
            assertNull(pipeInfo.getRequestMsgPackage());
            assertEquals(1, requestReleases.get());
            assertEquals(1, responseReleases.get());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
