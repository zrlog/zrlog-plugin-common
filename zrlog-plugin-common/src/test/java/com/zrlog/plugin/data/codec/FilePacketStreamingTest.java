package com.zrlog.plugin.data.codec;

import com.google.gson.Gson;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.PipeInfo;
import com.zrlog.plugin.ResponseLease;
import com.zrlog.plugin.client.ClientActionHandler;
import com.zrlog.plugin.common.HexaConversionUtil;
import com.zrlog.plugin.common.SecurityUtils;
import com.zrlog.plugin.data.codec.convert.FileConvertMsgBody;
import com.zrlog.plugin.type.ActionType;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FilePacketStreamingTest {

    @Test
    public void shouldKeepExistingFileWireFormat() throws Exception {
        File file = temporaryFile("wire-compatible", new byte[]{1, 2, 3, 4, 5});
        try (EncodeHarness harness = new EncodeHarness(new SocketEncode())) {
            MsgPacket packet = new MsgPacket(file, ContentType.FILE, MsgPacketStatus.RESPONSE_SUCCESS, 17,
                    ActionType.HTTP_ATTACHMENT_FILE.name());
            byte[] legacyPayload = new FileConvertMsgBody().toByteBuffer(file).array();
            byte[] expected = HexaConversionUtil.mergeBytes(
                    new byte[]{packet.getdStart(), packet.getStatus().getType()},
                    HexaConversionUtil.intToByteArray(packet.getMsgId()),
                    new byte[]{packet.getMethodLength()},
                    packet.getMethodStr().getBytes(),
                    HexaConversionUtil.intToByteArray(legacyPayload.length),
                    new byte[]{ContentType.FILE.getType()},
                    legacyPayload);

            harness.encode.doEncode(harness.session, packet);

            assertArrayEquals(expected, harness.read(expected.length));
            assertNull(packet.getData());
            assertNotNull(packet.getFilePayload());
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    @Test
    public void shouldKeepNormalFramesAtThirtyTwoMiBWhileAllowingLargerFiles() throws Exception {
        int largeFileLength = SocketPacketLimits.DEFAULT_MAX_DATA_LENGTH_BYTES + 1024;
        assertThrows(ProtocolException.class, () -> SocketPacketLimits.validateDataLength(
                largeFileLength, SocketPacketLimits.DEFAULT_MAX_DATA_LENGTH_BYTES, ContentType.BYTE));
        SocketPacketLimits.validateDataLength(
                largeFileLength, SocketPacketLimits.DEFAULT_MAX_FILE_DATA_LENGTH_BYTES, ContentType.FILE);

        File sparseFile = Files.createTempFile("zrlog-large-file-packet-", ".bin").toFile();
        try {
            try (RandomAccessFile randomAccessFile = new RandomAccessFile(sparseFile, "rw")) {
                randomAccessFile.setLength(largeFileLength);
            }
            MsgPacket packet = new MsgPacket(sparseFile, ContentType.FILE, MsgPacketStatus.RESPONSE_SUCCESS, 1,
                    ActionType.HTTP_ATTACHMENT_FILE.name());

            assertNull(packet.getData());
            assertEquals(largeFileLength, packet.getFilePayload().getFileLength());
            assertTrue(packet.getDataLength() > SocketPacketLimits.DEFAULT_MAX_DATA_LENGTH_BYTES);
        } finally {
            Files.deleteIfExists(sparseFile.toPath());
        }
    }

    @Test
    public void shouldSpoolFileAndReleaseItWithResponseLease() throws Exception {
        byte[] content = new byte[1024 * 1024];
        content[0] = 1;
        content[content.length - 1] = 2;
        int boundedFileMemory = SocketPacketLimits.MAX_FILE_DESCRIPTION_LENGTH_BYTES
                + FilePacketPayload.FIXED_METADATA_LENGTH + SocketEncode.FILE_TRANSFER_BUFFER_BYTES;
        SocketPacketMemoryBudget memoryBudget = new SocketPacketMemoryBudget(boundedFileMemory);
        SocketPacketFileBudget sessionFileBudget = new SocketPacketFileBudget(2L * 1024 * 1024);
        SocketPacketFileBudget globalFileBudget = new SocketPacketFileBudget(2L * 1024 * 1024);
        try (DecodeHarness harness = new DecodeHarness(16, 2 * 1024 * 1024, memoryBudget,
                sessionFileBudget, globalFileBudget, Runnable::run)) {
            harness.addPendingResponse(1);
            harness.write(fileFrame(1, "backup.zip", content, null));

            assertTrue(harness.decodeFrame());
            assertTrue(memoryBudget.getReservedBytes() < content.length / 4L);
            assertEquals(content.length, sessionFileBudget.getReservedBytes());
            assertEquals(content.length, globalFileBudget.getReservedBytes());

            File receivedFile;
            File receivedDirectory;
            try (ResponseLease lease = harness.readResponseLease(1)) {
                assertNotNull(lease);
                MsgPacket packet = lease.getPacket();
                assertNull(packet.getData());
                assertNotNull(packet.getFilePayload());
                receivedFile = packet.getFilePayload().getFile();
                receivedDirectory = receivedFile.getParentFile();
                assertTrue(receivedFile.isFile());
                assertEquals("backup.zip", receivedFile.getName());
                assertArrayEquals(content, Files.readAllBytes(receivedFile.toPath()));
            }
            assertFalse(receivedFile.exists());
            assertFalse(receivedDirectory.exists());
            assertEquals(0L, memoryBudget.getReservedBytes());
            assertEquals(0L, sessionFileBudget.getReservedBytes());
            assertEquals(0L, globalFileBudget.getReservedBytes());
        }
    }

    @Test(timeout = 1000)
    public void shouldYieldWhenNonBlockingFileReadHasNoMoreData() throws Exception {
        byte[] content = new byte[128 * 1024];
        content[0] = 1;
        content[content.length - 1] = 2;
        byte[] frame = fileFrame(1, "partial-backup.zip", content, null);
        int withheldBytes = 17;
        SocketPacketMemoryBudget memoryBudget = new SocketPacketMemoryBudget(256 * 1024);
        SocketPacketFileBudget sessionFileBudget = new SocketPacketFileBudget(256 * 1024);
        SocketPacketFileBudget globalFileBudget = new SocketPacketFileBudget(256 * 1024);
        try (DecodeHarness harness = new DecodeHarness(16, 256 * 1024, memoryBudget,
                sessionFileBudget, globalFileBudget, Runnable::run)) {
            harness.addPendingResponse(1);
            harness.write(Arrays.copyOf(frame, frame.length - withheldBytes));

            assertFalse(harness.decodeFrame());
            assertEquals(content.length, sessionFileBudget.getReservedBytes());
            assertFalse(harness.decoder.doDecode(harness.session));

            harness.write(Arrays.copyOfRange(frame, frame.length - withheldBytes, frame.length));
            assertTrue(harness.decodeFrame());
            try (ResponseLease lease = harness.readResponseLease(1)) {
                assertNotNull(lease);
                assertArrayEquals(content,
                        Files.readAllBytes(lease.getPacket().getFilePayload().getFile().toPath()));
            }
            assertEquals(0L, memoryBudget.getReservedBytes());
            assertEquals(0L, sessionFileBudget.getReservedBytes());
            assertEquals(0L, globalFileBudget.getReservedBytes());
        }
    }

    @Test
    public void shouldReleasePartialFileWhenDecoderCloses() throws Exception {
        byte[] content = new byte[64 * 1024];
        byte[] frame = fileFrame(1, "partial-close.zip", content, null);
        SocketPacketMemoryBudget memoryBudget = new SocketPacketMemoryBudget(128 * 1024);
        SocketPacketFileBudget sessionFileBudget = new SocketPacketFileBudget(128 * 1024);
        SocketPacketFileBudget globalFileBudget = new SocketPacketFileBudget(128 * 1024);
        try (DecodeHarness harness = new DecodeHarness(16, 128 * 1024, memoryBudget,
                sessionFileBudget, globalFileBudget, Runnable::run)) {
            harness.write(Arrays.copyOf(frame, frame.length - 1));

            assertFalse(harness.decodeFrame());
            assertTrue(memoryBudget.getReservedBytes() > 0L);
            assertEquals(content.length, sessionFileBudget.getReservedBytes());
            assertEquals(content.length, globalFileBudget.getReservedBytes());

            harness.decoder.close();

            assertEquals(0L, memoryBudget.getReservedBytes());
            assertEquals(0L, sessionFileBudget.getReservedBytes());
            assertEquals(0L, globalFileBudget.getReservedBytes());
        }
    }

    @Test
    public void shouldReleaseCompletedFileWhenExecutorRejectsDispatch() throws Exception {
        byte[] content = new byte[64];
        SocketPacketMemoryBudget memoryBudget = new SocketPacketMemoryBudget(512);
        SocketPacketFileBudget sessionFileBudget = new SocketPacketFileBudget(128);
        SocketPacketFileBudget globalFileBudget = new SocketPacketFileBudget(128);
        Executor rejectingExecutor = task -> {
            throw new RejectedExecutionException("test rejection");
        };
        try (DecodeHarness harness = new DecodeHarness(16, 1024, memoryBudget,
                sessionFileBudget, globalFileBudget, rejectingExecutor)) {
            harness.write(fileFrame(1, "rejected.zip", content, null));

            assertThrows(RejectedExecutionException.class, harness::decodeFrame);

            assertEquals(0L, memoryBudget.getReservedBytes());
            assertEquals(0L, sessionFileBudget.getReservedBytes());
            assertEquals(0L, globalFileBudget.getReservedBytes());
        }
    }

    @Test
    public void shouldPreserveAndCleanUpZeroLengthFileName() throws Exception {
        SocketPacketMemoryBudget memoryBudget = new SocketPacketMemoryBudget(256);
        SocketPacketFileBudget sessionFileBudget = new SocketPacketFileBudget(1);
        SocketPacketFileBudget globalFileBudget = new SocketPacketFileBudget(1);
        try (DecodeHarness harness = new DecodeHarness(16, 1024, memoryBudget,
                sessionFileBudget, globalFileBudget, Runnable::run)) {
            harness.addPendingResponse(1);
            harness.write(fileFrame(1, "empty-backup.zip", new byte[0], null));
            assertTrue(harness.decodeFrame());

            File receivedFile;
            File receivedDirectory;
            try (ResponseLease lease = harness.readResponseLease(1)) {
                assertNotNull(lease);
                receivedFile = lease.getPacket().getFilePayload().getFile();
                receivedDirectory = receivedFile.getParentFile();
                assertEquals("empty-backup.zip", receivedFile.getName());
                assertEquals(0L, receivedFile.length());
            }
            assertFalse(receivedFile.exists());
            assertFalse(receivedDirectory.exists());
            assertEquals(0L, memoryBudget.getReservedBytes());
            assertEquals(0L, sessionFileBudget.getReservedBytes());
            assertEquals(0L, globalFileBudget.getReservedBytes());
        }
    }

    @Test
    public void shouldDeleteQueuedFileWhenDispatchIsCancelled() throws Exception {
        byte[] content = new byte[64];
        SocketPacketMemoryBudget memoryBudget = new SocketPacketMemoryBudget(512);
        SocketPacketFileBudget sessionFileBudget = new SocketPacketFileBudget(128);
        SocketPacketFileBudget globalFileBudget = new SocketPacketFileBudget(128);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        try (DecodeHarness harness = new DecodeHarness(16, 1024, memoryBudget,
                sessionFileBudget, globalFileBudget, queued::set)) {
            harness.write(fileFrame(1, "queued.zip", content, null));

            assertTrue(harness.decodeFrame());
            assertNotNull(queued.get());
            assertEquals(content.length, sessionFileBudget.getReservedBytes());

            assertTrue(SocketDecode.cancelQueuedDispatch(queued.get()));
            assertFalse(SocketDecode.cancelQueuedDispatch(queued.get()));
            assertEquals(0L, memoryBudget.getReservedBytes());
            assertEquals(0L, sessionFileBudget.getReservedBytes());
            assertEquals(0L, globalFileBudget.getReservedBytes());
        }
    }

    @Test
    public void shouldRejectInvalidFileMetadataAndReleaseBudgets() throws Exception {
        byte[] content = new byte[32];
        SocketPacketMemoryBudget memoryBudget = new SocketPacketMemoryBudget(512);
        SocketPacketFileBudget sessionFileBudget = new SocketPacketFileBudget(128);
        SocketPacketFileBudget globalFileBudget = new SocketPacketFileBudget(128);
        try (DecodeHarness harness = new DecodeHarness(16, 1024, memoryBudget,
                sessionFileBudget, globalFileBudget, Runnable::run)) {
            harness.write(fileFrame(1, "../backup.zip", content, null));

            ProtocolException exception = expectProtocolException(harness);

            assertTrue(exception.getMessage().contains("Invalid FILE metadata"));
            assertEquals(0L, memoryBudget.getReservedBytes());
            assertEquals(0L, sessionFileBudget.getReservedBytes());
            assertEquals(0L, globalFileBudget.getReservedBytes());
        }
    }

    @Test
    public void shouldRejectChecksumMismatchAndReleaseTemporaryFileBudget() throws Exception {
        byte[] content = new byte[32];
        SocketPacketMemoryBudget memoryBudget = new SocketPacketMemoryBudget(512);
        SocketPacketFileBudget sessionFileBudget = new SocketPacketFileBudget(128);
        SocketPacketFileBudget globalFileBudget = new SocketPacketFileBudget(128);
        try (DecodeHarness harness = new DecodeHarness(16, 1024, memoryBudget,
                sessionFileBudget, globalFileBudget, Runnable::run)) {
            harness.write(fileFrame(1, "backup.zip", content, "00000000000000000000000000000000"));

            ProtocolException exception = expectProtocolException(harness);

            assertTrue(exception.getMessage().contains("checksum mismatch"));
            assertEquals(0L, memoryBudget.getReservedBytes());
            assertEquals(0L, sessionFileBudget.getReservedBytes());
            assertEquals(0L, globalFileBudget.getReservedBytes());
        }
    }

    @Test
    public void shouldRejectDeclaredFileLengthThatDoesNotMatchFrame() throws Exception {
        byte[] content = new byte[32];
        SocketPacketMemoryBudget memoryBudget = new SocketPacketMemoryBudget(512);
        SocketPacketFileBudget sessionFileBudget = new SocketPacketFileBudget(128);
        SocketPacketFileBudget globalFileBudget = new SocketPacketFileBudget(128);
        try (DecodeHarness harness = new DecodeHarness(16, 1024, memoryBudget,
                sessionFileBudget, globalFileBudget, Runnable::run)) {
            harness.write(fileFrame(1, "backup.zip", content, null, content.length - 1));

            ProtocolException exception = expectProtocolException(harness);

            assertTrue(exception.getMessage().contains("declared length"));
            assertEquals(0L, memoryBudget.getReservedBytes());
            assertEquals(0L, sessionFileBudget.getReservedBytes());
            assertEquals(0L, globalFileBudget.getReservedBytes());
        }
    }

    @Test
    public void shouldRejectFileWhenSessionTemporaryBudgetIsFull() throws Exception {
        byte[] content = new byte[32];
        SocketPacketMemoryBudget memoryBudget = new SocketPacketMemoryBudget(512);
        SocketPacketFileBudget sessionFileBudget = new SocketPacketFileBudget(16);
        SocketPacketFileBudget globalFileBudget = new SocketPacketFileBudget(128);
        try (DecodeHarness harness = new DecodeHarness(16, 1024, memoryBudget,
                sessionFileBudget, globalFileBudget, Runnable::run)) {
            harness.write(fileFrame(1, "backup.zip", content, null));

            ProtocolException exception = expectProtocolException(harness);

            assertTrue(exception.getMessage().contains("session temporary FILE budget exceeded"));
            assertEquals(0L, memoryBudget.getReservedBytes());
            assertEquals(0L, sessionFileBudget.getReservedBytes());
            assertEquals(0L, globalFileBudget.getReservedBytes());
        }
    }

    @Test
    public void shouldRejectFileWhenGlobalTemporaryBudgetIsFull() throws Exception {
        byte[] content = new byte[32];
        SocketPacketMemoryBudget memoryBudget = new SocketPacketMemoryBudget(512);
        SocketPacketFileBudget sessionFileBudget = new SocketPacketFileBudget(128);
        SocketPacketFileBudget globalFileBudget = new SocketPacketFileBudget(16);
        try (DecodeHarness harness = new DecodeHarness(16, 1024, memoryBudget,
                sessionFileBudget, globalFileBudget, Runnable::run)) {
            harness.write(fileFrame(1, "backup.zip", content, null));

            ProtocolException exception = expectProtocolException(harness);

            assertTrue(exception.getMessage().contains("Global temporary FILE budget exceeded"));
            assertEquals(0L, memoryBudget.getReservedBytes());
            assertEquals(0L, sessionFileBudget.getReservedBytes());
            assertEquals(0L, globalFileBudget.getReservedBytes());
        }
    }

    @Test
    public void sendFileMsgShouldExposeEncodeFailure() throws Exception {
        File file = temporaryFile("send-failure", new byte[]{1});
        SocketEncode failingEncode = new SocketEncode() {
            @Override
            public void doEncode(IOSession session, MsgPacket msgPacket) throws Exception {
                throw new IOException("test FILE send failure");
            }
        };
        try (EncodeHarness harness = new EncodeHarness(failingEncode)) {
            assertThrows(IllegalStateException.class,
                    () -> harness.session.sendFileMsg(file, 9, MsgPacketStatus.RESPONSE_SUCCESS));
            assertTrue(harness.session.isClosed());
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    private static File temporaryFile(String prefix, byte[] bytes) throws IOException {
        File file = Files.createTempFile(prefix, ".bin").toFile();
        Files.write(file.toPath(), bytes);
        return file;
    }

    private static byte[] fileFrame(int msgId, String fileName, byte[] content, String checksum) {
        return fileFrame(msgId, fileName, content, checksum, content.length);
    }

    private static byte[] fileFrame(int msgId,
                                    String fileName,
                                    byte[] content,
                                    String checksum,
                                    int declaredLength) {
        FileDesc fileDesc = new FileDesc();
        fileDesc.setFileName(fileName);
        fileDesc.setFilePath("/ignored/source/path");
        byte[] fileDescBytes = new Gson().toJson(fileDesc).getBytes(StandardCharsets.UTF_8);
        String resolvedChecksum = checksum == null ? SecurityUtils.md5(content) : checksum;
        byte[] payload = HexaConversionUtil.mergeBytes(
                HexaConversionUtil.intToByteArrayH(fileDescBytes.length),
                fileDescBytes,
                resolvedChecksum.getBytes(StandardCharsets.US_ASCII),
                HexaConversionUtil.intToByteArrayH(declaredLength),
                content);
        byte[] method = ActionType.HTTP_ATTACHMENT_FILE.name().getBytes();
        return HexaConversionUtil.mergeBytes(
                new byte[]{PackageVersion.V1.getVersion(), MsgPacketStatus.RESPONSE_SUCCESS.getType()},
                HexaConversionUtil.intToByteArray(msgId),
                new byte[]{(byte) method.length},
                method,
                HexaConversionUtil.intToByteArray(payload.length),
                new byte[]{ContentType.FILE.getType()},
                payload);
    }

    private static ProtocolException expectProtocolException(DecodeHarness harness) throws Exception {
        try {
            harness.decodeFrame();
            fail("Expected ProtocolException");
            return null;
        } catch (ProtocolException e) {
            return e;
        }
    }

    private static final class EncodeHarness implements AutoCloseable {

        private final ServerSocketChannel server;
        private final SocketChannel client;
        private final SocketChannel sessionChannel;
        private final Selector selector;
        private final SocketEncode encode;
        private final IOSession session;

        private EncodeHarness(SocketEncode encode) throws Exception {
            this.encode = encode;
            server = ServerSocketChannel.open();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            client = SocketChannel.open(server.getLocalAddress());
            sessionChannel = server.accept();
            sessionChannel.configureBlocking(false);
            selector = Selector.open();
            session = new IOSession(sessionChannel, selector,
                    new SocketCodec(encode, new SocketDecode(Runnable::run)), new ClientActionHandler());
        }

        private byte[] read(int length) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining()) {
                int read = client.read(buffer);
                if (read < 0) {
                    throw new IOException("connection closed before encoded packet completed");
                }
            }
            return buffer.array();
        }

        @Override
        public void close() throws Exception {
            session.close();
            client.close();
            server.close();
            selector.close();
        }
    }

    private static final class DecodeHarness implements AutoCloseable {

        private final ServerSocketChannel server;
        private final SocketChannel sender;
        private final SocketChannel receiver;
        private final Selector selector;
        private final SocketDecode decoder;
        private final IOSession session;

        private DecodeHarness(int maxDataLength,
                              int maxFileDataLength,
                              SocketPacketMemoryBudget memoryBudget,
                              SocketPacketFileBudget sessionFileBudget,
                              SocketPacketFileBudget globalFileBudget,
                              Executor executor) throws Exception {
            server = ServerSocketChannel.open();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            sender = SocketChannel.open(server.getLocalAddress());
            receiver = server.accept();
            receiver.configureBlocking(false);
            selector = Selector.open();
            decoder = new SocketDecode(executor, executor, maxDataLength, maxFileDataLength,
                    memoryBudget, sessionFileBudget, globalFileBudget);
            session = new IOSession(receiver, selector, new SocketCodec(new SocketEncode(), decoder),
                    new ClientActionHandler());
        }

        private void addPendingResponse(int msgId) {
            long now = System.currentTimeMillis();
            session.getPipeMap().put(msgId, new PipeInfo(null, null, null, now, now + 1000));
        }

        private void write(byte[] bytes) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                sender.write(buffer);
            }
        }

        private boolean decodeFrame() throws Exception {
            for (int i = 0; i < 32; i++) {
                if (decoder.doDecode(session)) {
                    return true;
                }
            }
            return false;
        }

        private ResponseLease readResponseLease(int msgId) {
            return session.getResponseLeaseByMsgId(msgId, Duration.ofSeconds(1));
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
