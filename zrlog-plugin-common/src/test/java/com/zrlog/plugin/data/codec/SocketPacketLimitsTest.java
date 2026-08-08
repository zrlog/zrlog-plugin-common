package com.zrlog.plugin.data.codec;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.PipeInfo;
import com.zrlog.plugin.ResponseLease;
import com.zrlog.plugin.client.ClientActionHandler;
import com.zrlog.plugin.common.HexaConversionUtil;
import com.zrlog.plugin.type.ActionType;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SocketPacketLimitsTest {

    @Test
    public void shouldRejectNegativeDataLengthBeforeAllocation() throws Exception {
        try (DecodeHarness harness = new DecodeHarness(1024)) {
            harness.write(frameMetadata((byte) 1, "A", -1));

            ProtocolException exception = expectProtocolException(harness);

            assertTrue(exception.getMessage().contains("BYTE"));
            assertTrue(exception.getMessage().contains("data length: -1"));
        }
    }

    @Test
    public void shouldRejectFileDataLengthAboveConfiguredLimitBeforeAllocation() throws Exception {
        try (DecodeHarness harness = new DecodeHarness(16)) {
            harness.write(frameMetadata((byte) 1, "A", 17, ContentType.FILE));

            ProtocolException exception = expectProtocolException(harness);

            assertTrue(exception.getMessage().contains("FILE"));
            assertTrue(exception.getMessage().contains("data length: 17"));
            assertTrue(exception.getMessage().contains("expected 0..16"));
        }
    }

    @Test
    public void shouldRejectNonPositiveAndOversizedMethodLengths() throws Exception {
        try (DecodeHarness zeroLengthHarness = new DecodeHarness(1024)) {
            zeroLengthHarness.write(frameHeader((byte) 0));
            assertTrue(expectProtocolException(zeroLengthHarness).getMessage().contains("method length: 0"));
        }
        try (DecodeHarness oversizedHarness = new DecodeHarness(1024)) {
            oversizedHarness.write(frameHeader((byte) 0x80));
            assertTrue(expectProtocolException(oversizedHarness).getMessage().contains("method length: -128"));
        }
    }

    @Test
    public void shouldRetainMetadataAcrossPartialNonBlockingReads() throws Exception {
        try (DecodeHarness harness = new DecodeHarness(16)) {
            harness.write(frameHeader((byte) 1));
            assertFalse(harness.decode());
            harness.write(new byte[]{'A', 17, 0});
            assertFalse(harness.decode());
            harness.write(new byte[]{0, 0, ContentType.FILE.getType()});

            ProtocolException exception = expectProtocolException(harness);

            assertTrue(exception.getMessage().contains("FILE"));
            assertTrue(exception.getMessage().contains("data length: 17"));
        }
    }

    @Test
    public void defaultDataLimitShouldCoverWorstCaseFourMiBGsonByteArray() {
        int worstCaseJsonBytes = 4 * 1024 * 1024 * 5 + 2;

        assertTrue(SocketPacketLimits.DEFAULT_MAX_DATA_LENGTH_BYTES > worstCaseJsonBytes);
        assertEquals(32 * 1024 * 1024, SocketPacketLimits.DEFAULT_MAX_DATA_LENGTH_BYTES);
    }

    @Test
    public void shouldIdentifyHandshakeAndHeartbeatAsControlPackets() {
        MsgPacket initConnect = new MsgPacket(new byte[]{1}, ContentType.BYTE,
                MsgPacketStatus.SEND_REQUEST, 1, ActionType.INIT_CONNECT.name());
        MsgPacket heartbeatRequest = new MsgPacket(new byte[0], ContentType.BYTE,
                MsgPacketStatus.SEND_REQUEST, 2, ActionType.HTTP_METHOD.name());
        MsgPacket heartbeatResponse = new MsgPacket(new byte[0], ContentType.BYTE,
                MsgPacketStatus.RESPONSE_SUCCESS, 2, ActionType.HTTP_METHOD.name());

        assertTrue(SocketDecode.isControlPacket(initConnect));
        assertTrue(SocketDecode.isControlPacket(heartbeatRequest));
        assertTrue(SocketDecode.isControlPacket(heartbeatResponse));
    }

    @Test
    public void shouldKeepApplicationMessagesOnTheRegularExecutor() {
        MsgPacket httpRequest = new MsgPacket(new byte[]{1}, ContentType.BYTE,
                MsgPacketStatus.SEND_REQUEST, 1, ActionType.HTTP_METHOD.name());
        MsgPacket jsonHttpRequest = new MsgPacket(new byte[0], ContentType.JSON,
                MsgPacketStatus.SEND_REQUEST, 2, ActionType.HTTP_METHOD.name());
        MsgPacket serviceRequest = new MsgPacket(new byte[0], ContentType.BYTE,
                MsgPacketStatus.SEND_REQUEST, 3, ActionType.SERVICE.name());

        assertFalse(SocketDecode.isControlPacket(httpRequest));
        assertFalse(SocketDecode.isControlPacket(jsonHttpRequest));
        assertFalse(SocketDecode.isControlPacket(serviceRequest));
        assertFalse(SocketDecode.isControlPacket(null));
    }

    @Test
    public void shouldAllowDataLimitOverrideThroughSystemProperty() {
        String originalValue = System.getProperty(SocketPacketLimits.MAX_DATA_LENGTH_PROPERTY);
        try {
            System.setProperty(SocketPacketLimits.MAX_DATA_LENGTH_PROPERTY, "4096");

            assertEquals(4096, SocketPacketLimits.configuredMaxDataLengthBytes());
        } finally {
            if (originalValue == null) {
                System.clearProperty(SocketPacketLimits.MAX_DATA_LENGTH_PROPERTY);
            } else {
                System.setProperty(SocketPacketLimits.MAX_DATA_LENGTH_PROPERTY, originalValue);
            }
        }
    }

    @Test
    public void shouldBoundPartialFramesAcrossSessionsAndReleaseOnClose() throws Exception {
        SocketPacketMemoryBudget budget = new SocketPacketMemoryBudget(16);
        try (DecodeHarness first = new DecodeHarness(16, budget)) {
            first.write(frameMetadata((byte) 1, "A", 12));
            assertFalse(first.decode());
            assertEquals(12L, budget.getReservedBytes());

            try (DecodeHarness second = new DecodeHarness(16, budget)) {
                second.write(frameMetadata((byte) 1, "A", 8));
                ProtocolException exception = expectProtocolException(second);
                assertTrue(exception.getMessage().contains("memory budget exceeded"));
                assertEquals(12L, budget.getReservedBytes());
            }
        }
        assertEquals(0L, budget.getReservedBytes());
    }

    @Test
    public void shouldRetainResponseReservationUntilLeaseCloses() throws Exception {
        SocketPacketMemoryBudget budget = new SocketPacketMemoryBudget(16);
        try (DecodeHarness harness = new DecodeHarness(16, budget)) {
            harness.addPendingResponse(1);
            harness.write(HexaConversionUtil.mergeBytes(
                    frameMetadata(MsgPacketStatus.RESPONSE_SUCCESS, (byte) 1, "A", 12, ContentType.BYTE),
                    new byte[12]));

            assertTrue(harness.decode());
            assertEquals(12L, budget.getReservedBytes());
            ResponseLease lease = harness.readResponseLease(1);
            assertNotNull(lease);
            assertEquals(12L, budget.getReservedBytes());

            harness.closeSession();
            assertEquals(12L, budget.getReservedBytes());
            try (DecodeHarness competing = new DecodeHarness(16, budget)) {
                competing.write(frameMetadata((byte) 1, "B", 5));
                assertTrue(expectProtocolException(competing).getMessage().contains("memory budget exceeded"));
            }
            assertEquals(12L, budget.getReservedBytes());

            lease.close();
            lease.close();
            assertEquals(0L, budget.getReservedBytes());
        }
    }

    @Test
    public void shouldNotDispatchNonEmptyFrameWhenCloseReleasesReservationBeforeDetach() throws Exception {
        SocketPacketMemoryBudget budget = new SocketPacketMemoryBudget(16);
        try (DecodeHarness harness = new DecodeHarness(16, budget)) {
            harness.addPendingResponse(1);
            harness.write(frameMetadata(MsgPacketStatus.RESPONSE_SUCCESS, (byte) 1, "A", 12,
                    ContentType.BYTE));
            assertFalse(harness.decode());
            assertEquals(12L, budget.getReservedBytes());

            harness.closeDecoder();
            assertEquals(0L, budget.getReservedBytes());
            harness.write(new byte[12]);

            assertTrue(harness.decode());
            assertEquals(0, harness.dispatchCount());
            assertNull(harness.pendingResponse(1));
            assertEquals(0L, budget.getReservedBytes());
        }
    }

    @Test
    public void shouldReleaseDecodedFrameReservationWhenDispatchIsRejected() throws Exception {
        SocketPacketMemoryBudget budget = new SocketPacketMemoryBudget(16);
        try (DecodeHarness harness = new DecodeHarness(16, budget, command -> {
            throw new RejectedExecutionException("test rejection");
        })) {
            harness.write(HexaConversionUtil.mergeBytes(
                    frameMetadata((byte) 1, "A", 12), new byte[12]));

            try {
                harness.decode();
                fail("Expected dispatch rejection");
            } catch (RejectedExecutionException expected) {
                // expected
            }

            assertEquals(0L, budget.getReservedBytes());
        }
    }

    @Test
    public void shouldCancelQueuedDecodedFrameExactlyOnce() throws Exception {
        SocketPacketMemoryBudget budget = new SocketPacketMemoryBudget(32);
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        assertTrue(budget.tryReserve(5));
        try (DecodeHarness harness = new DecodeHarness(16, budget, queuedTask::set)) {
            harness.write(HexaConversionUtil.mergeBytes(
                    frameMetadata((byte) 1, "A", 12), new byte[12]));

            assertTrue(harness.decode());
            assertEquals(17L, budget.getReservedBytes());
            assertNotNull(queuedTask.get());

            assertTrue(SocketDecode.cancelQueuedDispatch(queuedTask.get()));
            assertFalse(SocketDecode.cancelQueuedDispatch(queuedTask.get()));
            queuedTask.get().run();
            assertEquals(5L, budget.getReservedBytes());
        } finally {
            budget.release(5);
        }
        assertEquals(0L, budget.getReservedBytes());
    }

    @Test
    public void shouldNotCancelDecodedFrameAfterDispatchStarts() throws Exception {
        SocketPacketMemoryBudget budget = new SocketPacketMemoryBudget(16);
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        try (DecodeHarness harness = new DecodeHarness(16, budget, queuedTask::set)) {
            harness.addPendingResponse(1);
            harness.write(HexaConversionUtil.mergeBytes(
                    frameMetadata(MsgPacketStatus.RESPONSE_SUCCESS, (byte) 1, "A", 12, ContentType.BYTE),
                    new byte[12]));

            assertTrue(harness.decode());
            queuedTask.get().run();
            assertEquals(12L, budget.getReservedBytes());

            assertFalse(SocketDecode.cancelQueuedDispatch(queuedTask.get()));
            assertEquals(12L, budget.getReservedBytes());
            try (ResponseLease lease = harness.readResponseLease(1)) {
                assertNotNull(lease);
            }
            assertEquals(0L, budget.getReservedBytes());
        }
    }

    private static ProtocolException expectProtocolException(DecodeHarness harness) throws Exception {
        try {
            for (int i = 0; i < 4; i++) {
                harness.decode();
            }
            fail("Expected a protocol exception");
            return null;
        } catch (ProtocolException e) {
            return e;
        }
    }

    private static byte[] frameHeader(byte methodLength) {
        return HexaConversionUtil.mergeBytes(
                new byte[]{PackageVersion.V1.getVersion(), MsgPacketStatus.SEND_REQUEST.getType()},
                HexaConversionUtil.intToByteArray(1),
                new byte[]{methodLength});
    }

    private static byte[] frameMetadata(byte methodLength, String method, int dataLength) {
        return frameMetadata(methodLength, method, dataLength, ContentType.BYTE);
    }

    private static byte[] frameMetadata(byte methodLength, String method, int dataLength, ContentType contentType) {
        return frameMetadata(MsgPacketStatus.SEND_REQUEST, methodLength, method, dataLength, contentType);
    }

    private static byte[] frameMetadata(MsgPacketStatus status, byte methodLength, String method, int dataLength,
                                        ContentType contentType) {
        return HexaConversionUtil.mergeBytes(
                new byte[]{PackageVersion.V1.getVersion(), status.getType()},
                HexaConversionUtil.intToByteArray(1),
                new byte[]{methodLength},
                method.getBytes(),
                HexaConversionUtil.intToByteArray(dataLength),
                new byte[]{contentType.getType()});
    }

    private static class DecodeHarness implements AutoCloseable {

        private final ServerSocketChannel server;
        private final SocketChannel sender;
        private final SocketChannel receiver;
        private final Selector selector;
        private final SocketDecode decoder;
        private final IOSession session;
        private final AtomicInteger dispatchCount = new AtomicInteger();

        private DecodeHarness(int maxDataLength) throws Exception {
            this(maxDataLength, SocketPacketMemoryBudget.unlimited());
        }

        private DecodeHarness(int maxDataLength, SocketPacketMemoryBudget memoryBudget) throws Exception {
            this(maxDataLength, memoryBudget, null);
        }

        private DecodeHarness(int maxDataLength,
                              SocketPacketMemoryBudget memoryBudget,
                              Executor executor) throws Exception {
            server = ServerSocketChannel.open();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            sender = SocketChannel.open(server.getLocalAddress());
            receiver = server.accept();
            receiver.configureBlocking(false);
            selector = Selector.open();
            Executor dispatchExecutor = executor == null ? command -> {
                dispatchCount.incrementAndGet();
                command.run();
            } : executor;
            decoder = new SocketDecode(dispatchExecutor, maxDataLength, memoryBudget);
            session = new IOSession(receiver, selector, new SocketCodec(new SocketEncode(), decoder),
                    new ClientActionHandler());
        }

        private void write(byte[] bytes) throws Exception {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                sender.write(buffer);
            }
        }

        private boolean decode() throws Exception {
            return decoder.doDecode(session);
        }

        private void addPendingResponse(int msgId) {
            long now = System.currentTimeMillis();
            session.getPipeMap().put(msgId, new PipeInfo(null, null, null, now, now + 1000));
        }

        private ResponseLease readResponseLease(int msgId) {
            return session.getResponseLeaseByMsgId(msgId, Duration.ofSeconds(1));
        }

        private void closeSession() {
            session.close();
        }

        private void closeDecoder() {
            decoder.close();
        }

        private int dispatchCount() {
            return dispatchCount.get();
        }

        private MsgPacket pendingResponse(int msgId) {
            PipeInfo pipeInfo = session.getPipeMap().get(msgId);
            return pipeInfo == null ? null : pipeInfo.getResponseMsgPacket();
        }

        @Override
        public void close() throws Exception {
            decoder.close();
            session.close();
            sender.close();
            server.close();
            selector.close();
        }
    }
}
