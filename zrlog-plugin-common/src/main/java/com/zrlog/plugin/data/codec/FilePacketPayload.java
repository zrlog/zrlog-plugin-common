package com.zrlog.plugin.data.codec;

import com.google.gson.Gson;
import com.zrlog.plugin.common.HexaConversionUtil;
import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugin.common.SecurityUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * File content and metadata for a FILE packet. Received temporary files remain valid until the response lease closes.
 */
public final class FilePacketPayload implements AutoCloseable {

    private static final Logger LOGGER = LoggerUtil.getLogger(FilePacketPayload.class);
    private static final Gson GSON = new Gson();
    static final int FIXED_METADATA_LENGTH = 4 + 32 + 4;

    private final File file;
    private final FileDesc fileDesc;
    private final String md5sum;
    private final int fileLength;
    private final byte[] fileDescBytes;
    private final boolean deleteOnClose;
    private final Path ownedDirectory;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private FilePacketPayload(File file,
                              FileDesc fileDesc,
                              String md5sum,
                              int fileLength,
                              byte[] fileDescBytes,
                              boolean deleteOnClose,
                              Path ownedDirectory) {
        this.file = Objects.requireNonNull(file, "file");
        this.fileDesc = Objects.requireNonNull(fileDesc, "fileDesc");
        this.md5sum = Objects.requireNonNull(md5sum, "md5sum");
        this.fileLength = fileLength;
        this.fileDescBytes = Objects.requireNonNull(fileDescBytes, "fileDescBytes");
        this.deleteOnClose = deleteOnClose;
        this.ownedDirectory = ownedDirectory;
    }

    static FilePacketPayload forSend(File file) {
        validateReadableFile(file);
        long length = file.length();
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("FILE payload exceeds the protocol file length limit: " + length);
        }
        FileDesc fileDesc = new FileDesc();
        fileDesc.setFileName(file.getName());
        fileDesc.setFilePath(file.getParent());
        byte[] fileDescBytes = encodeAndValidateFileDesc(fileDesc);
        int encodedLength = encodedLength(fileDescBytes.length, (int) length);
        validateConfiguredLength(encodedLength);
        String md5sum;
        try (InputStream inputStream = new FileInputStream(file)) {
            md5sum = SecurityUtils.md5(inputStream);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read FILE payload " + file, e);
        }
        if (md5sum == null || md5sum.length() != 32) {
            throw new IllegalStateException("Unable to calculate FILE payload checksum for " + file);
        }
        if (file.length() != length) {
            throw new IllegalStateException("FILE payload changed while calculating checksum: " + file);
        }
        return new FilePacketPayload(file, fileDesc, md5sum, (int) length, fileDescBytes, false, null);
    }

    static FilePacketPayload received(File file,
                                      Path ownedDirectory,
                                      FileDesc fileDesc,
                                      String md5sum,
                                      int fileLength) {
        byte[] fileDescBytes = encodeAndValidateFileDesc(fileDesc);
        if (!file.isFile() || file.length() != fileLength) {
            throw new IllegalArgumentException("Received FILE length does not match its declared length");
        }
        return new FilePacketPayload(file, fileDesc, normalizeMd5(md5sum), fileLength, fileDescBytes, true,
                Objects.requireNonNull(ownedDirectory, "ownedDirectory"));
    }

    static byte[] encodeAndValidateFileDesc(FileDesc fileDesc) {
        validateFileName(fileDesc == null ? null : fileDesc.getFileName());
        // The original V1 wire format used the process default charset for this JSON field.
        byte[] bytes = GSON.toJson(fileDesc).getBytes(Charset.defaultCharset());
        if (bytes.length <= 0 || bytes.length > SocketPacketLimits.MAX_FILE_DESCRIPTION_LENGTH_BYTES) {
            throw new IllegalArgumentException("Invalid FILE description length: " + bytes.length);
        }
        return bytes;
    }

    static void validateFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new IllegalArgumentException("FILE name is empty");
        }
        byte[] bytes = fileName.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > SocketPacketLimits.MAX_FILE_NAME_LENGTH_BYTES
                || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0
                || ".".equals(fileName) || "..".equals(fileName)) {
            throw new IllegalArgumentException("Invalid FILE name: " + fileName);
        }
        for (int i = 0; i < fileName.length(); i++) {
            if (fileName.charAt(i) == 0 || Character.isISOControl(fileName.charAt(i))) {
                throw new IllegalArgumentException("Invalid FILE name: " + fileName);
            }
        }
    }

    static String normalizeMd5(String md5sum) {
        if (md5sum == null || !md5sum.matches("(?i)[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Invalid FILE checksum");
        }
        return md5sum.toLowerCase();
    }

    static int encodedLength(int fileDescLength, int fileLength) {
        long encodedLength = (long) FIXED_METADATA_LENGTH + fileDescLength + fileLength;
        if (fileLength < 0 || encodedLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid FILE payload length: " + fileLength);
        }
        return (int) encodedLength;
    }

    private static void validateConfiguredLength(int encodedLength) {
        try {
            SocketPacketLimits.validateDataLength(encodedLength,
                    SocketPacketLimits.configuredMaxFileDataLengthBytes(), ContentType.FILE);
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static void validateReadableFile(File file) {
        if (file == null || !file.isFile() || !file.canRead()) {
            throw new IllegalArgumentException("FILE payload is not a readable regular file: " + file);
        }
    }

    ByteBuffer metadataBuffer() {
        ensureOpen();
        byte[] checksum = md5sum.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buffer = ByteBuffer.allocate(FIXED_METADATA_LENGTH + fileDescBytes.length);
        buffer.put(HexaConversionUtil.intToByteArrayH(fileDescBytes.length));
        buffer.put(fileDescBytes);
        buffer.put(checksum);
        buffer.put(HexaConversionUtil.intToByteArrayH(fileLength));
        buffer.flip();
        return buffer;
    }

    int encodedLength() {
        return encodedLength(fileDescBytes.length, fileLength);
    }

    void validateSourceLength() throws IOException {
        ensureOpen();
        if (!file.isFile() || !file.canRead()) {
            throw new IOException("FILE payload is no longer readable: " + file);
        }
        if (file.length() != fileLength) {
            throw new IOException("FILE payload length changed before send: expected " + fileLength
                    + ", actual " + file.length());
        }
    }

    void validateSourceChecksum() throws IOException {
        ensureOpen();
        String currentChecksum;
        try (InputStream inputStream = new FileInputStream(file)) {
            currentChecksum = SecurityUtils.md5(inputStream);
        }
        if (!md5sum.equals(currentChecksum)) {
            throw new IOException("FILE payload changed after checksum calculation: " + file);
        }
    }

    public File getFile() {
        ensureOpen();
        return file;
    }

    public FileDesc getFileDesc() {
        ensureOpen();
        return fileDesc;
    }

    public String getMd5sum() {
        ensureOpen();
        return md5sum;
    }

    public long getFileLength() {
        ensureOpen();
        return fileLength;
    }

    public InputStream openStream() throws IOException {
        ensureOpen();
        return new FileInputStream(file);
    }

    public void copyTo(Path target) throws IOException {
        ensureOpen();
        Files.copy(file.toPath(), Objects.requireNonNull(target, "target"), StandardCopyOption.REPLACE_EXISTING);
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("FILE payload has been released");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true) || !deleteOnClose) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Unable to delete received FILE payload " + file, e);
        } finally {
            if (ownedDirectory != null) {
                try {
                    Files.deleteIfExists(ownedDirectory);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Unable to delete received FILE payload directory "
                            + ownedDirectory, e);
                }
            }
        }
    }
}
