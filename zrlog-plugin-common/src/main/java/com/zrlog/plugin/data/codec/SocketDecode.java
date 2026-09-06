package com.zrlog.plugin.data.codec;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.RunConstants;
import com.zrlog.plugin.common.HexaConversionUtil;
import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugin.type.ActionType;
import com.zrlog.plugin.type.RunType;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class SocketDecode {

    public static final class SocketSessionClosedException extends IOException {

        public SocketSessionClosedException() {
            super("connect closed");
        }
    }

    private static final Logger LOGGER = LoggerUtil.getLogger(SocketDecode.class);
    private static final Gson GSON = new Gson();
    private static final SocketPacketFileBudget GLOBAL_TEMP_FILE_BUDGET = new SocketPacketFileBudget(
            SocketPacketLimits.configuredMaxGlobalTempFileBytes());

    private MsgPacket packet;
    private ByteBuffer header = ByteBuffer.allocate(7);
    private ByteBuffer methodAndLengthAndContentType;

    private final Executor messageHandlerExecutor;
    private final Executor controlMessageHandlerExecutor;
    private final int maxDataLength;
    private final int maxFileDataLength;
    private final SocketPacketMemoryBudget memoryBudget;
    private final SocketPacketFileBudget sessionTempFileBudget;
    private final SocketPacketFileBudget globalTempFileBudget;
    private final Object reservationLock = new Object();
    private int pendingReservedBytes;
    private FileBudgetReservation pendingFileReservation;
    private FileReceiveState fileReceiveState;
    private boolean payloadInitialized;
    private boolean closed;

    public SocketDecode(Executor messageHandlerExecutor) {
        this(messageHandlerExecutor, messageHandlerExecutor, SocketPacketLimits.configuredMaxDataLengthBytes(),
                SocketPacketLimits.configuredMaxFileDataLengthBytes(), SocketPacketMemoryBudget.unlimited(),
                new SocketPacketFileBudget(SocketPacketLimits.configuredMaxSessionTempFileBytes()),
                GLOBAL_TEMP_FILE_BUDGET);
    }

    public SocketDecode(Executor messageHandlerExecutor, SocketPacketMemoryBudget memoryBudget) {
        this(messageHandlerExecutor, messageHandlerExecutor, SocketPacketLimits.configuredMaxDataLengthBytes(),
                SocketPacketLimits.configuredMaxFileDataLengthBytes(), memoryBudget,
                new SocketPacketFileBudget(SocketPacketLimits.configuredMaxSessionTempFileBytes()),
                GLOBAL_TEMP_FILE_BUDGET);
    }

    public SocketDecode(Executor messageHandlerExecutor, Executor controlMessageHandlerExecutor,
                        SocketPacketMemoryBudget memoryBudget) {
        this(messageHandlerExecutor, controlMessageHandlerExecutor,
                SocketPacketLimits.configuredMaxDataLengthBytes(),
                SocketPacketLimits.configuredMaxFileDataLengthBytes(), memoryBudget,
                new SocketPacketFileBudget(SocketPacketLimits.configuredMaxSessionTempFileBytes()),
                GLOBAL_TEMP_FILE_BUDGET);
    }

    SocketDecode(Executor messageHandlerExecutor, int maxDataLength) {
        this(messageHandlerExecutor, messageHandlerExecutor, maxDataLength, maxDataLength,
                SocketPacketMemoryBudget.unlimited(), SocketPacketFileBudget.unlimited(),
                SocketPacketFileBudget.unlimited());
    }

    SocketDecode(Executor messageHandlerExecutor, int maxDataLength, SocketPacketMemoryBudget memoryBudget) {
        this(messageHandlerExecutor, messageHandlerExecutor, maxDataLength, maxDataLength, memoryBudget,
                SocketPacketFileBudget.unlimited(), SocketPacketFileBudget.unlimited());
    }

    SocketDecode(Executor messageHandlerExecutor, Executor controlMessageHandlerExecutor,
                 int maxDataLength, SocketPacketMemoryBudget memoryBudget) {
        this(messageHandlerExecutor, controlMessageHandlerExecutor, maxDataLength, maxDataLength, memoryBudget,
                SocketPacketFileBudget.unlimited(), SocketPacketFileBudget.unlimited());
    }

    SocketDecode(Executor messageHandlerExecutor,
                 Executor controlMessageHandlerExecutor,
                 int maxDataLength,
                 int maxFileDataLength,
                 SocketPacketMemoryBudget memoryBudget,
                 SocketPacketFileBudget sessionTempFileBudget,
                 SocketPacketFileBudget globalTempFileBudget) {
        if (maxDataLength <= 0) {
            throw new IllegalArgumentException("maxDataLength must be greater than zero");
        }
        if (maxFileDataLength <= 0) {
            throw new IllegalArgumentException("maxFileDataLength must be greater than zero");
        }
        this.messageHandlerExecutor = Objects.requireNonNull(messageHandlerExecutor, "messageHandlerExecutor");
        this.controlMessageHandlerExecutor = Objects.requireNonNull(controlMessageHandlerExecutor,
                "controlMessageHandlerExecutor");
        this.maxDataLength = maxDataLength;
        this.maxFileDataLength = maxFileDataLength;
        this.memoryBudget = Objects.requireNonNull(memoryBudget, "memoryBudget");
        this.sessionTempFileBudget = Objects.requireNonNull(sessionTempFileBudget, "sessionTempFileBudget");
        this.globalTempFileBudget = Objects.requireNonNull(globalTempFileBudget, "globalTempFileBudget");
        packet = new MsgPacket();
    }

    private void reset() {
        header = ByteBuffer.allocate(7);
        methodAndLengthAndContentType = null;
        packet = new MsgPacket();
        fileReceiveState = null;
        payloadInitialized = false;
    }

    public synchronized boolean doDecode(final IOSession session) throws Exception {
        SocketChannel channel = (SocketChannel) session.getSystemAttr().get("_channel");

        if (!channel.isOpen() || channel.socket().isClosed()) {
            throw new SocketSessionClosedException();
        }
        if (methodAndLengthAndContentType == null) {
            read(channel, header);
            if (header.hasRemaining()) {
                return false;
            }
            parseHeader();
        }
        if (!payloadInitialized) {
            read(channel, methodAndLengthAndContentType);
            if (methodAndLengthAndContentType.hasRemaining()) {
                return false;
            }
            parseMethodAndDataLength();
        }
        if (packet.getContentType() == ContentType.FILE) {
            try {
                if (!fileReceiveState.read(channel)) {
                    return false;
                }
                packet.setFilePayload(fileReceiveState.detachPayload());
                fileReceiveState = null;
            } catch (IOException | RuntimeException | Error e) {
                abortPendingFile();
                releasePendingReservation();
                throw e;
            }
        } else {
            if (packet.getData().hasRemaining()) {
                read(channel, packet.getData());
                if (packet.getData().hasRemaining()) {
                    return false;
                }
            }
        }
        bindPluginLogLabelIfInitConnect(session, packet);
        session.getReceiveMsgCounter().incrementAndGet();
        if (RunConstants.runType == RunType.DEV) {
            LOGGER.info(session.logPrefix("receive <<< " + session.getReceiveMsgCounter().get() + " " + packet));
        }
        MsgPacket cpMsgPacket = deepCopyMsg(packet);
        int reservedBytes = detachPendingReservation();
        FileBudgetReservation fileReservation = detachPendingFileReservation();
        if (cpMsgPacket.getDataLength() > 0 && reservedBytes == 0
                && cpMsgPacket.getContentType() != ContentType.FILE) {
            cpMsgPacket.releaseFilePayload();
            releaseFileReservation(fileReservation);
            reset();
            return true;
        }
        DecodedPacketDispatchTask dispatchTask;
        try {
            dispatchTask = new DecodedPacketDispatchTask(session, cpMsgPacket, memoryBudget, reservedBytes,
                    fileReservation);
        } catch (RuntimeException | Error e) {
            memoryBudget.release(reservedBytes);
            cpMsgPacket.releaseFilePayload();
            releaseFileReservation(fileReservation);
            throw e;
        }
        try {
            executorFor(cpMsgPacket).execute(dispatchTask);
        } catch (RuntimeException | Error e) {
            dispatchTask.cancelIfQueued();
            throw e;
        }
        reset();
        return true;
    }

    private Executor executorFor(MsgPacket msgPacket) {
        return isControlPacket(msgPacket) ? controlMessageHandlerExecutor : messageHandlerExecutor;
    }

    public static boolean cancelQueuedDispatch(Runnable task) {
        if (!(task instanceof DecodedPacketDispatchTask)) {
            return false;
        }
        return ((DecodedPacketDispatchTask) task).cancelIfQueued();
    }

    static boolean isControlPacket(MsgPacket msgPacket) {
        if (msgPacket == null) {
            return false;
        }
        if (ActionType.INIT_CONNECT.name().equals(msgPacket.getMethodStr())) {
            return true;
        }
        return ActionType.HTTP_METHOD.name().equals(msgPacket.getMethodStr())
                && msgPacket.getContentType() == ContentType.BYTE
                && msgPacket.getDataLength() == 0;
    }

    private void read(SocketChannel channel, ByteBuffer buffer) throws IOException {
        int length = channel.read(buffer);
        if (length == -1) {
            throw new SocketSessionClosedException();
        }
    }

    private void parseHeader() throws IOException {
        byte[] data = header.array();
        if (data[0] != PackageVersion.V1.getVersion()) {
            throw new IOException("Unknown protocol version");
        }
        MsgPacketStatus msgPacketStatus = MsgPacketStatus.getMsgPacketStatus(data[1]);
        if (Objects.equals(msgPacketStatus, MsgPacketStatus.UNKNOWN)) {
            throw new IOException("Unknown package status");
        }
        int methodLength = data[6];
        SocketPacketLimits.validateMethodLength(methodLength);
        packet.setStatus(msgPacketStatus);
        packet.setMethodLength(data[6]);
        packet.setMsgId(HexaConversionUtil.byteArrayToInt(HexaConversionUtil.subByts(data, 2, 4)));
        methodAndLengthAndContentType = ByteBuffer.allocate(methodLength + 4 + 1);
    }

    private void parseMethodAndDataLength() throws IOException {
        byte[] data = methodAndLengthAndContentType.array();
        int methodLength = packet.getMethodLength();
        packet.setMethodStr(new String(HexaConversionUtil.subByts(data, 0, methodLength)));
        int dataLength = HexaConversionUtil.byteArrayToInt(HexaConversionUtil.subByts(data, methodLength, 4));
        ContentType contentType = ContentType.getContentType(data[data.length - 1]);
        int contentLimit = contentType == ContentType.FILE ? maxFileDataLength : maxDataLength;
        SocketPacketLimits.validateDataLength(dataLength, contentLimit, contentType);
        packet.setDataLength(dataLength);
        packet.setContentType(contentType);
        int reservedBytes = contentType == ContentType.FILE
                ? Math.min(dataLength, SocketPacketLimits.MAX_FILE_DESCRIPTION_LENGTH_BYTES
                + FilePacketPayload.FIXED_METADATA_LENGTH + SocketEncode.FILE_TRANSFER_BUFFER_BYTES)
                : dataLength;
        reservePending(reservedBytes);
        try {
            if (contentType == ContentType.FILE) {
                fileReceiveState = new FileReceiveState(dataLength);
            } else {
                packet.setData(ByteBuffer.allocate(dataLength));
            }
            payloadInitialized = true;
        } catch (RuntimeException | Error e) {
            releasePendingReservation();
            throw e;
        }
    }

    public synchronized void close() {
        abortPendingFile();
        releasePendingReservation();
    }

    private void reservePending(int bytes) throws ProtocolException {
        synchronized (reservationLock) {
            if (closed) {
                throw new ProtocolException("Socket decoder is closed");
            }
            if (!memoryBudget.tryReserve(bytes)) {
                throw new ProtocolException("Socket packet memory budget exceeded: requested " + bytes
                        + " bytes, available budget " + memoryBudget.getMaxBytes() + " bytes");
            }
            pendingReservedBytes = bytes;
        }
    }

    private int detachPendingReservation() {
        synchronized (reservationLock) {
            int reservedBytes = pendingReservedBytes;
            pendingReservedBytes = 0;
            return reservedBytes;
        }
    }

    private void releasePendingReservation() {
        int reservedBytes;
        synchronized (reservationLock) {
            closed = true;
            reservedBytes = pendingReservedBytes;
            pendingReservedBytes = 0;
        }
        memoryBudget.release(reservedBytes);
    }

    private void reservePendingFile(long bytes) throws ProtocolException {
        synchronized (reservationLock) {
            if (closed) {
                throw new ProtocolException("Socket decoder is closed");
            }
            if (pendingFileReservation != null) {
                throw new ProtocolException("Socket decoder already owns a temporary FILE reservation");
            }
            if (!sessionTempFileBudget.tryReserve(bytes)) {
                throw new ProtocolException("Socket session temporary FILE budget exceeded: requested " + bytes
                        + " bytes, available budget " + sessionTempFileBudget.getMaxBytes() + " bytes");
            }
            if (!globalTempFileBudget.tryReserve(bytes)) {
                sessionTempFileBudget.release(bytes);
                throw new ProtocolException("Global temporary FILE budget exceeded: requested " + bytes
                        + " bytes, available budget " + globalTempFileBudget.getMaxBytes() + " bytes");
            }
            pendingFileReservation = new FileBudgetReservation(
                    sessionTempFileBudget, globalTempFileBudget, bytes);
        }
    }

    private FileBudgetReservation detachPendingFileReservation() {
        synchronized (reservationLock) {
            FileBudgetReservation reservation = pendingFileReservation;
            pendingFileReservation = null;
            return reservation;
        }
    }

    private void abortPendingFile() {
        if (fileReceiveState != null) {
            fileReceiveState.close();
            fileReceiveState = null;
        }
        packet.releaseFilePayload();
        releaseFileReservation(detachPendingFileReservation());
    }

    private static void releaseFileReservation(FileBudgetReservation reservation) {
        if (reservation != null) {
            reservation.run();
        }
    }

    private static final class DecodedPacketDispatchTask implements Runnable {

        private final IOSession session;
        private final MsgPacket packet;
        private final SocketPacketMemoryBudget memoryBudget;
        private final int reservedBytes;
        private final FileBudgetReservation fileReservation;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();

        private DecodedPacketDispatchTask(IOSession session,
                                          MsgPacket packet,
                                          SocketPacketMemoryBudget memoryBudget,
                                          int reservedBytes,
                                          FileBudgetReservation fileReservation) {
            this.session = session;
            this.packet = packet;
            this.memoryBudget = memoryBudget;
            this.reservedBytes = reservedBytes;
            this.fileReservation = fileReservation;
        }

        @Override
        public void run() {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            try {
                session.dispatchIfOpen(packet, this::release);
            } catch (RuntimeException | Error e) {
                release();
                throw e;
            }
        }

        private boolean cancelIfQueued() {
            if (!claimed.compareAndSet(false, true)) {
                return false;
            }
            release();
            return true;
        }

        private void release() {
            if (released.compareAndSet(false, true)) {
                packet.releaseFilePayload();
                releaseFileReservation(fileReservation);
                memoryBudget.release(reservedBytes);
            }
        }
    }

    private static final class FileBudgetReservation implements Runnable {

        private final SocketPacketFileBudget sessionBudget;
        private final SocketPacketFileBudget globalBudget;
        private final long reservedBytes;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private FileBudgetReservation(SocketPacketFileBudget sessionBudget,
                                      SocketPacketFileBudget globalBudget,
                                      long reservedBytes) {
            this.sessionBudget = sessionBudget;
            this.globalBudget = globalBudget;
            this.reservedBytes = reservedBytes;
        }

        @Override
        public void run() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            sessionBudget.release(reservedBytes);
            globalBudget.release(reservedBytes);
        }
    }

    private final class FileReceiveState implements AutoCloseable {

        private final int frameDataLength;
        private final ByteBuffer fileDescLengthBuffer = ByteBuffer.allocate(4);
        private final MessageDigest digest;
        private ByteBuffer metadataBuffer;
        private ByteBuffer fileBuffer;
        private FileChannel output;
        private File temporaryFile;
        private Path temporaryDirectory;
        private FileDesc fileDesc;
        private String expectedMd5;
        private int fileLength = -1;
        private long remainingFileBytes = -1L;
        private FilePacketPayload completedPayload;
        private boolean closedState;

        private FileReceiveState(int frameDataLength) {
            this.frameDataLength = frameDataLength;
            try {
                this.digest = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("MD5 is unavailable", e);
            }
        }

        private boolean read(SocketChannel channel) throws IOException {
            if (closedState) {
                throw new ProtocolException("FILE receiver is closed");
            }
            if (fileDescLengthBuffer.hasRemaining()) {
                SocketDecode.this.read(channel, fileDescLengthBuffer);
                if (fileDescLengthBuffer.hasRemaining()) {
                    return false;
                }
                int fileDescLength = HexaConversionUtil.byteArrayToIntH(fileDescLengthBuffer.array());
                long maxDescriptionForFrame = (long) frameDataLength - FilePacketPayload.FIXED_METADATA_LENGTH;
                if (fileDescLength <= 0
                        || fileDescLength > SocketPacketLimits.MAX_FILE_DESCRIPTION_LENGTH_BYTES
                        || fileDescLength > maxDescriptionForFrame) {
                    throw new ProtocolException("Invalid FILE description length: " + fileDescLength);
                }
                metadataBuffer = ByteBuffer.allocate(fileDescLength + 32 + 4);
            }
            if (metadataBuffer.hasRemaining()) {
                SocketDecode.this.read(channel, metadataBuffer);
                if (metadataBuffer.hasRemaining()) {
                    return false;
                }
                parseMetadata();
            }
            while (remainingFileBytes > 0L) {
                fileBuffer.clear();
                fileBuffer.limit((int) Math.min(fileBuffer.capacity(), remainingFileBytes));
                int read = channel.read(fileBuffer);
                if (read < 0) {
                    throw new EOFException("FILE payload ended before its declared length");
                }
                if (read == 0) {
                    return false;
                }
                remainingFileBytes -= read;
                fileBuffer.flip();
                digest.update(fileBuffer.asReadOnlyBuffer());
                while (fileBuffer.hasRemaining()) {
                    output.write(fileBuffer);
                }
            }
            if (remainingFileBytes > 0L) {
                return false;
            }
            completeFile();
            return true;
        }

        private void parseMetadata() throws IOException {
            byte[] metadata = metadataBuffer.array();
            int fileDescLength = metadata.length - 32 - 4;
            try {
                // The original V1 wire format used the process default charset for this JSON field.
                fileDesc = GSON.fromJson(new String(metadata, 0, fileDescLength, Charset.defaultCharset()),
                        FileDesc.class);
                FilePacketPayload.validateFileName(fileDesc == null ? null : fileDesc.getFileName());
                expectedMd5 = FilePacketPayload.normalizeMd5(
                        new String(metadata, fileDescLength, 32, StandardCharsets.US_ASCII));
            } catch (RuntimeException e) {
                ProtocolException protocolException = new ProtocolException("Invalid FILE metadata: " + e.getMessage());
                protocolException.initCause(e);
                throw protocolException;
            }
            fileLength = HexaConversionUtil.byteArrayToIntH(HexaConversionUtil.subByts(
                    metadata, fileDescLength + 32, 4));
            long expectedFrameLength = (long) FilePacketPayload.FIXED_METADATA_LENGTH
                    + fileDescLength + fileLength;
            if (fileLength < 0 || expectedFrameLength != frameDataLength) {
                throw new ProtocolException("Invalid FILE declared length: " + fileLength
                        + ", frame payload length " + frameDataLength);
            }
            reservePendingFile(fileLength);
            temporaryDirectory = Files.createTempDirectory("zrlog-plugin-file-");
            temporaryFile = temporaryDirectory.resolve(fileDesc.getFileName()).toFile();
            output = FileChannel.open(temporaryFile.toPath(), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            fileBuffer = ByteBuffer.allocate(SocketEncode.FILE_TRANSFER_BUFFER_BYTES);
            remainingFileBytes = fileLength;
        }

        private void completeFile() throws IOException {
            if (completedPayload != null) {
                return;
            }
            closeOutput();
            if (temporaryFile == null || temporaryFile.length() != fileLength) {
                throw new ProtocolException("FILE actual length does not match declared length " + fileLength);
            }
            String actualMd5 = HexaConversionUtil.bytesToHexString(digest.digest()).toLowerCase();
            if (!expectedMd5.equals(actualMd5)) {
                throw new ProtocolException("FILE checksum mismatch");
            }
            completedPayload = FilePacketPayload.received(
                    temporaryFile, temporaryDirectory, fileDesc, expectedMd5, fileLength);
            temporaryFile = null;
            temporaryDirectory = null;
        }

        private FilePacketPayload detachPayload() {
            FilePacketPayload payload = completedPayload;
            completedPayload = null;
            if (payload == null) {
                throw new IllegalStateException("FILE payload is incomplete");
            }
            closedState = true;
            return payload;
        }

        private void closeOutput() throws IOException {
            if (output != null) {
                output.close();
                output = null;
            }
        }

        @Override
        public void close() {
            if (closedState) {
                return;
            }
            closedState = true;
            try {
                closeOutput();
            } catch (IOException ignored) {
            }
            if (completedPayload != null) {
                completedPayload.close();
                completedPayload = null;
            }
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile.toPath());
                } catch (IOException ignored) {
                }
                temporaryFile = null;
            }
            if (temporaryDirectory != null) {
                try {
                    Files.deleteIfExists(temporaryDirectory);
                } catch (IOException ignored) {
                }
                temporaryDirectory = null;
            }
        }
    }

    private void bindPluginLogLabelIfInitConnect(IOSession session, MsgPacket msgPacket) {
        if (session == null || msgPacket == null || !ActionType.INIT_CONNECT.name().equals(msgPacket.getMethodStr())
                || msgPacket.getContentType() != ContentType.JSON) {
            return;
        }
        try {
            Plugin plugin = GSON.fromJson(msgPacket.getDataStr(), Plugin.class);
            if (plugin != null) {
                session.setPluginLogLabel(plugin.getShortName());
            }
        } catch (JsonSyntaxException ignored) {
        }
    }

    private static MsgPacket deepCopyMsg(MsgPacket msgPacket) {
        MsgPacket packet = new MsgPacket();
        packet.setMsgId(msgPacket.getMsgId());
        packet.setStatus(msgPacket.getStatus());
        //
        packet.setContentType(msgPacket.getContentType());
        //data
        packet.setMethodStr(msgPacket.getMethodStr());
        packet.setMethodLength(msgPacket.getMethodLength());
        packet.setDataLength(msgPacket.getDataLength());
        if (msgPacket.getContentType() == ContentType.FILE) {
            packet.setFilePayload(msgPacket.detachFilePayload());
        } else {
            packet.setData(msgPacket.getData());
        }
        return packet;
    }

}
