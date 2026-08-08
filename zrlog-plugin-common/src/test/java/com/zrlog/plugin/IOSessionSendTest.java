package com.zrlog.plugin;

import com.zrlog.plugin.client.ClientActionHandler;
import com.zrlog.plugin.data.codec.ContentType;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.data.codec.SocketCodec;
import com.zrlog.plugin.data.codec.SocketDecode;
import com.zrlog.plugin.data.codec.SocketEncode;
import com.zrlog.plugin.data.codec.SocketPacketMemoryBudget;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class IOSessionSendTest {

    @Test
    public void shouldBoundPendingRequestsPerSession() throws Exception {
        RecordingSocketEncode encode = new RecordingSocketEncode();
        try (SessionHarness harness = new SessionHarness(encode)) {
            for (int i = 0; i < IOSession.DEFAULT_MAX_PENDING_REQUESTS; i++) {
                harness.session.sendMsg(request(i));
            }

            assertEquals(IOSession.DEFAULT_MAX_PENDING_REQUESTS, harness.session.getPipeMap().size());
            assertEquals(IOSession.DEFAULT_MAX_PENDING_REQUESTS, encode.calls);

            harness.session.sendMsg(request(IOSession.DEFAULT_MAX_PENDING_REQUESTS));

            assertEquals(IOSession.DEFAULT_MAX_PENDING_REQUESTS, harness.session.getPipeMap().size());
            assertEquals(IOSession.DEFAULT_MAX_PENDING_REQUESTS, encode.calls);
        }
    }

    @Test
    public void shouldRejectRequestThatExceedsPerSessionByteBudget() throws Exception {
        RecordingSocketEncode encode = new RecordingSocketEncode();
        SocketPacketMemoryBudget sessionBudget = new SocketPacketMemoryBudget(1);
        SocketPacketMemoryBudget globalBudget = new SocketPacketMemoryBudget(2);
        try (SessionHarness harness = new SessionHarness(encode, 2, sessionBudget, globalBudget)) {
            harness.session.sendMsg(request(1));
            harness.session.sendMsg(request(2));

            assertEquals(1, encode.calls);
            assertEquals(1, harness.session.getPipeMap().size());
            assertEquals(1, sessionBudget.getReservedBytes());
            assertEquals(1, globalBudget.getReservedBytes());
        }
        assertEquals(0, sessionBudget.getReservedBytes());
        assertEquals(0, globalBudget.getReservedBytes());
    }

    @Test
    public void shouldRejectRequestThatExceedsSharedGlobalByteBudget() throws Exception {
        RecordingSocketEncode firstEncode = new RecordingSocketEncode();
        RecordingSocketEncode secondEncode = new RecordingSocketEncode();
        SocketPacketMemoryBudget globalBudget = new SocketPacketMemoryBudget(1);
        try (SessionHarness first = new SessionHarness(firstEncode, 2,
                new SocketPacketMemoryBudget(2), globalBudget);
             SessionHarness second = new SessionHarness(secondEncode, 2,
                     new SocketPacketMemoryBudget(2), globalBudget)) {
            first.session.sendMsg(request(1));
            second.session.sendMsg(request(2));

            assertEquals(1, firstEncode.calls);
            assertEquals(0, secondEncode.calls);
            assertEquals(1, globalBudget.getReservedBytes());

            first.session.close();
            second.session.sendMsg(request(3));

            assertEquals(1, secondEncode.calls);
            assertEquals(1, globalBudget.getReservedBytes());
        }
        assertEquals(0, globalBudget.getReservedBytes());
    }

    @Test
    public void shouldReleaseRequestBytesWhenResponseIsAccepted() throws Exception {
        RecordingSocketEncode encode = new RecordingSocketEncode();
        SocketPacketMemoryBudget sessionBudget = new SocketPacketMemoryBudget(1);
        SocketPacketMemoryBudget globalBudget = new SocketPacketMemoryBudget(1);
        try (SessionHarness harness = new SessionHarness(encode, 2, sessionBudget, globalBudget)) {
            harness.session.sendMsg(request(1));

            harness.session.dispose(response(1));

            assertEquals(0, sessionBudget.getReservedBytes());
            assertEquals(0, globalBudget.getReservedBytes());
            assertNull(harness.session.getRequestMsgPacketByMsgId(1));
            try (ResponseLease lease = harness.session.getResponseLeaseByMsgId(1, Duration.ofMillis(50))) {
                assertSame(MsgPacketStatus.RESPONSE_SUCCESS, lease.getPacket().getStatus());
            }
        }
    }

    @Test
    public void shouldReleaseRequestBeforeCallbackButRetainResponseUntilCallbackCompletes() throws Exception {
        RecordingSocketEncode encode = new RecordingSocketEncode();
        SocketPacketMemoryBudget sessionBudget = new SocketPacketMemoryBudget(1);
        SocketPacketMemoryBudget globalBudget = new SocketPacketMemoryBudget(1);
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch finishCallback = new CountDownLatch(1);
        AtomicInteger responseReleases = new AtomicInteger();
        try (SessionHarness harness = new SessionHarness(encode, 2, sessionBudget, globalBudget)) {
            harness.session.sendMsg(request(1), ignored -> {
                callbackStarted.countDown();
                await(finishCallback);
            });
            Thread responseThread = new Thread(() ->
                    harness.session.dispose(response(1), responseReleases::incrementAndGet));
            responseThread.start();
            assertTrue(callbackStarted.await(5, TimeUnit.SECONDS));

            assertEquals(0, sessionBudget.getReservedBytes());
            assertEquals(0, globalBudget.getReservedBytes());
            assertEquals(0, responseReleases.get());

            harness.session.close();
            assertTrue(harness.session.getPipeMap().isEmpty());
            assertEquals(0, responseReleases.get());

            finishCallback.countDown();
            responseThread.join(5000);
            assertFalse(responseThread.isAlive());
            assertEquals(1, responseReleases.get());
        } finally {
            finishCallback.countDown();
        }
    }

    @Test
    public void shouldReleaseRequestBytesAfterTimeoutCloseAndEncodeFailure() throws Exception {
        SocketPacketMemoryBudget timeoutSessionBudget = new SocketPacketMemoryBudget(1);
        SocketPacketMemoryBudget timeoutGlobalBudget = new SocketPacketMemoryBudget(1);
        try (SessionHarness harness = new SessionHarness(new RecordingSocketEncode(), 2,
                timeoutSessionBudget, timeoutGlobalBudget)) {
            harness.session.sendMsg(request(1), null, Duration.ofMillis(1));
            assertNull(harness.session.getResponseLeaseByMsgId(1, Duration.ofMillis(1)));
            assertEquals(0, timeoutSessionBudget.getReservedBytes());
            assertEquals(0, timeoutGlobalBudget.getReservedBytes());
        }

        SocketPacketMemoryBudget closeSessionBudget = new SocketPacketMemoryBudget(1);
        SocketPacketMemoryBudget closeGlobalBudget = new SocketPacketMemoryBudget(1);
        try (SessionHarness harness = new SessionHarness(new RecordingSocketEncode(), 2,
                closeSessionBudget, closeGlobalBudget)) {
            harness.session.sendMsg(request(2));
            harness.session.close();
            assertEquals(0, closeSessionBudget.getReservedBytes());
            assertEquals(0, closeGlobalBudget.getReservedBytes());
        }

        RecordingSocketEncode failingEncode = new RecordingSocketEncode();
        failingEncode.failure = new IOException("test encode failure");
        SocketPacketMemoryBudget failureSessionBudget = new SocketPacketMemoryBudget(1);
        SocketPacketMemoryBudget failureGlobalBudget = new SocketPacketMemoryBudget(1);
        try (SessionHarness harness = new SessionHarness(failingEncode, 2,
                failureSessionBudget, failureGlobalBudget)) {
            harness.session.sendMsg(request(3));
            assertEquals(0, failureSessionBudget.getReservedBytes());
            assertEquals(0, failureGlobalBudget.getReservedBytes());
        }
    }

    @Test
    public void duplicateAndCountRejectionShouldNotLeakRequestBytes() throws Exception {
        RecordingSocketEncode encode = new RecordingSocketEncode();
        SocketPacketMemoryBudget sessionBudget = new SocketPacketMemoryBudget(2);
        SocketPacketMemoryBudget globalBudget = new SocketPacketMemoryBudget(2);
        try (SessionHarness harness = new SessionHarness(encode, 1, sessionBudget, globalBudget)) {
            harness.session.sendMsg(request(1));
            MsgPacket replacement = request(1);
            harness.session.sendMsg(replacement);

            assertSame(replacement, harness.session.getRequestMsgPacketByMsgId(1));
            assertEquals(1, sessionBudget.getReservedBytes());
            assertEquals(1, globalBudget.getReservedBytes());

            harness.session.sendMsg(request(2));
            assertEquals(2, encode.calls);
            assertEquals(1, sessionBudget.getReservedBytes());
            assertEquals(1, globalBudget.getReservedBytes());
        }
        assertEquals(0, sessionBudget.getReservedBytes());
        assertEquals(0, globalBudget.getReservedBytes());
    }

    @Test
    public void requestApiShouldFailImmediatelyWhenPendingLimitIsReached() throws Exception {
        RecordingSocketEncode encode = new RecordingSocketEncode();
        try (SessionHarness harness = new SessionHarness(encode)) {
            for (int i = 0; i < IOSession.DEFAULT_MAX_PENDING_REQUESTS; i++) {
                harness.session.sendMsg(request(i));
            }

            try {
                harness.session.requestService("comment", new HashMap<>());
                throw new AssertionError("request should fail when the pending limit is reached");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("Unable to send plugin request"));
            }

            assertEquals(IOSession.DEFAULT_MAX_PENDING_REQUESTS, harness.session.getPipeMap().size());
            assertEquals(IOSession.DEFAULT_MAX_PENDING_REQUESTS, encode.calls);
        }
    }

    @Test
    public void shouldRejectSendAfterClose() throws Exception {
        RecordingSocketEncode encode = new RecordingSocketEncode();
        try (SessionHarness harness = new SessionHarness(encode)) {
            harness.session.close();

            harness.session.sendMsg(request(1));

            assertTrue(harness.session.getPipeMap().isEmpty());
            assertEquals(0, encode.calls);
        }
    }

    @Test
    public void shouldRollbackRegisteredRequestWhenEncodeFails() throws Exception {
        RecordingSocketEncode encode = new RecordingSocketEncode();
        encode.failure = new IOException("test encode failure");
        try (SessionHarness harness = new SessionHarness(encode)) {
            harness.session.sendMsg(request(1));

            assertTrue(harness.session.getPipeMap().isEmpty());
            assertTrue(harness.session.isClosed());
            assertEquals(1, encode.calls);
        }
    }

    @Test
    public void shouldNotRollbackNewerRequestWithSameMessageId() throws Exception {
        RecordingSocketEncode encode = new RecordingSocketEncode();
        AtomicBoolean replaceOnFirstEncode = new AtomicBoolean(true);
        try (SessionHarness harness = new SessionHarness(encode)) {
            MsgPacket newerRequest = request(7);
            encode.beforeEncode = () -> {
                if (replaceOnFirstEncode.getAndSet(false)) {
                    harness.session.sendMsg(newerRequest);
                    harness.session.expectPipeInfoAtClose(7, harness.session.getPipeMap().get(7));
                    throw new IllegalStateException("first encode fails after replacement");
                }
            };

            harness.session.sendMsg(request(7));

            assertTrue(harness.session.expectedPipeInfoWasPresentAtClose);
            assertSame(newerRequest, harness.session.expectedRequestAtClose);
            assertTrue(harness.session.isClosed());
            assertTrue(harness.session.getPipeMap().isEmpty());
            assertEquals(2, encode.calls);
        }
    }

    @Test
    public void shouldReleaseDisplacedResponseForDuplicateMessageIdExactlyOnce() throws Exception {
        RecordingSocketEncode encode = new RecordingSocketEncode();
        AtomicInteger releases = new AtomicInteger();
        try (SessionHarness harness = new SessionHarness(encode)) {
            harness.session.sendMsg(request(9));
            PipeInfo displaced = harness.session.getPipeMap().get(9);
            harness.session.dispose(response(9), releases::incrementAndGet);

            MsgPacket replacement = request(9);
            harness.session.sendMsg(replacement);

            assertSame(replacement, harness.session.getRequestMsgPacketByMsgId(9));
            assertEquals(1, releases.get());
            displaced.releaseResponse();
            assertEquals(1, releases.get());
        }
    }

    @Test
    public void callbackCleanupShouldNotRemoveReplacementWithSameMessageId() throws Exception {
        RecordingSocketEncode encode = new RecordingSocketEncode();
        AtomicInteger releases = new AtomicInteger();
        try (SessionHarness harness = new SessionHarness(encode)) {
            int msgId = 10;
            MsgPacket replacement = request(msgId);
            harness.session.sendMsg(request(msgId), ignored -> harness.session.sendMsg(replacement));

            harness.session.dispose(response(msgId), releases::incrementAndGet);

            assertSame(replacement, harness.session.getRequestMsgPacketByMsgId(msgId));
            assertEquals(1, releases.get());
        }
    }

    @Test
    public void callbackResponseAfterTimeoutShouldReleasePacketExactlyOnce() throws Exception {
        RecordingSocketEncode encode = new RecordingSocketEncode();
        AtomicInteger releases = new AtomicInteger();
        try (SessionHarness harness = new SessionHarness(encode)) {
            int msgId = 11;
            PipeInfo timedOut = new PipeInfo(request(msgId), null, ignored -> {
            }, System.currentTimeMillis(), System.currentTimeMillis() + 5000);
            assertTrue(timedOut.releaseExpiredResponse());
            harness.session.getPipeMap().put(msgId, timedOut);

            harness.session.dispose(response(msgId), releases::incrementAndGet);

            assertEquals(1, releases.get());
        }
    }

    @Test
    public void oldWaiterShouldNotRemoveReplacementWithSameMessageId() throws Exception {
        RecordingSocketEncode encode = new RecordingSocketEncode();
        try (SessionHarness harness = new SessionHarness(encode)) {
            int msgId = 17;
            CountDownLatch claimStarted = new CountDownLatch(1);
            CountDownLatch continueClaim = new CountDownLatch(1);
            PipeInfo oldPipe = new BlockingClaimPipeInfo(claimStarted, continueClaim);
            PipeInfo replacement = new PipeInfo(request(msgId), null, null,
                    System.currentTimeMillis(), System.currentTimeMillis() + 5000);
            harness.session.getPipeMap().put(msgId, oldPipe);
            AtomicReference<ResponseLease> result = new AtomicReference<>();
            Thread waiter = new Thread(() -> result.set(
                    harness.session.getResponseLeaseByMsgId(msgId, Duration.ofSeconds(1))));
            waiter.start();
            assertTrue(claimStarted.await(5, TimeUnit.SECONDS));

            harness.session.getPipeMap().put(msgId, replacement);
            continueClaim.countDown();
            waiter.join(5000);

            assertFalse(waiter.isAlive());
            assertNull(result.get());
            assertSame(replacement, harness.session.getPipeMap().get(msgId));
        }
    }

    @Test
    public void timeoutShouldRetireExactPipeAndReleaseLateResponse() throws Exception {
        RecordingSocketEncode encode = new RecordingSocketEncode();
        AtomicInteger releases = new AtomicInteger();
        try (SessionHarness harness = new SessionHarness(encode)) {
            int msgId = 18;
            PipeInfo timedOut = new PipeInfo(request(msgId), null, null,
                    System.currentTimeMillis(), System.currentTimeMillis() + 5000);
            harness.session.getPipeMap().put(msgId, timedOut);

            ResponseLease lease = harness.session.getResponseLeaseByMsgId(msgId, Duration.ofMillis(1));

            assertNull(lease);
            assertFalse(harness.session.getPipeMap().containsKey(msgId));
            timedOut.setResponseMsgPacket(response(msgId), releases::incrementAndGet);
            assertEquals(1, releases.get());
        }
    }

    private static MsgPacket request(int msgId) {
        return new MsgPacket(new byte[]{1}, ContentType.BYTE, MsgPacketStatus.SEND_REQUEST, msgId, "SERVICE");
    }

    private static MsgPacket response(int msgId) {
        return new MsgPacket(new byte[]{2}, ContentType.BYTE, MsgPacketStatus.RESPONSE_SUCCESS, msgId, "SERVICE");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class RecordingSocketEncode extends SocketEncode {

        private int calls;
        private Runnable beforeEncode;
        private Exception failure;

        @Override
        public void doEncode(IOSession session, MsgPacket msgPacket) throws Exception {
            calls++;
            if (beforeEncode != null) {
                beforeEncode.run();
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static class BlockingClaimPipeInfo extends PipeInfo {

        private final CountDownLatch claimStarted;
        private final CountDownLatch continueClaim;

        private BlockingClaimPipeInfo(CountDownLatch claimStarted, CountDownLatch continueClaim) {
            super(null, null, null, System.currentTimeMillis(), System.currentTimeMillis() + 5000);
            this.claimStarted = claimStarted;
            this.continueClaim = continueClaim;
        }

        @Override
        synchronized ResponseLease claimResponse() {
            claimStarted.countDown();
            try {
                if (!continueClaim.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to continue response claim");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            return super.claimResponse();
        }
    }

    private static class SessionHarness implements AutoCloseable {

        private final ServerSocketChannel server;
        private final SocketChannel sender;
        private final Selector selector;
        private final RecordingCloseIOSession session;

        private SessionHarness(SocketEncode socketEncode) throws Exception {
            this(socketEncode, IOSession.DEFAULT_MAX_PENDING_REQUESTS,
                    new SocketPacketMemoryBudget(IOSession.DEFAULT_MAX_PENDING_REQUEST_BYTES),
                    new SocketPacketMemoryBudget(IOSession.DEFAULT_MAX_PENDING_REQUEST_BYTES));
        }

        private SessionHarness(SocketEncode socketEncode,
                               int maxPendingRequests,
                               SocketPacketMemoryBudget pendingRequestMemoryBudget,
                               SocketPacketMemoryBudget globalPendingRequestMemoryBudget) throws Exception {
            server = ServerSocketChannel.open();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            sender = SocketChannel.open(server.getLocalAddress());
            SocketChannel receiver = server.accept();
            selector = Selector.open();
            session = new RecordingCloseIOSession(receiver, selector,
                    new SocketCodec(socketEncode, new SocketDecode(Runnable::run)), new ClientActionHandler(),
                    maxPendingRequests, pendingRequestMemoryBudget, globalPendingRequestMemoryBudget);
        }

        @Override
        public void close() throws Exception {
            session.close();
            sender.close();
            server.close();
            selector.close();
        }
    }

    private static class RecordingCloseIOSession extends IOSession {

        private int expectedMessageIdAtClose;
        private PipeInfo expectedPipeInfoAtClose;
        private MsgPacket expectedRequestAtClose;
        private boolean expectedPipeInfoWasPresentAtClose;
        private boolean firstCloseObserved;

        private RecordingCloseIOSession(SocketChannel channel, Selector selector, SocketCodec socketCodec,
                                        ClientActionHandler actionHandler,
                                        int maxPendingRequests,
                                        SocketPacketMemoryBudget pendingRequestMemoryBudget,
                                        SocketPacketMemoryBudget globalPendingRequestMemoryBudget) {
            super(channel, selector, socketCodec, actionHandler, null, maxPendingRequests,
                    pendingRequestMemoryBudget, globalPendingRequestMemoryBudget);
        }

        private void expectPipeInfoAtClose(int msgId, PipeInfo pipeInfo) {
            expectedMessageIdAtClose = msgId;
            expectedPipeInfoAtClose = pipeInfo;
        }

        @Override
        public void close() {
            if (!firstCloseObserved) {
                firstCloseObserved = true;
                expectedPipeInfoWasPresentAtClose = expectedPipeInfoAtClose != null
                        && getPipeMap().get(expectedMessageIdAtClose) == expectedPipeInfoAtClose;
                expectedRequestAtClose = expectedPipeInfoAtClose == null
                        ? null : expectedPipeInfoAtClose.getRequestMsgPackage();
            }
            super.close();
        }
    }
}
