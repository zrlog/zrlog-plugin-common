package com.zrlog.plugin.data.codec;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.client.ClientActionHandler;
import com.zrlog.plugin.common.HexaConversionUtil;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SocketEncodeTest {

    @Test
    public void shouldKeepExistingWireFormatForStandardPacket() throws Exception {
        MsgPacket packet = new MsgPacket(new byte[]{1, 2, 3, 4}, ContentType.BYTE,
                MsgPacketStatus.SEND_REQUEST, 0x10203040, "SERVICE");
        byte[] expected = HexaConversionUtil.mergeBytes(
                new byte[]{packet.getdStart(), packet.getStatus().getType()},
                HexaConversionUtil.intToByteArray(packet.getMsgId()),
                new byte[]{packet.getMethodLength()},
                packet.getMethodStr().getBytes(),
                HexaConversionUtil.intToByteArray(packet.getDataLength()),
                new byte[]{packet.getContentType().getType()},
                packet.getData().array());

        byte[] encoded = drain(SocketEncode.encodeBuffers(packet));

        assertArrayEquals(expected, encoded);
    }

    @Test
    public void shouldWriteDeclaredPayloadWithoutMutatingOriginalBuffer() throws Exception {
        ByteBuffer payload = ByteBuffer.wrap(new byte[]{9, 1, 2, 3, 8});
        payload.position(payload.limit());
        MsgPacket packet = new MsgPacket();
        packet.setStatus(MsgPacketStatus.SEND_REQUEST);
        packet.setMsgId(7);
        packet.setMethodStr("A");
        packet.setMethodLength((byte) 1);
        packet.setContentType(ContentType.BYTE);
        packet.setDataLength(payload.capacity());
        packet.setData(payload);

        byte[] encoded = drain(SocketEncode.encodeBuffers(packet));

        assertEquals(5, payload.position());
        assertEquals(5, payload.limit());
        int dataLengthOffset = 7 + packet.getMethodStr().getBytes().length;
        assertEquals(5, HexaConversionUtil.byteArrayToInt(
                new byte[]{encoded[dataLengthOffset], encoded[dataLengthOffset + 1],
                        encoded[dataLengthOffset + 2], encoded[dataLengthOffset + 3]}));
        assertArrayEquals(new byte[]{9, 1, 2, 3, 8}, new byte[]{encoded[encoded.length - 5],
                encoded[encoded.length - 4], encoded[encoded.length - 3],
                encoded[encoded.length - 2], encoded[encoded.length - 1]});
    }

    @Test
    public void shouldBackOffAndTimeOutWhenSocketWriteMakesNoProgress() {
        AtomicLong clock = new AtomicLong();
        AtomicLong waitedNanos = new AtomicLong();
        SocketEncode encode = new SocketEncode(clock::get, nanos -> {
            waitedNanos.addAndGet(nanos);
            clock.addAndGet(nanos);
        }, 10L);

        assertThrows(SocketTimeoutException.class,
                () -> encode.writeFully(new ZeroWriteChannel(), ByteBuffer.wrap(new byte[]{1})));
        assertEquals(10L, waitedNanos.get());
    }

    @Test
    public void shouldApplyDeadlineEvenWhenEveryWriteMakesProgress() {
        AtomicLong clock = new AtomicLong();
        SocketEncode encode = new SocketEncode(clock::get, ignored -> {
        }, 10L);
        ByteBuffer buffer = ByteBuffer.wrap(new byte[10]);

        assertThrows(SocketTimeoutException.class,
                () -> encode.writeFully(new ProgressWriteChannel(clock), buffer));
        assertTrue(buffer.hasRemaining());
    }

    @Test
    public void shouldRejectPayloadLargerThanConfiguredFrameLimit() {
        String previousValue = System.getProperty(SocketPacketLimits.MAX_DATA_LENGTH_PROPERTY);
        try {
            System.setProperty(SocketPacketLimits.MAX_DATA_LENGTH_PROPERTY, "1");

            assertThrows(ProtocolException.class, () -> SocketEncode.encodeBuffers(
                    new MsgPacket(new byte[]{1, 2}, ContentType.BYTE,
                            MsgPacketStatus.SEND_REQUEST, 1, "SERVICE")));
        } finally {
            if (previousValue == null) {
                System.clearProperty(SocketPacketLimits.MAX_DATA_LENGTH_PROPERTY);
            } else {
                System.setProperty(SocketPacketLimits.MAX_DATA_LENGTH_PROPERTY, previousValue);
            }
        }
    }

    @Test
    public void shouldPropagateClosedSelector() throws Exception {
        try (SessionHarness harness = new SessionHarness()) {
            harness.selector.close();

            assertThrows(ClosedSelectorException.class,
                    () -> new SocketEncode().doEncode(harness.session, packet()));
        }
    }

    @Test
    public void shouldPropagateClosedChannel() throws Exception {
        try (SessionHarness harness = new SessionHarness()) {
            harness.receiver.close();

            assertThrows(ClosedChannelException.class,
                    () -> new SocketEncode().doEncode(harness.session, packet()));
        }
    }

    @Test
    public void shouldRejectClosedSession() throws Exception {
        try (SessionHarness harness = new SessionHarness()) {
            harness.session.close();

            assertThrows(ClosedChannelException.class,
                    () -> new SocketEncode().doEncode(harness.session, packet()));
        }
    }

    private static MsgPacket packet() {
        return new MsgPacket(new byte[]{1}, ContentType.BYTE, MsgPacketStatus.SEND_REQUEST, 1, "SERVICE");
    }

    private static byte[] drain(ByteBuffer[] buffers) {
        int length = 0;
        for (ByteBuffer buffer : buffers) {
            length += buffer.remaining();
        }
        byte[] bytes = new byte[length];
        ByteBuffer target = ByteBuffer.wrap(bytes);
        for (ByteBuffer buffer : buffers) {
            target.put(buffer);
        }
        return bytes;
    }

    private static class ZeroWriteChannel implements WritableByteChannel {

        private boolean open = true;

        @Override
        public int write(ByteBuffer src) {
            return 0;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    private static class ProgressWriteChannel implements WritableByteChannel {

        private final AtomicLong clock;
        private boolean open = true;

        private ProgressWriteChannel(AtomicLong clock) {
            this.clock = clock;
        }

        @Override
        public int write(ByteBuffer src) {
            src.get();
            clock.addAndGet(2L);
            return 1;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    private static class SessionHarness implements AutoCloseable {

        private final ServerSocketChannel server;
        private final SocketChannel sender;
        private final SocketChannel receiver;
        private final Selector selector;
        private final IOSession session;

        private SessionHarness() throws Exception {
            server = ServerSocketChannel.open();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            sender = SocketChannel.open(server.getLocalAddress());
            receiver = server.accept();
            selector = Selector.open();
            session = new IOSession(receiver, selector,
                    new SocketCodec(new SocketEncode(), new SocketDecode(Runnable::run)),
                    new ClientActionHandler());
        }

        @Override
        public void close() throws Exception {
            session.close();
            sender.close();
            server.close();
            selector.close();
        }
    }
}
