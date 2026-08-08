package com.zrlog.plugin.data.codec;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.RunConstants;
import com.zrlog.plugin.common.HexaConversionUtil;
import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugin.type.RunType;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

public class SocketEncode {

    private static final Logger LOGGER = LoggerUtil.getLogger(SocketEncode.class);
    private static final long DEFAULT_WRITE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long INITIAL_ZERO_WRITE_BACKOFF_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long MAX_ZERO_WRITE_BACKOFF_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    static final int FILE_TRANSFER_BUFFER_BYTES = 64 * 1024;

    private final ReentrantLock reentrantLock = new ReentrantLock();
    private final LongSupplier nanoTime;
    private final LongConsumer waitNanos;
    private final long writeTimeoutNanos;

    public SocketEncode() {
        this(System::nanoTime, LockSupport::parkNanos, DEFAULT_WRITE_TIMEOUT_NANOS);
    }

    SocketEncode(LongSupplier nanoTime, LongConsumer waitNanos, long writeTimeoutNanos) {
        if (writeTimeoutNanos <= 0) {
            throw new IllegalArgumentException("writeTimeoutNanos must be greater than zero");
        }
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.waitNanos = Objects.requireNonNull(waitNanos, "waitNanos");
        this.writeTimeoutNanos = writeTimeoutNanos;
    }

    public void doEncode(IOSession session, MsgPacket msgPacket) throws Exception {
        reentrantLock.lock();
        try {
            if (session.isClosed()) {
                throw new ClosedChannelException();
            }
            SocketChannel channel = (SocketChannel) session.getSystemAttr().get("_channel");
            Selector selector = (Selector) session.getSystemAttr().get("_selector");
            if (!channel.isOpen()) {
                throw new ClosedChannelException();
            }
            if (!selector.isOpen()) {
                throw new ClosedSelectorException();
            }
            FilePacketPayload filePayload = msgPacket.getFilePayload();
            if (msgPacket.getContentType() == ContentType.FILE) {
                if (filePayload == null) {
                    throw new ProtocolException("FILE socket packet has no file payload");
                }
                writeFilePacket(channel, msgPacket, filePayload);
            } else {
                ByteBuffer[] encodedPacket = encodeBuffers(msgPacket);
                long writeStartedAt = nanoTime.getAsLong();
                for (ByteBuffer buffer : encodedPacket) {
                    writeFully(channel, buffer, writeStartedAt);
                }
            }
            session.getSendMsgCounter().incrementAndGet();
            if (RunConstants.runType == RunType.DEV) {
                LOGGER.info(session.logPrefix("send >>> " + session.getSendMsgCounter().get() + " " + msgPacket));
            }
            channel.register(selector, SelectionKey.OP_READ);
        } finally {
            reentrantLock.unlock();
        }
    }

    static ByteBuffer[] encodeBuffers(MsgPacket msgPacket) throws IOException {
        if (msgPacket.getContentType() == ContentType.FILE) {
            throw new ProtocolException("FILE packets must use streaming socket encoding");
        }
        byte[] methodBytes = msgPacket.getMethodStr().getBytes();
        SocketPacketLimits.validateMethodLength(methodBytes.length);
        int dataLength = msgPacket.getDataLength();
        SocketPacketLimits.validateDataLength(dataLength,
                SocketPacketLimits.configuredMaxDataLengthBytes(), msgPacket.getContentType());
        if (msgPacket.getData() == null || dataLength > msgPacket.getData().capacity()) {
            throw new ProtocolException("Socket packet data length " + dataLength
                    + " exceeds the payload buffer capacity");
        }
        ByteBuffer payload = msgPacket.getData().asReadOnlyBuffer();
        payload.position(0);
        payload.limit(dataLength);
        ByteBuffer header = encodeHeader(msgPacket, methodBytes, dataLength);
        return new ByteBuffer[]{header, payload};
    }

    private void writeFilePacket(SocketChannel channel,
                                 MsgPacket msgPacket,
                                 FilePacketPayload filePayload) throws IOException {
        int dataLength = filePayload.encodedLength();
        if (dataLength != msgPacket.getDataLength()) {
            throw new ProtocolException("FILE socket packet data length changed: expected "
                    + msgPacket.getDataLength() + ", actual " + dataLength);
        }
        SocketPacketLimits.validateDataLength(dataLength,
                SocketPacketLimits.configuredMaxFileDataLengthBytes(), ContentType.FILE);
        byte[] methodBytes = msgPacket.getMethodStr().getBytes();
        SocketPacketLimits.validateMethodLength(methodBytes.length);
        filePayload.validateSourceLength();
        filePayload.validateSourceChecksum();
        try (FileChannel fileChannel = FileChannel.open(filePayload.getFile().toPath(), StandardOpenOption.READ)) {
            if (fileChannel.size() != filePayload.getFileLength()) {
                throw new IOException("FILE payload length changed before send: expected "
                        + filePayload.getFileLength() + ", actual " + fileChannel.size());
            }
            writeFully(channel, encodeHeader(msgPacket, methodBytes, dataLength), nanoTime.getAsLong());
            writeFully(channel, filePayload.metadataBuffer(), nanoTime.getAsLong());
            ByteBuffer transferBuffer = ByteBuffer.allocate(FILE_TRANSFER_BUFFER_BYTES);
            long remaining = filePayload.getFileLength();
            while (remaining > 0L) {
                transferBuffer.clear();
                transferBuffer.limit((int) Math.min(transferBuffer.capacity(), remaining));
                int read = fileChannel.read(transferBuffer);
                if (read < 0) {
                    throw new EOFException("FILE payload ended before its declared length");
                }
                if (read == 0) {
                    continue;
                }
                remaining -= read;
                transferBuffer.flip();
                // Large files use a progress deadline per bounded chunk instead of a whole-frame deadline.
                writeFully(channel, transferBuffer, nanoTime.getAsLong());
            }
            if (fileChannel.position() != filePayload.getFileLength()
                    || fileChannel.size() != filePayload.getFileLength()) {
                throw new IOException("FILE payload length changed while sending");
            }
        }
    }

    private static ByteBuffer encodeHeader(MsgPacket msgPacket, byte[] methodBytes, int dataLength) {
        ByteBuffer header = ByteBuffer.allocate(7 + methodBytes.length + 4 + 1);
        header.put(msgPacket.getdStart());
        header.put(msgPacket.getStatus().getType());
        header.put(HexaConversionUtil.intToByteArray(msgPacket.getMsgId()));
        header.put((byte) methodBytes.length);
        header.put(methodBytes);
        header.put(HexaConversionUtil.intToByteArray(dataLength));
        header.put(msgPacket.getContentType().getType());
        header.flip();
        return header;
    }

    void writeFully(WritableByteChannel channel, ByteBuffer buffer) throws IOException {
        writeFully(channel, buffer, nanoTime.getAsLong());
    }

    private void writeFully(WritableByteChannel channel, ByteBuffer buffer, long writeStartedAt) throws IOException {
        long backoffNanos = INITIAL_ZERO_WRITE_BACKOFF_NANOS;
        while (buffer.hasRemaining()) {
            long elapsedNanos = Math.max(0L, nanoTime.getAsLong() - writeStartedAt);
            if (elapsedNanos >= writeTimeoutNanos) {
                throw new SocketTimeoutException("Socket write exceeded the "
                        + TimeUnit.NANOSECONDS.toMillis(writeTimeoutNanos) + " ms deadline");
            }
            int len = channel.write(buffer);
            if (len < 0) {
                throw new EOFException();
            }
            if (len > 0) {
                backoffNanos = INITIAL_ZERO_WRITE_BACKOFF_NANOS;
                continue;
            }
            elapsedNanos = Math.max(0L, nanoTime.getAsLong() - writeStartedAt);
            if (elapsedNanos >= writeTimeoutNanos) {
                throw new SocketTimeoutException("Socket write exceeded the "
                        + TimeUnit.NANOSECONDS.toMillis(writeTimeoutNanos) + " ms deadline");
            }
            long remainingNanos = writeTimeoutNanos - elapsedNanos;
            waitNanos.accept(Math.min(backoffNanos, remainingNanos));
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Interrupted while waiting for socket write progress");
            }
            backoffNanos = Math.min(backoffNanos * 2, MAX_ZERO_WRITE_BACKOFF_NANOS);
        }
    }
}
