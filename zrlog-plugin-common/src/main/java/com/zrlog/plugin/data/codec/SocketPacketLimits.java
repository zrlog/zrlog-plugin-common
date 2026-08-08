package com.zrlog.plugin.data.codec;

import java.net.ProtocolException;

public final class SocketPacketLimits {

    public static final int MAX_METHOD_LENGTH_BYTES = Byte.MAX_VALUE;

    // A 4 MiB HTTP byte[] can expand to about 20 MiB when Gson writes signed byte values as JSON numbers.
    public static final int DEFAULT_MAX_DATA_LENGTH_BYTES = 32 * 1024 * 1024;
    public static final int DEFAULT_MAX_FILE_DATA_LENGTH_BYTES = 512 * 1024 * 1024;
    public static final int ABSOLUTE_MAX_FILE_DATA_LENGTH_BYTES = 1024 * 1024 * 1024;
    public static final int MAX_FILE_DESCRIPTION_LENGTH_BYTES = 64 * 1024;
    public static final int MAX_FILE_NAME_LENGTH_BYTES = 255;
    public static final long DEFAULT_MAX_SESSION_TEMP_FILE_BYTES = DEFAULT_MAX_FILE_DATA_LENGTH_BYTES;
    public static final long DEFAULT_MAX_GLOBAL_TEMP_FILE_BYTES = 2L * DEFAULT_MAX_FILE_DATA_LENGTH_BYTES;

    public static final String MAX_DATA_LENGTH_PROPERTY = "zrlog.plugin.socket.maxDataLengthBytes";
    public static final String MAX_DATA_LENGTH_ENV = "PLUGIN_SOCKET_MAX_DATA_LENGTH_BYTES";
    public static final String MAX_FILE_DATA_LENGTH_PROPERTY = "zrlog.plugin.socket.maxFileDataLengthBytes";
    public static final String MAX_FILE_DATA_LENGTH_ENV = "PLUGIN_SOCKET_MAX_FILE_DATA_LENGTH_BYTES";
    public static final String MAX_SESSION_TEMP_FILE_BYTES_PROPERTY = "zrlog.plugin.socket.maxSessionTempFileBytes";
    public static final String MAX_SESSION_TEMP_FILE_BYTES_ENV = "PLUGIN_SOCKET_MAX_SESSION_TEMP_FILE_BYTES";
    public static final String MAX_GLOBAL_TEMP_FILE_BYTES_PROPERTY = "zrlog.plugin.socket.maxGlobalTempFileBytes";
    public static final String MAX_GLOBAL_TEMP_FILE_BYTES_ENV = "PLUGIN_SOCKET_MAX_GLOBAL_TEMP_FILE_BYTES";

    private SocketPacketLimits() {
    }

    public static int configuredMaxDataLengthBytes() {
        String value = System.getProperty(MAX_DATA_LENGTH_PROPERTY);
        String source = MAX_DATA_LENGTH_PROPERTY;
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(MAX_DATA_LENGTH_ENV);
            source = MAX_DATA_LENGTH_ENV;
        }
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT_MAX_DATA_LENGTH_BYTES;
        }
        try {
            int configuredValue = Integer.parseInt(value.trim());
            if (configuredValue <= 0) {
                throw new IllegalArgumentException(source + " must be greater than zero");
            }
            return configuredValue;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(source + " must be a valid integer", e);
        }
    }

    public static int configuredMaxFileDataLengthBytes() {
        long value = configuredPositiveLong(MAX_FILE_DATA_LENGTH_PROPERTY, MAX_FILE_DATA_LENGTH_ENV,
                DEFAULT_MAX_FILE_DATA_LENGTH_BYTES);
        if (value > ABSOLUTE_MAX_FILE_DATA_LENGTH_BYTES) {
            throw new IllegalArgumentException(MAX_FILE_DATA_LENGTH_PROPERTY + " must be at most "
                    + ABSOLUTE_MAX_FILE_DATA_LENGTH_BYTES);
        }
        return (int) value;
    }

    public static long configuredMaxSessionTempFileBytes() {
        return configuredPositiveLong(MAX_SESSION_TEMP_FILE_BYTES_PROPERTY, MAX_SESSION_TEMP_FILE_BYTES_ENV,
                DEFAULT_MAX_SESSION_TEMP_FILE_BYTES);
    }

    public static long configuredMaxGlobalTempFileBytes() {
        return configuredPositiveLong(MAX_GLOBAL_TEMP_FILE_BYTES_PROPERTY, MAX_GLOBAL_TEMP_FILE_BYTES_ENV,
                DEFAULT_MAX_GLOBAL_TEMP_FILE_BYTES);
    }

    private static long configuredPositiveLong(String property, String environment, long fallback) {
        String value = System.getProperty(property);
        String source = property;
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(environment);
            source = environment;
        }
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            long configuredValue = Long.parseLong(value.trim());
            if (configuredValue <= 0L) {
                throw new IllegalArgumentException(source + " must be greater than zero");
            }
            return configuredValue;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(source + " must be a valid integer", e);
        }
    }

    static void validateMethodLength(int methodLength) throws ProtocolException {
        if (methodLength <= 0 || methodLength > MAX_METHOD_LENGTH_BYTES) {
            throw new ProtocolException("Invalid socket packet method length: " + methodLength
                    + ", expected 1.." + MAX_METHOD_LENGTH_BYTES);
        }
    }

    static void validateDataLength(int dataLength, int maxDataLength, ContentType contentType) throws ProtocolException {
        if (dataLength < 0 || dataLength > maxDataLength) {
            throw new ProtocolException("Invalid " + contentType + " socket packet data length: " + dataLength
                    + ", expected 0.." + maxDataLength);
        }
    }
}
